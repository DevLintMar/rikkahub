package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agents.AgentDefinition
import me.rerere.rikkahub.ui.components.ai.ToolSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailPage(agentName: String = "") {
    val vm = koinViewModel<AgentDetailVM>()
    val agent by vm.agent.collectAsStateWithLifecycle()
    val mcpServers by vm.availableMcpServers.collectAsStateWithLifecycle()
    val skills by vm.availableSkills.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    LaunchedEffect(agentName) { vm.load(agentName) }

    // Form state
    var name by remember(agent) { mutableStateOf(agent?.name ?: "") }
    var description by remember(agent) { mutableStateOf(agent?.description ?: "") }
    var tools by remember(agent) { mutableStateOf(agent?.tools) }
    var disallowedTools by remember(agent) { mutableStateOf(agent?.disallowedTools ?: emptyList()) }
    var mcpServersSelected by remember(agent) { mutableStateOf(agent?.mcpServers ?: emptyList<String>()) }
    var skillsSelected by remember(agent) { mutableStateOf(agent?.skills ?: emptyList<String>()) }
    var effort by remember(agent) { mutableStateOf(agent?.effort) }
    var systemPrompt by remember(agent) { mutableStateOf(agent?.systemPrompt ?: "") }
    var inheritTools by remember(agent) { mutableStateOf(agent?.tools == null) }

    // Selector visibility
    var showToolSelector by remember { mutableStateOf(false) }
    var showDisallowedSelector by remember { mutableStateOf(false) }
    var showMcpSelector by remember { mutableStateOf(false) }
    var showSkillSelector by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    if (showToolSelector) {
        ToolSelector(
            selected = (tools ?: emptyList()).toSet(),
            onSelect = { tools = it.toList() },
            onDismissRequest = { showToolSelector = false },
        )
    }
    if (showDisallowedSelector) {
        ToolSelector(
            selected = disallowedTools.toSet(),
            onSelect = { disallowedTools = it.toList() },
            onDismissRequest = { showDisallowedSelector = false },
        )
    }

    // MCP server selector
    if (showMcpSelector) {
        SimpleMultiSelector(
            title = stringResource(R.string.sub_agents_detail_mcp_servers),
            items = mcpServers,
            selected = mcpServersSelected.toSet(),
            onSelect = { mcpServersSelected = it.toList() },
            onDismissRequest = { showMcpSelector = false },
        )
    }

    // Skills selector
    if (showSkillSelector) {
        SimpleMultiSelector(
            title = stringResource(R.string.sub_agents_detail_skills),
            items = skills,
            selected = skillsSelected.toSet(),
            onSelect = { skillsSelected = it.toList() },
            onDismissRequest = { showSkillSelector = false },
        )
    }

    val isEditing = agentName.isNotBlank()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(if (isEditing) agentName else stringResource(R.string.sub_agents_detail_new))
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.sub_agents_detail_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isEditing,
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.sub_agents_detail_description)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            // Tools (nullable)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sub_agents_detail_tools)) },
                    supportingContent = {
                        Text(
                            if (tools != null) "${tools!!.size} tool(s) selected"
                            else stringResource(R.string.sub_agents_detail_inherit),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = inheritTools,
                    onCheckedChange = { inherit ->
                        inheritTools = inherit
                        if (inherit) tools = null else tools = emptyList()
                    },
                )
            }
            if (!inheritTools) {
                Button(
                    onClick = { showToolSelector = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sub_agents_detail_select_tools))
                }
            }

            // DisallowedTools
            ListItem(
                headlineContent = { Text(stringResource(R.string.sub_agents_detail_disallowed_tools)) },
                supportingContent = { Text("${disallowedTools.size} tool(s)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { showDisallowedSelector = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sub_agents_detail_select_disallowed))
            }

            // MCP Servers
            ListItem(
                headlineContent = { Text(stringResource(R.string.sub_agents_detail_mcp_servers)) },
                supportingContent = { Text(mcpServersSelected.joinToString(", ").ifEmpty { stringResource(R.string.sub_agents_detail_inherit) }) },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showMcpSelector = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.sub_agents_detail_select_mcp))
                }
                if (mcpServersSelected.isNotEmpty()) {
                    TextButton(
                        onClick = { mcpServersSelected = emptyList() },
                    ) {
                        Text(stringResource(R.string.sub_agents_detail_clear))
                    }
                }
            }

            // Skills
            ListItem(
                headlineContent = { Text(stringResource(R.string.sub_agents_detail_skills)) },
                supportingContent = { Text(skillsSelected.joinToString(", ").ifEmpty { stringResource(R.string.sub_agents_detail_inherit) }) },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showSkillSelector = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.sub_agents_detail_select_skills))
                }
                if (skillsSelected.isNotEmpty()) {
                    TextButton(
                        onClick = { skillsSelected = emptyList() },
                    ) {
                        Text(stringResource(R.string.sub_agents_detail_clear))
                    }
                }
            }

            // Effort
            EffortSlider(
                effort = effort,
                onEffortChange = { effort = it },
            )

            // System prompt (body)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.sub_agents_detail_system_prompt)) },
                minLines = 8,
                maxLines = 24,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            // Save button
            val nameRequiredMsg = stringResource(R.string.sub_agents_detail_name_required)
            val savedMsg = stringResource(R.string.sub_agents_detail_saved)
            val saveFailedMsg = stringResource(R.string.sub_agents_detail_save_failed)
            Button(
                onClick = {
                    if (name.isBlank()) {
                        toaster.show(nameRequiredMsg, type = ToastType.Warning)
                        return@Button
                    }
                    isSaving = true
                    vm.save(
                        name = name,
                        description = description,
                        tools = if (inheritTools) null else tools,
                        disallowedTools = disallowedTools,
                        mcpServers = mcpServersSelected.ifEmpty { null },
                        skills = skillsSelected.ifEmpty { null },
                        effort = effort,
                        systemPrompt = systemPrompt,
                    ) { success, msg ->
                        isSaving = false
                        if (success) {
                            toaster.show(savedMsg, type = ToastType.Success)
                            navController.popBackStack()
                        } else {
                            toaster.show(saveFailedMsg + ": $msg", type = ToastType.Error)
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun EffortSlider(
    effort: String?,
    onEffortChange: (String?) -> Unit,
) {
    val options = listOf(null, "low", "medium", "high", "xhigh")
    val labels = mapOf(
        null to stringResource(R.string.sub_agents_detail_effort_inherit),
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "xhigh" to "X-High",
    )

    Column {
        Text(
            text = stringResource(R.string.sub_agents_detail_effort),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            options.forEach { value ->
                val selected = effort == value
                androidx.compose.material3.FilterChip(
                    selected = selected,
                    onClick = { onEffortChange(value) },
                    label = { Text(labels[value] ?: "", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleMultiSelector(
    title: String,
    items: List<String>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = androidx.compose.material3.rememberBottomSheetState(
            initialValue = androidx.compose.material3.SheetValue.Hidden,
            enabledValues = setOf(androidx.compose.material3.SheetValue.Hidden, androidx.compose.material3.SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items.size, key = { items[it] }) { index ->
                    val item = items[index]
                    ListItem(
                        headlineContent = { Text(item) },
                        trailingContent = {
                            Switch(
                                checked = item in selected,
                                onCheckedChange = { checked ->
                                    val newSelected = if (checked) selected + item else selected - item
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
