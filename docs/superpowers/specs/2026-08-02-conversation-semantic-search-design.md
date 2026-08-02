# 会话语义搜索融合（FTS + Embedding + RRF）

日期：2026-08-02
状态：已批准设计

## 背景

RikkaHub 现有历史搜索是**纯词法全文检索**：SQLite FTS5 + jieba 分词 + BM25 相关度，索引在会话保存时增量维护。它能精确命中字面词，但**无法召回语义相关的改写**（同义词、近义表达、跨语言）。

参考 Agora 的记忆实现（`references/Agora`），其语义搜索（消息级 embedding + 余弦）和 `read_conversation` 深读工具是 RikkaHub 缺失的能力。本设计为 RikkaHub 增加**语义召回层**，与现有 FTS 用 **RRF（倒数排名融合）** 合并，并新增 `read_conversation` 工具，让 AI 能按选中分支精读历史会话。

## 目标

- 让 `conversation_search` AI 工具能召回语义相关（而非仅字面命中）的历史消息
- 保留现有 FTS 的精确词法命中优势，两者融合而非替换
- 新消息**自动**进入语义索引（无需手动重建）
- 不改变现有 UI 搜索页行为（仍走 FTS），升级点集中在 AI 工具路径
- 无 embedder 配置 / 无索引 / API 失败时**平滑降级**为纯 FTS

## 非目标（YAGNI）

- 不改变 FTS 索引本身的逻辑（继续按现状在保存时重建）
- 不做 UI 搜索页的语义化改造
- 不做增量索引之外的复杂调度（不做队列、不做多模型并发）
- 不做重排（rerank）层
- 不索引工具调用记录（tool/result/system 消息，与 FTS 的 `searchMessages` 过滤一致）

## 设计决策（已确认）

| 决策 | 选择 |
|---|---|
| Embedder 接入 | **可配置，远程优先**：独立 `EmbedderConfig`（baseUrl/model/apiKey/batchSize），默认 DashScope `qwen3-embedding-8b`，baseUrl 可切 Ollama/vLLM 本地 |
| 融合策略 | **RRF 分数融合**：语义余弦分 + FTS BM25 rank，`score = Σ 1/(k + rank)`，k=60，按 messageId 去重 |
| read_conversation 分支 | **只读选中分支**：复用 `Conversation.currentMessages`（按 `selectIndex` 线性化） |
| 索引生命周期 | **方案 A：WorkManager 后台索引**。保存路径只做轻量 `markPending`（纯本地写），嵌入由 `EmbeddingIndexWorker` 延迟批量执行 |

## 组件

### 新增

| 组件 | 位置 | 职责 |
|---|---|---|
| `EmbedderConfig` | `data/embedding/` | 全局 embedding 配置（DataStore）：`enabled / baseUrl / model / apiKey / batchSize` |
| `EmbeddingClient` | `data/embedding/` | OpenAI 兼容 `POST {baseUrl}/embeddings`，单条/批量 → `FloatArray`。复用现有 OkHttp 基建 |
| `EmbeddingIndexer` | `data/embedding/` | float↔byte（BIG_ENDIAN float32）+ 余弦相似度 |
| `MessageEmbeddingEntity` | `data/db/entity/` | Room entity，见数据模型 |
| `SemanticIndexManager` | `data/embedding/` | `markPending(conversation)` / `indexPending()` / `deleteConversation(id)` / `deleteAll()` / `rebuildAllIndexes(onProgress)` / `search(query, limit)` |
| `EmbeddingIndexWorker` | `service/` | WorkManager 后台索引 worker |
| `read_conversation` tool | `data/ai/tools/ConversationTools.kt` | 新增第三个对话工具 |

### 修改

| 文件 | 改动 |
|---|---|
| `ConversationRepository` | `insertConversation` / `updateConversation` 内新增 `semanticIndexManager.markPending(conversation)`；`deleteConversation` 新增删除语义索引；新增 `searchMessagesHybrid` 走 RRF |
| `ConversationTools.kt` | 新增 `read_conversation`；`recent_chats` 上限 30→50 |
| `DataSourceModule.kt` | Room migration 24→25（新增 `MessageEmbedding` 表） |
| 设置页（新增） | embedder 配置入口 + 「重建语义索引」动作 |

## 数据模型

### `message_embeddings` 表（Room entity，migration 24→25）

```
message_id      TEXT PRIMARY KEY     -- UIMessage.id
node_id         TEXT                 -- 所属 MessageNode.id
conversation_id TEXT                 -- 所属会话 id
model_name      TEXT                 -- 嵌入时用的模型名（维度失配检测）
status          INTEGER              -- 0=PENDING 1=INDEXED 2=DIRTY 3=FAILED
chunk_text      TEXT                 -- 索引时的文本快照（脏检测用）
embedding       BLOB?                -- float32 字节，INDEXED 时非空
dimension       INTEGER?             -- 嵌入维度，INDEXED 时非空
updated_at      INTEGER              -- epoch millis
```

**状态机**：
- `PENDING`：markPending 时插入（尚无 embedding）
- `INDEXED`：worker 嵌入成功
- `DIRTY`：markPending 发现已存在但 `chunk_text` 快照与当前文本不一致（编辑/重新生成）
- `FAILED`：worker 嵌入失败（可重试，超过重试上限保留以便重建）

