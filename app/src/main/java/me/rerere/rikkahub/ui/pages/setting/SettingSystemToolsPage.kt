package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pulse01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.KeepAliveService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSystemToolsPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var keepAliveEnabled by remember(settings) {
        mutableStateOf(settings.keepAliveEnabled)
    }
    LaunchedEffect(settings) {
        keepAliveEnabled = settings.keepAliveEnabled
    }

    val notificationPermissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionNotification)
        } else emptySet()
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_system_tools)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_system_tools_keep_alive_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Pulse01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_system_tools_keep_alive)) },
                        supportingContent = { Text(stringResource(R.string.setting_system_tools_keep_alive_desc)) },
                        trailingContent = {
                            Switch(
                                checked = keepAliveEnabled,
                                onCheckedChange = { enabled ->
                                    keepAliveEnabled = enabled
                                    vm.updateSettings(settings.copy(keepAliveEnabled = enabled))
                                    if (enabled) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                            && !notificationPermissionState.allPermissionsGranted
                                        ) {
                                            notificationPermissionState.requestPermissions()
                                        } else {
                                            KeepAliveService.start(context)
                                        }
                                    } else {
                                        KeepAliveService.stop(context)
                                    }
                                }
                            )
                        }
                    )
                    if (keepAliveEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && !notificationPermissionState.allPermissionsGranted
                    ) {
                        item(
                            headlineContent = { Text("⚠ " + stringResource(R.string.setting_system_tools_permission_required)) },
                            supportingContent = { Text(stringResource(R.string.setting_system_tools_notification_permission_desc)) },
                        )
                    }
                }
            }
        }
    }
}
