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
import coil3.request.ImageRequest
import coil3.request.allowHardware

import coil3.size.Precision
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.files.FilesManager
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
                    val request = remember(images[index]) {
                        ImageRequest.Builder(context)
                            .data(images[index])
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