### EmbedderConfig（DataStore，全局单例）

```
enabled: Boolean
baseUrl: String      // 默认 https://dashscope.aliyuncs.com/compatible-mode/v1
model:   String      // 默认 qwen3-embedding-8b
apiKey:  String
batchSize: Int       // 默认 8
```

## 数据流

### 索引写入（保存路径，轻量）

```
insertConversation / updateConversation（事务提交后）:
  └─ messageFtsManager.indexConversation(conversation)      // 现状不变
  └─ semanticIndexManager.markPending(conversation)          // 新增，纯本地写
       └─ 对每条 USER/ASSISTANT 且文本非空的消息:
            已有行?  ── 文本快照相同 → 跳过
                   └─ 文本快照不同 → status=DIRTY, 更新 chunk_text
            无行?   ── 插入 status=PENDING + chunk_text
       └─ 若存在任何 PENDING/DIRTY → enqueueWork（唯一 one-off，delay 30s）
```

markPending 不触发任何 API 调用，开销 ≈ 1 条 `SELECT` + 若干 upsert，可接受。

### 后台嵌入（EmbeddingIndexWorker）

```
doWork:
  └─ 读 message_embeddings WHERE status IN (PENDING, DIRTY, FAILED)
     LIMIT batchSize，循环分页
  └─ 对每条 chunk_text 调 EmbeddingClient（批量）
  └─ 成功 → status=INDEXED + embedding + dimension + model_name
  └─ 失败 → status=FAILED（WorkManager backoff 重试；连续失败保留）
```

唯一 one-off work name：`embedding-index`，`ExistingWorkPolicy.REPLACE`（新消息到来重新排期），重试走 WorkManager 默认 backoff。

### 搜索（RRF 融合）

```
searchMessagesHybrid(query, limit):
  semantic = semanticSearch(query)     // 有配置+索引时；否则空列表
  fts      = ftsSearch(query)          // 现状 searchMessages
  if semantic 为空 → 返回 fts          // 平滑降级
  否则 → RRF 融合（k=60）去重 → 返回

semanticSearch:
  query 嵌入（EmbeddingClient）
  └─ 全表余弦扫描（维度与当前 model_name 一致的行）
  └─ score > 阈值（常量 RAG_THRESHOLD = 0.3，后续可做成设置项）→ 降序 → 返回 (MessageSearchResult, score)
  维度失配 / 无索引 / 嵌入失败 → 返回空（触发降级）
```

## 工具变更

### 新增 `read_conversation`

```
参数: conversation_id(必填), offset(默认0), limit(默认50, 1-100)
逻辑:
  └─ 加载会话，用 conversation.currentMessages 线性化选中分支
  └─ 过滤 role ∈ {USER, ASSISTANT}，跳过 tool/system
  └─ offset/limit 分页，返回 total_messages / has_more
返回: {conversation_id, title, total_messages, messages:[{role, text, timestamp}]}
```

### 修改 `recent_chats`

- `coerceIn(1, 30)` → `coerceIn(1, 50)`
- 描述文案 `max: 30` → `max: 50`

### `conversation_search`

- 改用 `searchMessagesHybrid`（原走 `searchMessages`）

## 错误处理与降级

| 场景 | 行为 |
|---|---|
| `enabled=false` 或未配置 | `searchMessagesHybrid` 直接走纯 FTS，不报错 |
| 嵌入 API 失败（网络/404/超时） | 该行 `FAILED`；worker 重试；不中断整批 |
| 查询嵌入失败 | 返回纯 FTS 结果 |
| 维度失配（模型变了） | 检测到存储维度 ≠ 当前模型维度 → 纯 FTS + 日志提示重建索引 |
| 无索引（从未 markPending） | 语义返回空 → 纯 FTS |
| 搜索页重建索引 | `rebuildAllIndexes` 全量重嵌（覆盖所有消息，清 FAILED） |

## 测试

### 单元测试
- `EmbeddingIndexer`：float↔byte 往返；余弦相似度（相同=1、正交=0、零向量=0）
- RRF 融合：两路重叠 / 只一路 / 两路空 / 去重正确性
- `read_conversation` 分支线性化：多节点、节点内多替代分支（selectIndex 指向）、分页边界
- `markPending` 状态机：新消息→PENDING、文本变化→DIRTY、不变→跳过

### 集成测试
- `searchMessagesHybrid` 在无 embedding 配置时返回与 `searchMessages` 一致（不回归）
- 配置后：语义命中的同义改写出现在结果中，且与 FTS 命中去重
- WorkManager worker 对 PENDING 消息嵌入后置为 INDEXED

### 手动验证
- 发一条新消息 → 30s 后语义搜索能召回
- 关网络 → 搜索自动降级 FTS，无崩溃
- 切换 embedder 模型 → 维度失配触发降级 + 提示重建

## 未来工作（本次不做）

- 增量钩子的进一步优化（如改每消息粒度触发）
- UI 搜索页接入语义（当前只升级 AI 工具路径）
- 结果重排（rerank）层
