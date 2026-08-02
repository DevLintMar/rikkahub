package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Refresh01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingEmbedderPage(vm: SettingEmbedderViewModel = koinViewModel()) {
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val embedder = settings.embedder
    var baseUrl by remember(embedder.baseUrl) { mutableStateOf(embedder.baseUrl) }
    var model by remember(embedder.model) { mutableStateOf(embedder.model) }
    var apiKey by remember(embedder.apiKey) { mutableStateOf(embedder.apiKey) }
    var batchSize by remember(embedder.batchSize) { mutableStateOf(embedder.batchSize) }

    LaunchedEffect(vm.rebuildFinished) {
        vm.rebuildFinished?.let { message ->
            toaster.show(message)
            vm.consumeRebuildFinished()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("语义索引")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("embedder_config") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Embedder 配置",
                            style = MaterialTheme.typography.titleMedium
                        )

                        FormItem(
                            label = {
                                Text("启用语义搜索")
                            },
                            description = {
                                Text("开启后新消息会异步构建语义索引，搜索时与全文检索混合排序；关闭则仅使用全文检索。")
                            },
                            tail = {
                                Switch(
                                    checked = embedder.enabled,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            settingsStore.updateEmbedder { it.copy(enabled = checked) }
                                        }
                                    }
                                )
                            }
                        )

                        FormItem(
                            label = {
                                Text("Base URL")
                            }
                        ) {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { value ->
                                    baseUrl = value
                                    scope.launch {
                                        settingsStore.updateEmbedder { it.copy(baseUrl = value) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        FormItem(
                            label = {
                                Text("Model")
                            }
                        ) {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { value ->
                                    model = value
                                    scope.launch {
                                        settingsStore.updateEmbedder { it.copy(model = value) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        FormItem(
                            label = {
                                Text("API Key")
                            },
                            description = {
                                Text("兼容 OpenAI /embeddings 接口的 API Key，例如 DashScope。")
                            }
                        ) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { value ->
                                    apiKey = value
                                    scope.launch {
                                        settingsStore.updateEmbedder { it.copy(apiKey = value) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        FormItem(
                            label = {
                                Text("Batch Size")
                            },
                            description = {
                                Text("单次批量嵌入的请求条数。")
                            }
                        ) {
                            OutlinedNumberInput(
                                value = batchSize,
                                onValueChange = { value ->
                                    batchSize = value
                                    scope.launch {
                                        settingsStore.updateEmbedder { it.copy(batchSize = value) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        FormItem(
                            label = {
                                Text("相似度阈值")
                            },
                            description = {
                                Text("语义搜索仅保留余弦相似度 ≥ 该值的结果。值越高越精确但召回越少。")
                            }
                        ) {
                            Column {
                                Text(
                                    text = "≥ ${"%.2f".format(embedder.threshold)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = embedder.threshold,
                                    onValueChange = { value ->
                                        scope.launch {
                                            settingsStore.updateEmbedder { it.copy(threshold = value) }
                                        }
                                    },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        FormItem(
                            label = {
                                Text("测试连接")
                            },
                            description = {
                                Text("用当前填写的配置发一次探测嵌入，验证 baseUrl / model / API Key 是否可用。")
                            }
                        ) {
                            Button(
                                onClick = { vm.testConnection(baseUrl, model, apiKey) },
                                enabled = vm.testState != SettingEmbedderViewModel.TestState.TESTING,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (vm.testState == SettingEmbedderViewModel.TestState.TESTING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(
                                        imageVector = HugeIcons.Connect,
                                        contentDescription = null
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(if (vm.testState == SettingEmbedderViewModel.TestState.TESTING) "测试中..." else "测试连接")
                            }
                            vm.testResult?.let { result ->
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (result.startsWith("连接成功"))
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            item("rebuild_index") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "重建索引",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "清空并重建全文检索与语义索引，耗时取决于会话数量，重建期间建议不要退出页面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { vm.rebuild() },
                            enabled = !vm.isRebuilding,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (vm.isRebuilding) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Refresh01,
                                    contentDescription = null
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (vm.isRebuilding) "重建中..." else "重建语义索引")
                        }
                        if (vm.isRebuilding) {
                            val (current, total) = vm.progress
                            LinearProgressIndicator(
                                progress = {
                                    if (total > 0) current.toFloat() / total.toFloat() else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = if (total > 0) "进度：$current / $total" else "准备中...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
