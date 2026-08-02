# 会话语义搜索融合（FTS + Embedding + RRF）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RikkaHub 增加语义召回层，与现有 FTS 全文检索用 RRF 融合，让 `conversation_search` AI 工具能召回语义相关历史消息；新增 `read_conversation` 工具让 AI 按选中分支精读历史会话。

**Architecture:** 新增 `data/embedding/` 包承载 embedding 能力（配置、客户端、索引管理、纯函数工具）。保存路径在 `ConversationRepository.insert/updateConversation` 里追加轻量 `markPending`（纯本地写），`EmbeddingIndexWorker`（WorkManager）延迟批量嵌入 `message_embeddings` 表。搜索时 `searchMessagesHybrid` 并行跑 FTS + 语义余弦，用 RRF（k=60）融合去重；无配置/无索引/失败时平滑降级纯 FTS。`read_conversation` 复用 `Conversation.currentMessages` 线性化选中分支。

**Tech Stack:** Kotlin, Room (v24→25), DataStore (SettingsStore), OkHttp, kotlinx.serialization, WorkManager (`androidx.work.runtime.ktx` + `koin.androidx.workmanager`), JUnit (JVM 单测，无 Robolectric)。

## Global Constraints

- Room 版本 24 → 25；新增 `message_embeddings` 表用 `AutoMigration(from = 24, to = 25)`
- 测试为 JVM 单测（`libs.junit`）；Room/DataStore/WorkManager 相关流程**只做纯函数单测 + 手动/编译验证**，不引 Robolectric
- 工具门控沿用 `assistant.enableRecentChatsReference`（不新增开关）
- 文本抽取规则与 FTS 一致：`UIMessagePart.Text` 拼接，截断 10000 字符
- 语义阈值常量 `RAG_THRESHOLD = 0.3`，RRF `k = 60`，batch `batchSize = 8`（config 可改）
- 全部新增代码放 `data/embedding/` 包；不改现有 UI 搜索页行为
- 提交信息按项目惯例（`feat:` / `refactor:` 前缀），每条任务末尾提交一次

---

