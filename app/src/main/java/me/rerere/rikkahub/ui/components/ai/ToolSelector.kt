package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

val ALL_BASE_TOOLS = listOf(
    "search_web",
    "scrape_web",
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
    "get_time_info",
    "clipboard_tool",
    "eval_javascript",
    "text_to_speech",
    "ask_user",
    "get_screen_time",
    "calendar_query",
    "calendar_create",
    "recent_chats",
    "conversation_search",
    "sub_agent",
    "run_workflow",
    "memory_tool",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSelector(
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.sub_agents_tool_selector_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                text = stringResource(R.string.sub_agents_tool_selector_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(ALL_BASE_TOOLS, key = { it }) { toolName ->
                    ListItem(
                        headlineContent = { Text(toolName, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            Switch(
                                checked = toolName in selected,
                                onCheckedChange = { checked ->
                                    val newSelected = if (checked) {
                                        selected + toolName
                                    } else {
                                        selected - toolName
                                    }
                                    onSelect(newSelected)
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
