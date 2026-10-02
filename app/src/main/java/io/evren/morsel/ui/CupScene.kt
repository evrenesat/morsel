package io.evren.morsel.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.sin

/** Cup visual state. */
enum class CupState {
    /** Selected portions visible as kibble in/above the cup. */
    PORTIONS,

    /** Pouring animation after cloud acceptance. */
    POURING,

    /** Calm stillness when the outcome is unresolved. */
    STILL,
}

/**
 * Original Morsel cup with kibble. Decorative (semantics excluded); the count
 * itself is always announced by the accessible counter text, never by art.
 * Kibble pile grows deterministically with the selection; the pour animation
 * only runs when motion is enabled and only reflects factual acceptance.
 */
@Composable
fun CupScene(
    state: CupState,
    portions: Int,
    cup: Color,
    cupRim: Color,
    kibble: Color,
    kibbleDark: Color,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMorselMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "cup")
    val pourPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Restart),
        label = "pour",
    )
    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        drawCupBody(cup, cupRim)
        val pouring = motion && state == CupState.POURING && portions > 0
        if (portions > 0) {
            drawKibblePile(kibble, kibbleDark, portions, shake = if (pouring) pourPhase else 0f)
        }
        if (pouring) {
            drawStream(kibble, pourPhase, portions)
        }
    }
}

private fun DrawScope.drawCupBody(cup: Color, rim: Color) {
    val bodyWidth = size.width * 0.42f
    val bodyHeight = size.height * 0.5f
    val left = center.x - bodyWidth / 2f
    val top = size.height - bodyHeight
    // tapered cup body
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(left + bodyWidth * 0.06f, top)
        lineTo(left + bodyWidth * 0.16f, size.height - size.height * 0.04f)
        lineTo(left + bodyWidth * 0.84f, size.height - size.height * 0.04f)
        lineTo(left + bodyWidth * 0.94f, top)
        close()
    }
    drawPath(path, cup)
    drawRoundRect(
        rim,
        topLeft = Offset(left, top - size.height * 0.02f),
        size = Size(bodyWidth, size.height * 0.06f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.02f),
    )
    // handle
    drawCircle(
        color = cup,
        radius = bodyWidth * 0.18f,
        center = Offset(left + bodyWidth + bodyWidth * 0.1f, top + bodyHeight * 0.35f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.028f),
    )
}

private fun DrawScope.drawKibblePile(kibble: Color, kibbleDark: Color, portions: Int, shake: Float) {
    val pileWidth = size.width * 0.3f
    val pileBaseY = size.height * 0.62f
    val wobble = if (shake > 0f) sin(shake * 2f * Math.PI.toFloat()) * 2f else 0f
    val maxPieces = 12
    val pieces = (portions + 1).coerceAtMost(maxPieces)
    for (i in 0 until pieces) {
        val row = i / 4
        val col = i % 4
        val px = center.x - pileWidth / 2f + pileWidth * (0.12f + col * 0.25f) + wobble
        val py = pileBaseY - row * size.height * 0.045f
        drawKibblePiece(px, py, if (i % 3 == 0) kibbleDark else kibble)
    }
}

private fun DrawScope.drawKibblePiece(x: Float, y: Float, color: Color) {
    val r = size.width * 0.028f
    drawRoundRect(
        color,
        topLeft = Offset(x - r, y - r),
        size = Size(r * 2f, r * 1.7f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.6f),
    )
}

private fun DrawScope.drawStream(kibble: Color, phase: Float, portions: Int) {
    // A short burst of falling pieces during the pour window (1-1.5s feel).
    if (phase !in 0.1f..0.75f) return
    val pieces = portions.coerceIn(1, 4)
    for (i in 0 until pieces) {
        val progress = ((phase - 0.1f) / 0.65f + i * 0.08f).mod(1f)
        val x = center.x + size.width * 0.04f * (i - pieces / 2f)
        val y = size.height * 0.12f + progress * size.height * 0.4f
        drawKibblePiece(x, y, kibble)
    }
}

private fun Float.mod(other: Float): Float = ((this % other) + other) % other
