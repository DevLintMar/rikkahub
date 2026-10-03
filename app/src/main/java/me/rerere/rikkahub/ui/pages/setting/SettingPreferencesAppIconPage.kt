package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import java.io.File
import java.io.FileOutputStream

@Composable
fun SettingPreferencesAppIconPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val toaster = LocalToaster.current

    val customIconFile = remember { File(context.filesDir, "custom_app_icon.png") }
    var iconRevision by remember { mutableStateOf(System.currentTimeMillis()) }

    val (_, launchCrop) = useCropLauncher(
        aspectRatio = 1f to 1f,
        freeStyleCropEnabled = false,
        onCroppedImageReady = { croppedUri ->
            runCatching {
                context.contentResolver.openInputStream(croppedUri)?.use { input ->
                    FileOutputStream(customIconFile).use { output ->
                        input.copyTo(output)
                    }
                }
                iconRevision = System.currentTimeMillis()
                toaster.show(
                    context.getString(R.string.setting_provider_page_save_success),
                    type = ToastType.Success
                )
            }.onFailure { e ->
                e.printStackTrace()
                toaster.show(
                    e.message ?: "Failed to save cropped image",
                    type = ToastType.Error
                )
            }
        }
    )

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { launchCrop(it) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_app_icon)) },
                navigationIcon = { BackButton() },
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
            // 当前图标预览展示卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val hasCustom = customIconFile.exists() && customIconFile.length() > 0
                        AsyncImage(
                            model = if (hasCustom) "$customIconFile?rev=$iconRevision" else R.mipmap.ic_launcher,
                            contentDescription = stringResource(R.string.setting_page_preferences_app_icon),
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = if (hasCustom) "当前自定义图标" else "当前默认图标",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 操作选项卡片
            item {
                CardGroup {
                    item(
                        onClick = { imagePicker.launch("image/*") },
                        leadingContent = { Icon(HugeIcons.Image02, null) },
                        headlineContent = { Text(stringResource(R.string.setting_app_icon_select_image)) },
                        supportingContent = { Text("从相册选择图片并进行 1:1 正方形裁切") }
                    )

                    item(
                        onClick = {
                            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                                toaster.show(
                                    context.getString(R.string.setting_app_icon_shortcut_not_supported),
                                    type = ToastType.Warning
                                )
                                return@item
                            }

                            val bitmap = if (customIconFile.exists()) {
                                BitmapFactory.decodeFile(customIconFile.absolutePath)
                            } else {
                                BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                            }

                            if (bitmap == null) {
                                toaster.show("无法加载图标数据", type = ToastType.Error)
                                return@item
                            }

                            val launchIntent = Intent(context, RouteActivity::class.java).apply {
                                action = Intent.ACTION_MAIN
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }

                            val shortcutInfo = ShortcutInfoCompat.Builder(context, "rikkahub_launcher_shortcut")
                                .setShortLabel(context.getString(R.string.app_name))
                                .setLongLabel(context.getString(R.string.app_name))
                                .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
                                .setIntent(launchIntent)
                                .build()

                            val success = ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
                            if (success) {
                                toaster.show(
                                    context.getString(R.string.setting_app_icon_shortcut_success),
                                    type = ToastType.Success
                                )
                            } else {
                                toaster.show(
                                    context.getString(R.string.setting_app_icon_shortcut_not_supported),
                                    type = ToastType.Warning
                                )
                            }
                        },
                        leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                        headlineContent = { Text(stringResource(R.string.setting_app_icon_create_shortcut)) },
                        supportingContent = { Text("将当前图标固定为手机桌面快捷方式，点击秒开应用") }
                    )

                    if (customIconFile.exists()) {
                        item(
                            onClick = {
                                customIconFile.delete()
                                iconRevision = System.currentTimeMillis()
                                toaster.show("已恢复默认图标", type = ToastType.Success)
                            },
                            leadingContent = { Icon(HugeIcons.Delete02, null, tint = MaterialTheme.colorScheme.error) },
                            headlineContent = {
                                Text("清除自定义图标", color = MaterialTheme.colorScheme.error)
                            },
                            supportingContent = { Text("移除已裁切的自定义图标，恢复官方默认图标") }
                        )
                    }
                }
            }

            // 说明说明提示卡
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "💡 原理说明",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "受 Android 操作系统安全策略限制，已安装的应用在运行时无法直接篡改 APK 安装包自身的主桌面图标。通过此功能，您可以将任意专属图片裁切后固定为手机桌面快捷方式，使用体验与原生桌面图标完全一致。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
