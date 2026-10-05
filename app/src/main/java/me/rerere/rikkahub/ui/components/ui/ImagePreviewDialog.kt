package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.core.net.toUri
import coil3.request.ImageRequest
import coil3.request.allowHardware

import coil3.size.Precision
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.image.viewer.AnyComposable
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.richtext.LocalWorkspaceFileProvider
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val state = rememberZoomablePagerState { images.size }
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    val rawUrl = images[index]
                    val workspaceResolver = LocalWorkspaceFileProvider.current
                    val realUrl = remember(rawUrl) {
                        if (workspaceResolver != null) {
                            workspaceResolver(rawUrl)?.takeIf { it.isFile }?.toUri()?.toString() ?: rawUrl
                        } else {
                            rawUrl
                        }
                    }
                    val request = remember(realUrl) {
                        ImageRequest.Builder(context)
                            .data(realUrl)
                            .size(coil3.size.Size.ORIGINAL)
                            .precision(coil3.size.Precision.EXACT)
                            .allowHardware(false)
                            .build()
                    }
                    val painter = rememberAsyncImagePainter(request)
                    val size = painter.intrinsicSize
                    val safeSize = if (size.isSpecified && size.width > 0f && size.height > 0f) {
                        size
                    } else {
                        Size(1024f, 1024f)
                    }
                    return@ImagePager Pair(painter, safeSize)
                },
                proceedPresentation = { model, size, processor, imageLoading ->
                    if (model != null && size != null) {
                        ZoomablePolicy(intrinsicSize = size) { zoomState ->
                            // 针对竖长图自适应满宽展示与顶部对齐，消灭 image-viewer 默认高度适配导致的牙签细条缩略缺陷
                            LaunchedEffect(zoomState, size, zoomState.containerSize.value) {
                                val container = zoomState.containerSize.value
                                if (container.width > 0f && container.height > 0f && size.width > 0f && size.height > 0f) {
                                    val containerRatio = container.width / container.height
                                    val contentRatio = size.width / size.height
                                    if (contentRatio < containerRatio) {
                                        // 竖长图（高宽比超过屏幕高宽比）：将初始显示宽度缩放至满宽（Fit Width）
                                        val fitWidthScale = containerRatio / contentRatio
                                        zoomState.scale.snapTo(fitWidthScale)
                                        // 初始顶部对齐：让用户从长图最顶端开始阅读
                                        val realHeight = container.height * fitWidthScale
                                        val topOffsetY = (realHeight - container.height) / 2f
                                        zoomState.offsetY.snapTo(topOffsetY)
                                        zoomState.offsetX.snapTo(0f)
                                    }
                                }
                            }
                            processor.Deploy(model = model, state = zoomState)
                        }
                        size.isSpecified
                    } else if (model != null && model is AnyComposable && size == null) {
                        model.composable.invoke()
                        true
                    } else {
                        imageLoading?.invoke()
                        false
                    }
                }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        lifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                toaster.show("正在保存")
                                val imgUrl = images[state.currentPage]
                                filesManager.saveMessageImage(context, imgUrl)
                                toaster.show(message = "已保存图片", type = ToastType.Success)
                            }.onFailure {
                                it.printStackTrace()
                                toaster.show(
                                    message = it.toString(),
                                    type = ToastType.Error
                                )
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Download01, null, tint = Color.White)
                }
            }
        }
    }
}
