package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun Favicon(
    url: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(25),
) {
    val faviconUrl = remember(url) {
        url.toHttpUrlOrNull()?.host?.let { host ->
            "https://favicone.com/$host"
        }
    }
    AsyncImage(
        model = faviconUrl,
        modifier = modifier
            .size(20.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = rememberVectorPainter(HugeIcons.Link01),
        fallback = rememberVectorPainter(HugeIcons.Link01),
    )
}
