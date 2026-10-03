package io.anysound.vr

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HudActivity { IDLE, RECORDING, WORKING }
data class HudContent(val title: String, val text: String, val footer: String = "", val level: Int = 0,
    val visible: Boolean = false, val error: Boolean = false, val activity: HudActivity = HudActivity.IDLE)

/** Shared by the desktop preview and the offscreen VR composition. */
@Composable
fun OverlayPanel(content: HudContent, onVisibilityChanged: (Boolean) -> Unit = {}) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = content.visible
    var lastVisible by remember { mutableStateOf(content) }
    // Keep the outgoing text until the exit completes, even if the hidden target clears it.
    val display = if (content.visible) content else lastVisible
    val showing = visibility.currentState || visibility.targetState || !visibility.isIdle
    SideEffect {
        if (content.visible) lastVisible = content
        onVisibilityChanged(showing)
    }
    AnimatedVisibility(visibility, modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(500)) + slideInVertically(tween(500, easing = FastOutSlowInEasing)) { it / 16 } + scaleIn(tween(500), initialScale = 0.98f),
        exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { it / 24 } + scaleOut(tween(400), targetScale = 0.99f),
    ) { OverlayBody(display) }
}

@Composable
private fun OverlayBody(content: HudContent) {
    val accent by animateColorAsState(if (content.error) Color(0xFFFF9292) else Color(0xFF7DD7DB), tween(180), label = "statusColor")
    val level by animateFloatAsState(content.level.coerceIn(0, 100) / 100f, tween(120), label = "audioLevel")
    Column(Modifier.fillMaxSize().background(Color(0xF010151F), RoundedCornerShape(16.dp)).padding(32.dp, 22.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                when (content.activity) {
                    HudActivity.WORKING -> CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
                    HudActivity.RECORDING -> {
                        val pulse = rememberInfiniteTransition(label = "recording")
                        val opacity by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recordingPulse")
                        Box(Modifier.size(10.dp).alpha(opacity).background(accent, CircleShape))
                    }
                    HudActivity.IDLE -> Box(Modifier.size(8.dp).background(accent, CircleShape))
                }
            }
            AnimatedContent(content.title, modifier = Modifier.weight(1f), label = "statusTitle",
                transitionSpec = { (fadeIn(tween(160)) togetherWith fadeOut(tween(100))).using(null) },
            ) { title ->
                Text(title, color = accent, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(content, modifier = Modifier.weight(1f).fillMaxWidth(), label = "subtitle",
            // Streaming revisions share a key and update immediately; final text crossfades.
            contentKey = { if (it.activity == HudActivity.RECORDING) HudActivity.RECORDING else it.text },
            transitionSpec = { (fadeIn(tween(180)) togetherWith fadeOut(tween(100))).using(null) },
        ) { value ->
            Text(value.text.ifBlank { "准备好了，按住绑定键开始说话" },
                color = Color(0xFFF0F3F8), fontSize = 34.sp, lineHeight = 42.sp,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFF323F4E), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).fillMaxHeight()
                .background(Color(0xFF69D2D5), RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(12.dp))
        Text(content.footer, color = Color(0xFFA3B1C2), fontSize = 20.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
