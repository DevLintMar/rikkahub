package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CodeSquare
import me.rerere.rikkahub.R

/**
 * 工具详情底部弹层（Agora SegmentDetailSheet 移植）：默认半屏(Half=0.5)，上拉近全屏(Full=0.94)。
 * 自包含拖拽状态机：Collapsed/Half/Full 驱动 Animatable fraction；内容区统一滚动 + 嵌套滚动
 * （Half 时拖动全屏拖动 Sheet，Full 时内容滚动，内容到顶下拉回 Half）。
 */
@Composable
internal fun ToolDetailSheet(
    title: String,
    onDismiss: () -> Unit,
    jsonBody: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // jsonBody 非 null → 标题栏右侧显示 CodeSquare 开关，翻转到整页 JSON 视图
    var showJson by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val PARTIAL = 0.5f
    val FULL = 0.94f

    val PHASE_COLLAPSED = 0; val PHASE_HALF = 1; val PHASE_FULL = 2
    var phase by remember { mutableIntStateOf(PHASE_HALF) }
    var rawFraction by remember { mutableFloatStateOf(0f) }
    val visualFraction = remember { Animatable(0f) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var dismissing by remember { mutableStateOf(false) }

    val snapSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 350f, visibilityThreshold = 0.001f)

    fun snapTarget(pos: Float, velSign: Float): Float {
        val goingUp = velSign >= 0f
        return when {
            pos > 0.5f && goingUp -> FULL
            pos > 0.5f && !goingUp -> PARTIAL
            pos <= 0.5f && goingUp -> PARTIAL
            else -> 0f
        }
    }

    fun animateTo(target: Float) {
        snapJob?.cancel()
        snapJob = coroutineScope.launch {
            visualFraction.animateTo(target, snapSpring)
            rawFraction = visualFraction.value
            phase = when (target) { FULL -> PHASE_FULL; PARTIAL -> PHASE_HALF; else -> PHASE_COLLAPSED }
            if (target == 0f) onDismiss()
        }
    }

    fun dismiss() { dismissing = true; animateTo(0f) }

    fun grabSheet() {
        if (dismissing) return
        if (snapJob?.isActive == true) { snapJob?.cancel(); rawFraction = visualFraction.value }
    }

    LaunchedEffect(Unit) { animateTo(PARTIAL); snapJob?.join(); rawFraction = PARTIAL }

    // 页面 JSON 开关切换时回到顶部，避免深滚夹在中间
    LaunchedEffect(showJson) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(rawFraction) {
        if (dismissing || snapJob?.isActive == true) return@LaunchedEffect
        val pos = rawFraction
        delay(80)
        if (dismissing || pos != rawFraction || snapJob?.isActive == true) return@LaunchedEffect
        val target = snapTarget(pos, 0f)
        if (abs(target - pos) > 0.01f) animateTo(target)
    }

    val dialogWindowRef = remember { mutableStateOf<android.view.Window?>(null) }
    LaunchedEffect(dialogWindowRef.value) {
        val window = dialogWindowRef.value ?: return@LaunchedEffect
        while (isActive) {
            window.attributes = window.attributes.also { it.dimAmount = (0.32f * visualFraction.value).coerceIn(0f, 1f) }
            withFrameNanos { }
        }
    }

    val sheetScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!dismissing && phase != PHASE_FULL) {
                    grabSheet()
                    val delta = -available.y / screenHeightPx
                    rawFraction = (rawFraction + delta).coerceIn(0f, FULL)
                    coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                    if (rawFraction >= FULL && available.y < 0f) phase = PHASE_FULL
                    return available.copy(x = 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (dismissing) return Offset.Zero
                if (phase == PHASE_FULL && available.y > 0f && scrollState.value == 0
                    && source == NestedScrollSource.UserInput
                ) {
                    phase = PHASE_HALF
                    val delta = -available.y / screenHeightPx
                    rawFraction = (FULL + delta).coerceIn(0f, FULL)
                    coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                    return available.copy(x = 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (phase != PHASE_FULL && available.y != 0f) {
                    val velSign = if (available.y < 0f) 1f else -1f
                    animateTo(snapTarget(rawFraction, velSign))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindowRef.value = dialogWindow }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { if (visualFraction.value > 0.02f) dismiss() })
                    }
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val h = (screenHeightPx * visualFraction.value).roundToInt().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(placeable.width, h) { placeable.placeRelative(0, 0) }
                    }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 拖拽把手 + 标题 + JSON 开关
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    var velEma = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            velEma = 0f; grabSheet()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (dismissing) return@detectVerticalDragGestures
                                            change.consume()
                                            velEma = velEma * 0.5f + (-dragAmount).coerceIn(-1f, 1f) * 0.5f
                                            rawFraction = (rawFraction - dragAmount / screenHeightPx).coerceIn(0f, FULL)
                                            coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                                            if (rawFraction >= FULL && dragAmount < 0f) phase = PHASE_FULL
                                        },
                                        onDragEnd = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            animateTo(snapTarget(rawFraction, velEma))
                                        },
                                    )
                                }
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier.width(36.dp).height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (jsonBody != null) {
                                    IconButton(onClick = { showJson = !showJson }) {
                                        Icon(
                                            imageVector = HugeIcons.CodeSquare,
                                            contentDescription = stringResource(
                                                if (showJson) R.string.tool_ui_view_style
                                                else R.string.tool_ui_view_json,
                                            ),
                                            modifier = Modifier.size(18.dp),
                                            tint = if (showJson) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }

                        // 内容区：统一滚动 + 嵌套滚动；showJson 时切到整页 JSON 视图
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(sheetScrollConnection)
                                .verticalScroll(scrollState)
                                .padding(horizontal = 24.dp)
                                .padding(top = 6.dp)
                                .navigationBarsPadding()
                                .padding(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (showJson && jsonBody != null) jsonBody()
                            else content()
                        }
                    }
                }
            }
        }
    }
}
