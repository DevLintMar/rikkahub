package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.SearchRemove
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.setting.SearchAbilityTagLine
import org.koin.compose.koinInject

enum class SearchMode {
    OFF,
    LOCAL,
}

/**
 * 模型所在供应商是否具备「服务端搜索」能力。
 *
 * 判据只看供应商类型与协议，不看 modelId —— 旧的 `gpt-` / GEMINI_SERIES 启发式会把
 * 第三方网关（自建 modelId 跑 Responses）挡在外面，用户因此看不到内置搜索开关。
 *
 * 与 ChatToolFactory.shouldUseExternalWebSearch 分工不同：这里回答「能不能开内置搜索」，
 * 那里回答「内置搜索已经开着时还要不要挂外挂搜索」。
 */
internal fun ProviderSetting.supportsBuiltInServerSearch(): Boolean = when (this) {
    is ProviderSetting.Claude -> true
    is ProviderSetting.Google -> true
    // 只有走 Responses 协议时才有服务端 web_search；Chat Completions 没有
    is ProviderSetting.OpenAI -> useResponseApi
}

internal fun Settings.supportsBuiltInServerSearch(model: Model?): Boolean {
    if (model == null) return false
    // checkOverwrite = false：要的是真实供应商的「类型/协议」，不是覆盖项
    return model.findProvider(providers, checkOverwrite = false)?.supportsBuiltInServerSearch() == true
}

@Composable
fun SearchPickerButton(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (List<Uuid>) -> Unit,
    model: Model?,
) {
    var showSearchPicker by remember { mutableStateOf(false) }

    ToggleSurface(
        modifier = modifier,
        checked = enableSearch || model?.tools?.contains(BuiltInTools.Search) == true,
        onClick = {
            showSearchPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (enableSearch || model?.tools?.contains(BuiltInTools.Search) == true) {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.SearchRemove,
                        contentDescription = stringResource(R.string.use_web_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (showSearchPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSearchPicker = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.search_picker_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                SearchPicker(
                    enableSearch = enableSearch,
                    settings = settings,
                    onUpdateSearchMode = onUpdateSearchMode,
                    onUpdateSearchService = { ids ->
                        onUpdateSearchService(ids)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    model = model,
                    onDismiss = {
                        showSearchPicker = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchPicker(
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    modifier: Modifier = Modifier,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (List<Uuid>) -> Unit,
    onDismiss: () -> Unit
) {
    val navBackStack = LocalNavController.current

    // 模型能否开内置搜索：看供应商与协议，与「要不要挂外挂搜索」同源
    val supportsBuiltInSearch = settings.supportsBuiltInServerSearch(model)
    // 模型是否已开启内置搜索（可能是不支持的模型残留的孤儿状态）
    val hasBuiltInSearchEnabled = model?.tools?.contains(BuiltInTools.Search) == true

    // 模型支持内置搜索，或已开启内置搜索（后者保证残留状态也能被关闭）时显示开关
    if (model != null && (supportsBuiltInSearch || hasBuiltInSearchEnabled)) {
        BuiltInSearchSetting(model = model)
    }

    // 如果没有开启内置搜索，显示搜索服务选择
    if (!hasBuiltInSearchEnabled) {
        AppSearchSettings(
            enableSearch = enableSearch,
            onDismiss = onDismiss,
            navBackStack = navBackStack,
            onUpdateSearchMode = onUpdateSearchMode,
            modifier = modifier,
            settings = settings,
            onUpdateSearchService = onUpdateSearchService
        )
    }
}

@Composable
private fun AppSearchSettings(
    enableSearch: Boolean,
    onDismiss: () -> Unit,
    navBackStack: Navigator,
    onUpdateSearchMode: (SearchMode) -> Unit,
    modifier: Modifier,
    settings: Settings,
    onUpdateSearchService: (List<Uuid>) -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.GlobalSearch, null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.use_web_search),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (enableSearch) {
                        stringResource(R.string.web_search_enabled)
                    } else {
                        stringResource(R.string.web_search_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }
            IconButton(
                onClick = {
                    onDismiss()
                    navBackStack.navigate(Screen.SettingSearch)
                }
            ) {
                Icon(HugeIcons.Settings03, null)
            }
            Switch(
                checked = enableSearch,
                onCheckedChange = { checked ->
                    onUpdateSearchMode(if (checked) SearchMode.LOCAL else SearchMode.OFF)
                }
            )
        }
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = GridCells.Adaptive(150.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(settings.searchServices) { _, service ->
            val selected = service.id in settings.searchServiceSelectedIds
            val isLastSelected = selected && settings.searchServiceSelectedIds.size == 1
            val containerColor = animateColorAsState(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            val textColor = animateColorAsState(
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = containerColor.value,
                    contentColor = textColor.value,
                ),
                onClick = {
                    if (isLastSelected) return@Card  // 至少保留一个
                    val next = if (selected) {
                        settings.searchServiceSelectedIds - service.id
                    } else {
                        settings.searchServiceSelectedIds + service.id
                    }
                    onUpdateSearchService(next)
                },
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AutoAIIcon(
                        name = service.displayName,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = service.displayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        SearchAbilityTagLine(
                            options = service,
                            modifier = Modifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInSearchSetting(model: Model) {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    Card {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.GlobalSearch, null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.built_in_search_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.built_in_search_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }

            Switch(
                checked = model.tools.contains(BuiltInTools.Search),
                onCheckedChange = { checked ->
                    val settings = settingsStore.settingsFlow.value
                    scope.launch {
                        settingsStore.update(
                            settings.copy(
                                providers = settings.providers.map { providerSetting ->
                                    providerSetting.editModel(
                                        model.copy(
                                            tools = if (checked) model.tools + BuiltInTools.Search else model.tools - BuiltInTools.Search
                                        )
                                    )
                                }
                            )
                        )
                    }
                }
            )
        }
    }
}