### Task 1: EmbedderConfig 数据类 + SettingsStore 更新方法

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/datastore/EmbedderConfigTest.kt`

**Interfaces:**
- Consumes: 现有 `SettingsStore` / `Settings`（`PreferencesStore.kt:521`）
- Produces: `@Serializable data class EmbedderConfig(...)`；`Settings.embedder` 字段；`SettingsStore.updateEmbedder(fn: (EmbedderConfig) -> EmbedderConfig)`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/me/rerere/rikkahub/data/datastore/EmbedderConfigTest.kt`：
```kotlin
package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedderConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default config is disabled with dashscope defaults`() {
        val c = EmbedderConfig()
        assertFalse(c.enabled)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", c.baseUrl)
        assertEquals("qwen3-embedding-8b", c.model)
        assertEquals(8, c.batchSize)
    }

    @Test
    fun `config survives json round trip`() {
        val c = EmbedderConfig(enabled = true, model = "text-embedding-3-small", batchSize = 16)
        val decoded = json.decodeFromString<EmbedderConfig>(json.encodeToString(c))
        assertEquals(c, decoded)
    }

    @Test
    fun `settings missing embedder field decodes to default`() {
        // 旧版本 Settings 序列化不含 embedder 字段 → 应回落到默认值
        val oldSettingsJson = """{"init":false,"dynamicColor":true}"""
        val s = json.decodeFromString<Settings>(oldSettingsJson)
        assertFalse(s.embedder.enabled)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.EmbedderConfigTest"`
Expected: 编译失败，`EmbedderConfig` / `Settings.embedder` 未定义

- [ ] **Step 3: 实现 EmbedderConfig + Settings 字段**

在 `PreferencesStore.kt` 的 `Settings` 定义之前（约 `:520` 前）加入：
```kotlin
@Serializable
data class EmbedderConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val model: String = "qwen3-embedding-8b",
    val apiKey: String = "",
    val batchSize: Int = 8,
)
```
在 `data class Settings(` 内新增字段（放任意带默认值的位置即可）：
```kotlin
    val embedder: EmbedderConfig = EmbedderConfig(),
```

- [ ] **Step 4: 在 SettingsStore 加更新方法**

`PreferencesStore.kt` 的 `SettingsStore` 类内，仿照已有 `update` / `updateAssistant` 方法（`:430` / `:434`）：
```kotlin
    suspend fun updateEmbedder(fn: (EmbedderConfig) -> EmbedderConfig) {
        update { it.copy(embedder = fn(it.embedder)) }
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.EmbedderConfigTest"`
Expected: PASS（3 个用例）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/test/java/me/rerere/rikkahub/data/datastore/EmbedderConfigTest.kt
git commit -m "feat(embedding): add EmbedderConfig to settings store"
```

---

### Task 2: EmbeddingIndexer 纯工具（float↔byte + 余弦相似度）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/embedding/EmbeddingIndexer.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingIndexerTest.kt`

**Interfaces:**
- Consumes: 无（纯函数）
- Produces: `object EmbeddingIndexer { fun floatsToBytes(FloatArray): ByteArray; fun bytesToFloats(ByteArray): FloatArray; fun cosineSimilarity(a: FloatArray, b: FloatArray): Float }`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingIndexerTest.kt`：
```kotlin
package me.rerere.rikkahub.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingIndexerTest {
    @Test
    fun `float array round trips through bytes`() {
        val floats = floatArrayOf(0.1f, -0.5f, 1.0f, 0.0f)
        val out = EmbeddingIndexer.bytesToFloats(EmbeddingIndexer.floatsToBytes(floats))
        assertEquals(floats.size, out.size)
        for (i in floats.indices) assertEquals(floats[i], out[i], 1e-6f)
    }

    @Test
    fun `identical vectors have cosine 1`() {
        val v = floatArrayOf(1f, 0f, 2f)
        assertEquals(1f, EmbeddingIndexer.cosineSimilarity(v, v), 1e-6f)
    }

    @Test
    fun `orthogonal vectors have cosine 0`() {
        assertEquals(0f, EmbeddingIndexer.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun `zero vector yields cosine 0`() {
        assertEquals(0f, EmbeddingIndexer.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 1e-6f)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.EmbeddingIndexerTest"`
Expected: 编译失败，`EmbeddingIndexer` 未定义

- [ ] **Step 3: 实现（照搬 Agora 的 `EmbeddingIndexer.kt` 33 行实现）**

```kotlin
package me.rerere.rikkahub.data.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder

object EmbeddingIndexer {
    fun floatsToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (f in floats) buffer.putFloat(f)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) floats[i] = buffer.float
        return floats
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.EmbeddingIndexerTest"`
Expected: PASS（4 个用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/embedding/EmbeddingIndexer.kt app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingIndexerTest.kt
git commit -m "feat(embedding): add float-byte and cosine utilities"
```

---

### Task 3: MessageTextExtractor + 纯文本抽取

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/embedding/MessageTextExtractor.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/embedding/MessageTextExtractorTest.kt`

**Interfaces:**
- Consumes: `me.rerere.ai.ui.UIMessage`, `me.rerere.ai.ui.UIMessagePart`
- Produces: `object MessageTextExtractor { fun messageToSearchText(message: UIMessage): String }`

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.embedding

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextExtractorTest {
    @Test
    fun `joins text parts and skips non-text`() {
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("hello "),
                UIMessagePart.Text("world"),
            ),
        )
        assertEquals("hello world", MessageTextExtractor.messageToSearchText(msg))
    }
}
```
> 注：`UIMessage` 构造参数如有其他必填项（如 id），用带默认值的 `UIMessage(role=..., parts=...)` 补全——以 `ai/src/main/java/me/rerere/ai/ui/Message.kt:22` 的实际签名为准。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.MessageTextExtractorTest"`
Expected: 编译失败，`MessageTextExtractor` 未定义

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.embedding

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object MessageTextExtractor {
    fun messageToSearchText(message: UIMessage): String =
        message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .take(10_000)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.MessageTextExtractorTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/embedding/MessageTextExtractor.kt app/src/test/java/me/rerere/rikkahub/data/embedding/MessageTextExtractorTest.kt
git commit -m "feat(embedding): add message text extractor"
```

---

### Task 4: MessageEmbeddingEntity + DAO + Room migration v25

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/entity/MessageEmbeddingEntity.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageEmbeddingDAO.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- Test: 编译验证（`.\gradlew.bat :app:compileDebugKotlin`）

**Interfaces:**
- Consumes: 现有 `AppDatabase`（`AppDatabase.kt`）
- Produces:
  - `@Entity(tableName = "message_embeddings") data class MessageEmbeddingEntity(...)`
  - `interface MessageEmbeddingDAO`（`upsertAll` / `getByConversation` / `getPending` / `getByModel` / `deleteByConversation` / `deleteAll` / `getMessageIdsOfConversation`）
  - `object EmbeddingStatus { const val PENDING=0; const val INDEXED=1; const val DIRTY=2; const val FAILED=3 }`
  - `AppDatabase.messageEmbeddingDao(): MessageEmbeddingDAO`；version 25；`AutoMigration(from=24, to=25)`

- [ ] **Step 1: 写实体**

`app/src/main/java/me/rerere/rikkahub/data/db/entity/MessageEmbeddingEntity.kt`：
```kotlin
package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_embeddings")
data class MessageEmbeddingEntity(
    @PrimaryKey val messageId: String,
    val nodeId: String,
    val conversationId: String,
    val modelName: String,
    val status: Int,
    val chunkText: String,
    val embedding: ByteArray? = null,
    val dimension: Int? = null,
    val updatedAt: Long = 0L,
)
```
（`ByteArray` Room 原生映射为 BLOB，无需 TypeConverter。）

- [ ] **Step 2: 写状态常量**

在 `MessageEmbeddingEntity.kt` 同文件追加：
```kotlin
object EmbeddingStatus {
    const val PENDING = 0
    const val INDEXED = 1
    const val DIRTY = 2
    const val FAILED = 3
}
```

- [ ] **Step 3: 写 DAO**

`app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageEmbeddingDAO.kt`：
```kotlin
package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MessageEmbeddingEntity

@Dao
interface MessageEmbeddingDAO {
    @Query("SELECT * FROM message_embeddings WHERE conversation_id = :conversationId")
    suspend fun getByConversation(conversationId: String): List<MessageEmbeddingEntity>

    @Query("SELECT * FROM message_embeddings WHERE status IN (:statuses) LIMIT :limit")
    suspend fun getPending(statuses: List<Int>, limit: Int): List<MessageEmbeddingEntity>

    @Query("SELECT * FROM message_embeddings WHERE model_name = :modelName")
    suspend fun getByModel(modelName: String): List<MessageEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM message_embeddings")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MessageEmbeddingEntity>)

    @Query("DELETE FROM message_embeddings WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_embeddings")
    suspend fun deleteAll()
}
```

- [ ] **Step 4: 接入 AppDatabase**

`AppDatabase.kt`：
- import 加 `me.rerere.rikkahub.data.db.dao.MessageEmbeddingDAO`、`me.rerere.rikkahub.data.db.entity.MessageEmbeddingEntity`
- `entities = [...]` 列表加 `MessageEmbeddingEntity::class`
- `version = 24` → `version = 25`
- `autoMigrations = [...]` 加 `AutoMigration(from = 24, to = 25)`
- 类内加访问器：
```kotlin
    abstract fun messageEmbeddingDao(): MessageEmbeddingDAO
```

- [ ] **Step 5: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（migration 声明会触发 Room schema 校验；若提示需要 `AutoMigrationSpec`，按报错补空 spec）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/db/entity/MessageEmbeddingEntity.kt app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageEmbeddingDAO.kt app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt
git commit -m "feat(embedding): add message_embeddings table and Room migration v25"
```

---

### Task 5: EmbeddingClient（OpenAI 兼容 /embeddings）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/embedding/EmbeddingClient.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingClientTest.kt`

**Interfaces:**
- Consumes: `okhttp3.OkHttpClient`（构造注入）、`kotlinx.serialization`
- Produces:
  - `class EmbeddingClient(private val okHttpClient: OkHttpClient)`
  - `suspend fun computeEmbeddings(texts: List<String>, model: String, baseUrl: String, apiKey: String): List<FloatArray?>`（失败项为 null）
  - `fun parseEmbeddingResponse(body: String, count: Int): List<FloatArray?>`（纯函数，可单测）

- [ ] **Step 1: 写失败测试（解析函数）**

`app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingClientTest.kt`：
```kotlin
package me.rerere.rikkahub.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddingClientTest {
    @Test
    fun `parses embeddings from openai response`() {
        val body = """{"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}"""
        val out = EmbeddingClient.parseEmbeddingResponse(body, 2)
        assertEquals(2, out.size)
        assertEquals(0.1f, out[0]!![0], 1e-6f)
        assertEquals(0.4f, out[1]!![1], 1e-6f)
    }

    @Test
    fun `malformed response yields nulls`() {
        val out = EmbeddingClient.parseEmbeddingResponse("not json", 1)
        assertEquals(1, out.size)
        assertNull(out[0])
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.EmbeddingClientTest"`
Expected: 编译失败，`EmbeddingClient` 未定义

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.embedding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log

private const val TAG = "EmbeddingClient"

class EmbeddingClient(private val okHttpClient: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun computeEmbeddings(
        texts: List<String>,
        model: String,
        baseUrl: String,
        apiKey: String,
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        try {
            val body = json.encodeToString(
                mapOf("model" to model, "input" to texts)
            )
            val request = Request.Builder()
                .url("$baseUrl/embeddings")
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.use {
                val responseBody = it.body?.string() ?: return@withContext texts.map { null }
                parseEmbeddingResponse(responseBody, texts.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "computeEmbeddings failed", e)
            texts.map { null }
        }
    }

    fun parseEmbeddingResponse(body: String, count: Int): List<FloatArray?> {
        return try {
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return List(count) { null }
            data.map { item ->
                val arr = item.jsonObject["embedding"]?.jsonArray ?: return@map null
                FloatArray(arr.size) { i -> arr[i].jsonPrimitive.float }
            }.let { list -> list + List((count - list.size).coerceAtLeast(0)) { null } }
        } catch (e: Exception) {
            List(count) { null }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.EmbeddingClientTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/embedding/EmbeddingClient.kt app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingClientTest.kt
git commit -m "feat(embedding): add OpenAI-compatible embeddings client"
```

---

### Task 6: RRF 融合纯函数 + SemanticHit

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/embedding/RrfFusion.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/embedding/RrfFusionTest.kt`

**Interfaces:**
- Consumes: `me.rerere.rikkahub.data.db.fts.MessageSearchResult`
- Produces:
  - `data class SemanticHit(messageId: String, nodeId: String, conversationId: String, score: Float, chunkText: String)`
  - `fun rrfFuse(fts: List<MessageSearchResult>, semantic: List<MessageSearchResult>, k: Int = 60): List<MessageSearchResult>`
  - `const val RAG_THRESHOLD = 0.3f`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/me/rerere/rikkahub/data/embedding/RrfFusionTest.kt`：
```kotlin
package me.rerere.rikkahub.data.embedding

import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RrfFusionTest {
    private fun res(id: String) = MessageSearchResult(
        nodeId = "n-$id", messageId = id, conversationId = "c",
        title = "t", updateAt = Instant.EPOCH, snippet = "",
    )

    @Test
    fun `message in both lists ranks above single-list hits`() {
        val fts = listOf(res("a"), res("b"))
        val semantic = listOf(res("a"), res("c"))
        val fused = rrfFuse(fts, semantic, k = 60)
        // a 两路命中分数最高，排第一
        assertEquals("a", fused.first().messageId)
        // 去重后共 3 条
        assertEquals(setOf("a", "b", "c"), fused.map { it.messageId }.toSet())
    }

    @Test
    fun `empty semantic returns fts order`() {
        val fts = listOf(res("a"), res("b"))
        assertEquals(listOf("a", "b"), rrfFuse(fts, emptyList()).map { it.messageId })
    }

    @Test
    fun `empty fts returns semantic order`() {
        val semantic = listOf(res("a"), res("b"))
        assertEquals(listOf("a", "b"), rrfFuse(emptyList(), semantic).map { it.messageId })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.RrfFusionTest"`
Expected: 编译失败，`rrfFuse` 未定义

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.embedding

import me.rerere.rikkahub.data.db.fts.MessageSearchResult

const val RAG_THRESHOLD = 0.3f

data class SemanticHit(
    val messageId: String,
    val nodeId: String,
    val conversationId: String,
    val score: Float,
    val chunkText: String,
)

fun rrfFuse(
    fts: List<MessageSearchResult>,
    semantic: List<MessageSearchResult>,
    k: Int = 60,
): List<MessageSearchResult> {
    if (fts.isEmpty()) return semantic
    if (semantic.isEmpty()) return fts

    val scores = linkedMapOf<String, Double>()
    val byId = linkedMapOf<String, MessageSearchResult>()
    fun addRanked(list: List<MessageSearchResult>) {
        list.forEachIndexed { index, item ->
            byId.putIfAbsent(item.messageId, item)
            val s = scores[item.messageId] ?: 0.0
            scores[item.messageId] = s + 1.0 / (k + index + 1)
        }
    }
    addRanked(fts)
    addRanked(semantic)

    return scores.entries
        .sortedByDescending { it.value }
        .mapNotNull { byId[it.key] }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.RrfFusionTest"`
Expected: PASS（3 个用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/embedding/RrfFusion.kt app/src/test/java/me/rerere/rikkahub/data/embedding/RrfFusionTest.kt
git commit -m "feat(embedding): add RRF fusion and SemanticHit"
```

---

### Task 7: EmbeddingPlanner 纯函数（markPending 计划 + 评分）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/embedding/EmbeddingPlanner.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingPlannerTest.kt`

**Interfaces:**
- Consumes: `UIMessage`、`MessageEmbeddingEntity`、`EmbeddingStatus`、`SemanticHit`、`EmbeddingIndexer`
- Produces:
  - `fun planMarkPending(messages: List<Pair<String, UIMessage>>, existing: List<MessageEmbeddingEntity>, conversationId: String, modelName: String, now: Long): List<MessageEmbeddingEntity>` — `messages` 为 `(nodeId, message)` 对；返回需要 upsert 的行（新消息 PENDING / 文本变化 DIRTY），相同的跳过
  - `fun scoreSemanticHits(queryEmbedding: FloatArray, rows: List<MessageEmbeddingEntity>, threshold: Float, limit: Int): List<SemanticHit>`

**业务规则（与 spec 一致）：** 只处理 `MessageRole.USER` / `MessageRole.ASSISTANT` 且 `messageToSearchText` 非空的消息。

- [ ] **Step 1: 写失败测试**

`app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingPlannerTest.kt`：
```kotlin
package me.rerere.rikkahub.data.embedding

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MessageEmbeddingEntity
import me.rerere.rikkahub.data.db.entity.EmbeddingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingPlannerTest {

    private fun msg(id: String, role: MessageRole, text: String): UIMessage =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `new user message becomes pending`() {
        val planned = planMarkPending(
            messages = listOf("n1" to msg("m1", MessageRole.USER, "hi")),
            existing = emptyList(),
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertEquals(1, planned.size)
        assertEquals(EmbeddingStatus.PENDING, planned[0].status)
        assertEquals("hi", planned[0].chunkText)
        assertEquals("n1", planned[0].nodeId)
    }

    @Test
    fun `unchanged message is not planned`() {
        val existing = listOf(
            MessageEmbeddingEntity(
                messageId = "m1", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED, chunkText = "hi", updatedAt = 1L,
            ),
        )
        val planned = planMarkPending(
            messages = listOf("n" to msg("m1", MessageRole.USER, "hi")),
            existing = existing,
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `changed text marks dirty`() {
        val existing = listOf(
            MessageEmbeddingEntity(
                messageId = "m1", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED, chunkText = "old", updatedAt = 1L,
            ),
        )
        val planned = planMarkPending(
            messages = listOf("n" to msg("m1", MessageRole.USER, "new text")),
            existing = existing,
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertEquals(1, planned.size)
        assertEquals(EmbeddingStatus.DIRTY, planned[0].status)
    }

    @Test
    fun `tool and system messages are skipped`() {
        val planned = planMarkPending(
            messages = listOf(
                "n" to msg("t", MessageRole.TOOL, "tool call"),
                "n" to msg("s", MessageRole.SYSTEM, "system"),
                "n" to msg("u", MessageRole.USER, ""),
            ),
            existing = emptyList(),
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertTrue(planned.isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.EmbeddingPlannerTest"`
Expected: 编译失败，`planMarkPending` 未定义

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.embedding

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.EmbeddingStatus
import me.rerere.rikkahub.data.db.entity.MessageEmbeddingEntity

fun planMarkPending(
    messages: List<Pair<String, UIMessage>>,
    existing: List<MessageEmbeddingEntity>,
    conversationId: String,
    modelName: String,
    now: Long,
): List<MessageEmbeddingEntity> {
    val existingByMessageId = existing.associateBy { it.messageId }
    val planned = mutableListOf<MessageEmbeddingEntity>()
    for ((nodeId, message) in messages) {
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) continue
        val text = MessageTextExtractor.messageToSearchText(message)
        if (text.isBlank()) continue

        val messageId = message.id.toString()
        val old = existingByMessageId[messageId]
        when {
            old == null -> planned.add(
                MessageEmbeddingEntity(
                    messageId = messageId,
                    nodeId = nodeId,
                    conversationId = conversationId,
                    modelName = modelName,
                    status = EmbeddingStatus.PENDING,
                    chunkText = text,
                    updatedAt = now,
                ),
            )
            old.chunkText != text -> planned.add(
                old.copy(status = EmbeddingStatus.DIRTY, chunkText = text, updatedAt = now),
            )
            old.status == EmbeddingStatus.FAILED -> planned.add(
                old.copy(status = EmbeddingStatus.PENDING, updatedAt = now),
            )
        }
    }
    return planned
}
```

- [ ] **Step 4: 补 `scoreSemanticHits` 实现**

```kotlin
fun scoreSemanticHits(
    queryEmbedding: FloatArray,
    rows: List<MessageEmbeddingEntity>,
    threshold: Float,
    limit: Int,
): List<SemanticHit> {
    return rows.asSequence()
        .filter { it.status == EmbeddingStatus.INDEXED }
        .filter { it.embedding != null && it.dimension == queryEmbedding.size }
        .mapNotNull { row ->
            val score = EmbeddingIndexer.cosineSimilarity(
                queryEmbedding,
                EmbeddingIndexer.bytesToFloats(row.embedding!!),
            )
            if (score >= threshold) {
                SemanticHit(
                    messageId = row.messageId,
                    nodeId = row.nodeId,
                    conversationId = row.conversationId,
                    score = score,
                    chunkText = row.chunkText,
                ) to score
            } else null
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
        .toList()
}
```

- [ ] **Step 5: 补评分单测**

在上面的测试类追加：
```kotlin
    @Test
    fun `scoring filters by threshold and dimension`() {
        val q = floatArrayOf(1f, 0f)
        val rows = listOf(
            MessageEmbeddingEntity(
                messageId = "a", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED,
                chunkText = "a", embedding = EmbeddingIndexer.floatsToBytes(floatArrayOf(1f, 0f)),
                dimension = 2,
            ),
            MessageEmbeddingEntity(
                messageId = "b", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED,
                chunkText = "b", embedding = EmbeddingIndexer.floatsToBytes(floatArrayOf(0f, 1f)),
                dimension = 2,
            ),
            MessageEmbeddingEntity(
                messageId = "dim", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED,
                chunkText = "dim", embedding = EmbeddingIndexer.floatsToBytes(floatArrayOf(1f)),
                dimension = 1,
            ),
        )
        val hits = scoreSemanticHits(q, rows, threshold = 0.5f, limit = 10)
        assertEquals(listOf("a"), hits.map { it.messageId }) // b 余弦=0 被阈值过滤，dim 维度不符被过滤
    }
```

- [ ] **Step 6: 运行全部测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.embedding.EmbeddingPlannerTest"`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/embedding/EmbeddingPlanner.kt app/src/test/java/me/rerere/rikkahub/data/embedding/EmbeddingPlannerTest.kt
git commit -m "feat(embedding): add mark-pending planner and semantic scoring"
```

---

### Task 8: SemanticIndexManager（编排 markPending / indexPending / search / delete）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/embedding/SemanticIndexManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`（提供 `EmbeddingClient`、`SemanticIndexManager`）

**Interfaces:**
- Consumes: `MessageEmbeddingDAO`、`EmbeddingClient`、`SettingsStore`、`MessageTextExtractor`、`EmbeddingPlanner`、`Context`
- Produces:
  - `class SemanticIndexManager(dao, embeddingClient, settingsStore, context)`
  - `suspend fun markPending(conversation: Conversation): Boolean`（有 PENDING/DIRTY 时内部 enqueue 并返回 true）
  - `suspend fun indexPending(limit: Int = 64): IndexCounts`
  - `data class IndexCounts(indexed: Int, failed: Int)`
  - `suspend fun search(query: String, limit: Int): List<SemanticHit>`
  - `suspend fun deleteConversation(conversationId: Uuid)`
  - `suspend fun deleteAll()`
  - `suspend fun isConfigured(): Boolean`

- [ ] **Step 1: 实现**

```kotlin
package me.rerere.rikkahub.data.embedding

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.MessageEmbeddingDAO
import me.rerere.rikkahub.data.db.entity.EmbeddingStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.EmbeddingIndexWorker
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TAG = "SemanticIndexManager"

class SemanticIndexManager(
    private val dao: MessageEmbeddingDAO,
    private val embeddingClient: EmbeddingClient,
    private val settingsStore: SettingsStore,
    private val context: Context,
) {
    data class IndexCounts(val indexed: Int, val failed: Int)

    suspend fun isConfigured(): Boolean {
        val c = settingsStore.settingsFlowRaw.first().embedder
        return c.enabled && c.apiKey.isNotBlank() && c.baseUrl.isNotBlank()
    }

    private suspend fun currentConfig() =
        settingsStore.settingsFlowRaw.first().embedder

    /** 保存路径调用：纯本地写，标记 PENDING/DIRTY；有活时排期 worker。 */
    suspend fun markPending(conversation: Conversation): Boolean {
        val config = currentConfig()
        if (!config.enabled) return false

        val conversationId = conversation.id.toString()
        val existing = dao.getByConversation(conversationId)
        val now = System.currentTimeMillis()

        // 收集 (nodeId, message)，遍历全部节点下全部消息（与 FTS 索引范围一致）
        val pairs = conversation.messageNodes.flatMap { node ->
            node.messages.map { node.id.toString() to it }
        }
        val planned = planMarkPending(
            messages = pairs,
            existing = existing,
            conversationId = conversationId,
            modelName = config.model,
            now = now,
        )
        if (planned.isEmpty()) return false
        dao.upsertAll(planned)
        EmbeddingIndexWorker.enqueue(context)
        return true
    }

    /** worker 调用：批量嵌入 PENDING/DIRTY/FAILED。 */
    suspend fun indexPending(limit: Int = 64): IndexCounts = withContext(Dispatchers.IO) {
        val config = currentConfig()
        if (!config.enabled) return@withContext IndexCounts(0, 0)

        val rows = dao.getPending(statuses = listOf(
            EmbeddingStatus.PENDING, EmbeddingStatus.DIRTY, EmbeddingStatus.FAILED,
        ), limit = limit)
        if (rows.isEmpty()) return@withContext IndexCounts(0, 0)

        val texts = rows.map { it.chunkText }
        val embeddings = embeddingClient.computeEmbeddings(
            texts = texts,
            model = config.model,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
        )
        val updated = rows.mapIndexed { i, row ->
            val emb = embeddings.getOrNull(i)
            if (emb != null) {
                row.copy(
                    status = EmbeddingStatus.INDEXED,
                    embedding = EmbeddingIndexer.floatsToBytes(emb),
                    dimension = emb.size,
                    modelName = config.model,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                row.copy(status = EmbeddingStatus.FAILED, updatedAt = System.currentTimeMillis())
            }
        }
        dao.upsertAll(updated)
        val indexed = updated.count { it.status == EmbeddingStatus.INDEXED }
        IndexCounts(indexed = indexed, failed = updated.size - indexed)
    }

    /** 搜索：query 嵌入 + 余弦 + 阈值 + 维度保护。 */
    suspend fun search(query: String, limit: Int): List<SemanticHit> = withContext(Dispatchers.IO) {
        val config = currentConfig()
        if (!config.enabled) return@withContext emptyList()

        val queryEmbedding = embeddingClient.computeEmbeddings(
            texts = listOf(query),
            model = config.model,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
        ).firstOrNull() ?: return@withContext emptyList()

        val rows = dao.getByModel(config.model)
        if (rows.isEmpty()) return@withContext emptyList()

        scoreSemanticHits(queryEmbedding, rows, RAG_THRESHOLD, limit)
    }

    suspend fun deleteConversation(conversationId: Uuid) {
        dao.deleteByConversation(conversationId.toString())
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }
}
```

- [ ] **Step 2: 在 DataSourceModule 提供依赖**

`app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`（在 `MessageFtsManager(get())` 附近，`:150`）：
```kotlin
    single { EmbeddingClient(okHttpClient = get()) }
    single {
        SemanticIndexManager(
            dao = get<AppDatabase>().messageEmbeddingDao(),
            embeddingClient = get(),
            settingsStore = get(),
            context = get(),
        )
    }
```
> 注：若 DataSourceModule 没有 `AppDatabase` / `OkHttpClient` / `Context` 的既有 provider，参考文件中已有 `get()` 链补齐；`OkHttpClient` 若无全局单例，`single { OkHttpClient.Builder().build() }` 提供一份。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（Task 9 的 `EmbeddingIndexWorker` 此时尚未创建会导致 `service.EmbeddingIndexWorker` 未解析——若报错，先按 Task 9 Step 3 建好空 worker 类再编译）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/embedding/SemanticIndexManager.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt
git commit -m "feat(embedding): add SemanticIndexManager orchestration"
```

---

### Task 9: EmbeddingIndexWorker（WorkManager）+ 排期

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/EmbeddingIndexWorker.kt`

**Interfaces:**
- Consumes: `SemanticIndexManager`（Koin worker factory 注入）、`Context`、`WorkerParameters`
- Produces: `class EmbeddingIndexWorker(...) : CoroutineWorker`；`companion object { fun enqueue(context: Context) }`

- [ ] **Step 1: 实现**

```kotlin
package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.embedding.SemanticIndexManager
import java.util.concurrent.TimeUnit

class EmbeddingIndexWorker(
    appContext: Context,
    params: WorkerParameters,
    private val semanticIndexManager: SemanticIndexManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            semanticIndexManager.indexPending()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "embedding-index"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmbeddingIndexWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（此时 Task 8 的 `EmbeddingIndexWorker.enqueue(context)` 引用可解析）

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/EmbeddingIndexWorker.kt
git commit -m "feat(embedding): add background embedding index worker"
```

---

### Task 10: ConversationRepository 接线（markPending / 删除 / searchMessagesHybrid）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`

**Interfaces:**
- Consumes: `SemanticIndexManager`、`rrfFuse`、`SemanticHit`、`MessageSearchResult`
- Produces:
  - `suspend fun searchMessagesHybrid(query: String, limit: Int = 15): List<MessageSearchResult>`
  - `insertConversation` / `updateConversation` 内部追加 `semanticIndexManager.markPending(conversation)`
  - `deleteConversation` 内部追加 `semanticIndexManager.deleteConversation(conversation.id)`
  - `rebuildAllIndexes` 扩展：先重建 FTS，再重建语义索引（`semanticIndexManager.deleteAll()` + 逐会话 `markPending` + 末尾 `indexPending(limit=Int.MAX_VALUE)`）

- [ ] **Step 1: 构造注入**

`ConversationRepository` 构造参数（`ConversationRepository.kt:35` 附近）新增：
```kotlin
    private val semanticIndexManager: SemanticIndexManager,
```
并在 DI（`repositoryModule` 或 `DataSourceModule` 中构造 `ConversationRepository` 处）传 `get()`。

- [ ] **Step 2: insert / update / delete 接线**

`insertConversation`（`:286`）与 `updateConversation`（`:296`）在 `messageFtsManager.indexConversation(conversation)` 之后追加：
```kotlin
        semanticIndexManager.markPending(conversation)
```
在 `deleteConversation` 的 FTS 清理处（找到 `messageFtsManager.deleteConversation(...)` 调用）追加：
```kotlin
        semanticIndexManager.deleteConversation(conversation.id)
```

- [ ] **Step 3: searchMessagesHybrid**

在 `ConversationRepository.kt` 的 `searchMessages`（`:325`）旁新增：
```kotlin
    suspend fun searchMessagesHybrid(query: String, limit: Int = 15): List<MessageSearchResult> {
        val fts = searchMessages(query, MessageSearchSort.RELEVANCE).take(limit)
        if (!semanticIndexManager.isConfigured()) return fts
        val semanticHits = semanticIndexManager.search(query, limit)
        if (semanticHits.isEmpty()) return fts

        // 为语义命中解析 title / updateAt（按 conversation 去重加载，避免 N+1）
        val metaByConv = HashMap<String, Pair<String, Instant>>()
        fun metaFor(conversationId: String): Pair<String, Instant> =
            metaByConv.getOrPut(conversationId) {
                conversationDAO.getConversationById(conversationId)
                    ?.let { it.title to it.updateAt }
                    ?: ("" to Instant.EPOCH)
            }

        val semanticResults = semanticHits.map { hit ->
            val (title, updateAt) = metaFor(hit.conversationId)
            MessageSearchResult(
                nodeId = hit.nodeId,
                messageId = hit.messageId,
                conversationId = hit.conversationId,
                title = title,
                updateAt = updateAt,
                snippet = hit.chunkText.take(120),
            )
        }
        return rrfFuse(fts, semanticResults, k = 60).take(limit)
    }
```
> 需确认 `conversationDAO.getConversationById` 返回类型字段名（`title` / `updateAt` 以 `ConversationEntity` 为准）；`Instant` import 来自 `java.time.Instant`。

- [ ] **Step 4: rebuildAllIndexes 扩展**

现有 `rebuildAllIndexes`（`:330`）内，在 FTS 重建循环后追加：
```kotlin
        // 语义索引重建：清空后全量 markPending 并立即嵌入
        semanticIndexManager.deleteAll()
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            semanticIndexManager.markPending(conversation)
            onProgress(index + 1, total)
        }
        semanticIndexManager.indexPending(limit = Int.MAX_VALUE)
```
> 注：`indexPending(limit=Int.MAX_VALUE)` 一次性处理完所有 pending；若数据量大分页未闭环，可将此步改为循环直至 `IndexCounts(0,0)`。以实际编译通过为准。

- [ ] **Step 5: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
git commit -m "feat(embedding): wire semantic index into conversation repository"
```

---

### Task 11: read_conversation 工具 + recent_chats 上限 50

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ConversationToolsBranchTest.kt`

**Interfaces:**
- Consumes: `conversationRepo.getConversationById(Uuid)`、`Conversation.currentMessages`、`MessageRole`、`MessageTextExtractor`
- Produces: 新增 `Tool("read_conversation", ...)`；`recent_chats` 上限 30→50

- [ ] **Step 1: 写失败测试（分支线性化 + 过滤 + 分页）**

`app/src/test/java/me/rerere/rikkahub/data/ai/tools/ConversationToolsBranchTest.kt`：
```kotlin
package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.embedding.MessageTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationToolsBranchTest {

    private fun msg(role: MessageRole, text: String) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `selected branch follows selectIndex and filters tool messages`() {
        val conv = Conversation(
            id = Uuid.random(), assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(messages = listOf(msg(MessageRole.USER, "hi")), selectIndex = 0),
                MessageNode(
                    messages = listOf(
                        msg(MessageRole.ASSISTANT, "answer A"),
                        msg(MessageRole.ASSISTANT, "answer B"),
                    ),
                    selectIndex = 1, // 用户选的是 B
                ),
                MessageNode(messages = listOf(msg(MessageRole.TOOL, "tool")), selectIndex = 0),
            ),
        )
        val visible = conv.currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .map { MessageTextExtractor.messageToSearchText(it) }
        assertEquals(listOf("hi", "answer B"), visible)
    }

    @Test
    fun `pagination slices the branch`() {
        val nodes = (0 until 5).map { i ->
            MessageNode(messages = listOf(msg(MessageRole.USER, "m$i")), selectIndex = 0)
        }
        val conv = Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = nodes)
        val messages = conv.currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        val page = messages.drop(1).take(2)
        assertEquals(listOf("m1", "m2"), page.map { MessageTextExtractor.messageToSearchText(it) })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.tools.ConversationToolsBranchTest"`
Expected: 编译失败（`Conversation`/`MessageNode` 构造参数以 `Conversation.kt` 实际为准——若 `assistantId` 必填则测试已带上；若还有必填字段需补）

- [ ] **Step 3: 在 ConversationTools 加 read_conversation**

`ConversationTools.kt`：在 `createConversationTools` 返回列表追加第三个 `Tool`（import 补 `MessageRole`、`Uuid`、`MessageTextExtractor`）：
```kotlin
    Tool(
        name = "read_conversation",
        description = """
            Read a specific conversation by ID, showing the currently selected message branch
            as a linear list with pagination. Use this after recent_chats or conversation_search
            to read a conversation of interest. Tool and system messages are excluded.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "The conversation ID to read (from recent_chats or conversation_search results).")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of messages to skip (default: 0).")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum messages to return (default: 50, max: 100).")
                    })
                },
                required = listOf("conversation_id"),
            )
        },
        execute = {
            val conversationId = it.jsonObject["conversation_id"]?.jsonPrimitive?.contentOrNull
                ?: error("conversation_id is required")
            val offset = it.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 100)

            val conversation = conversationRepo.getConversationById(Uuid.parse(conversationId))
                ?: error("conversation not found: $conversationId")
            val messages = runCatching { conversation.currentMessages }
                .getOrDefault(emptyList())
                .filter { m -> m.role == MessageRole.USER || m.role == MessageRole.ASSISTANT }

            val page = messages.drop(offset).take(limit)
            val payload = buildJsonObject {
                put("conversation_id", conversationId)
                put("title", conversation.title.ifBlank { "Untitled" })
                put("total_messages", messages.size)
                put("offset", offset)
                put("limit", limit)
                put("has_more", offset + limit < messages.size)
                putJsonArray("messages") {
                    page.forEach { m ->
                        add(buildJsonObject {
                            put("role", m.role.name.lowercase())
                            put("text", MessageTextExtractor.messageToSearchText(m))
                            put("timestamp", m.createdAt.toString())
                        })
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        },
    ),
```

- [ ] **Step 4: recent_chats 上限 30→50**

`ConversationTools.kt` 的 `recent_chats`：
- description `"default: 10, max: 30"` → `"default: 10, max: 50"`
- `(it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)` → `.coerceIn(1, 50)`

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.tools.ConversationToolsBranchTest"`
Expected: PASS

- [ ] **Step 6: 编译验证完整模块**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ConversationToolsBranchTest.kt
git commit -m "feat(tools): add read_conversation tool and raise recent_chats limit to 50"
```

---

### Task 12: 设置页（embedder 配置 + 重建语义索引）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingEmbedderPage.kt`
- Modify: 设置入口（找到设置页路由/列表，在"搜索"设置附近加 `SettingEmbedderPage` 入口）
- Test: 手动验证

**Interfaces:**
- Consumes: `SettingsStore.updateEmbedder`、`ConversationRepository.rebuildAllIndexes`
- Produces: 一个 Compose 设置页，含 enabled 开关、baseUrl / model / apiKey / batchSize 输入、"重建语义索引"按钮（带进度）

- [ ] **Step 1: 实现设置页**

`SettingEmbedderPage.kt` 用 `FormItem`（参照 `SettingSearchPage.kt` 的布局惯例）：
- `Switch` 绑定 `settings.embedder.enabled`
- `OutlinedTextField`：baseUrl / model / apiKey / batchSize
- 每次修改调用 `settingsStore.updateEmbedder { it.copy(...) }`
- "重建语义索引"按钮：调 `conversationRepo.rebuildAllIndexes { current, total -> progress = current to total }`，结束后 `LocalToaster` 提示完成
- 页面前缀字符串按 `setting_page_` 惯例（若项目要求本地化则加，否则先用字面量，遵循 CLAUDE.md「未明确要求本地化则先不考虑」）

- [ ] **Step 2: 加设置入口**

在设置页导航列表加一个 item 指向 `SettingEmbedderPage`（图标可用 `HugeIcons` 的搜索/数据库类图标；参照现有设置项的路由注册方式）。

- [ ] **Step 3: 手动验证清单**
- 打开设置 → 配置 DashScope key + model → 开启 enabled
- 发一条新消息 → 等 ~30s → 在对话里用工具触发 `conversation_search`，确认同义改写能命中
- 搜索页点"重建索引"→ 进度推进 → 完成后语义搜索正常
- 关闭 enabled → 搜索仍能出 FTS 结果（降级）
- 切换 model（维度变）→ 搜索降级 FTS 且无崩溃

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingEmbedderPage.kt
git commit -m "feat(settings): add embedder configuration and rebuild page"
```

---

## 自审记录

- **Spec 覆盖**：embedder 可配置（Task 1, 12）✓；RRF 融合（Task 6, 10）✓；read_conversation 只读选中分支（Task 11）✓；WorkManager 增量索引 markPending + worker（Task 8, 9, 10）✓；recent_chats 50（Task 11）✓；Room migration v25（Task 4）✓；错误降级（Task 8 `isConfigured`/空列表、Task 10 `if empty return fts`）✓；测试（纯函数单测各任务 + 手动验证 Task 12）✓。
- **占位符扫描**：无 TBD/TODO。Task 7 有一处签名修正说明（`planMarkPending` 携带 conversationId/nodeId）已内联写清。
- **类型一致性**：`SemanticIndexManager` / `EmbeddingIndexWorker` / `rrfFuse` / `scoreSemanticHits` / `SemanticHit` 在 Task 6-10 间签名一致；`MessageSearchResult`、`EmbeddingStatus`、`RAG_THRESHOLD` 交叉引用一致。
- **已知风险**：`UIMessage` 构造参数、`ConversationEntity.title/updateAt` 字段名、`conversationRepo` 在 `createConversationTools` 内可用性，均已在对应任务标注"以实际签名为准"并给出编译兜底路径。
