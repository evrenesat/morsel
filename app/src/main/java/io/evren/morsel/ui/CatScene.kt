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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.sin

/** What the cat is quietly conveying. Drives pose only; it never triggers work. */
enum class CatMood {
    /** Idle blinking; the resting pose. */
    IDLE,

    /** Selection changed; ears perk up briefly. */
    ATTENTIVE,

    /** Dispatching: a hopeful lean toward the feeder. */
    SENDING,

    /** Cloud accepted; calm, slightly upright wait. */
    WAITING,

    /** Proven success: happy, eating. */
    HAPPY,

    /** Unresolved: calm, one ear tilted; honest uncertainty. */
    UNSURE,
}

/**
 * Original Morsel cat, drawn entirely with Canvas. Decorative: excluded from
 * semantics and from animation when motion is disabled. Independent ears, tail
 * and eyes; idle blinking, selection attention, sending lean, waiting, happy
 * eating, calm uncertainty.
 */
@Composable
fun CatScene(
    mood: CatMood,
    fur: Color,
    furSoft: Color,
    accent: Color,
    onFur: Color,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMorselMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "cat")
    val blinkPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_700), RepeatMode.Restart),
        label = "blink",
    )
    val tailPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_600), RepeatMode.Restart),
        label = "tail",
    )
    val breathePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200), RepeatMode.Restart),
        label = "breathe",
    )
    Canvas(
        modifier = modifier.clearAndSetSemantics {},
    ) {
        val blink = if (motion && blinkPhase > 0.94f) 1f else 0f
        val tail = if (motion) sin(tailPhase * 2f * Math.PI.toFloat()) * 8f else 0f
        val breathe = if (motion) sin(breathePhase * 2f * Math.PI.toFloat()) * 1.5f else 0f
        val lean = when {
            !motion -> 0f
            mood == CatMood.SENDING -> 7f
            mood == CatMood.ATTENTIVE -> 2f
            else -> 0f
        }
        val headTilt = if (motion && mood == CatMood.UNSURE) 4f else 0f
        val earPerk = if (mood == CatMood.ATTENTIVE || mood == CatMood.WAITING) 3f else 0f
        val chewing = motion && mood == CatMood.HAPPY

        rotate(degrees = lean, pivot = center) {
            drawTail(tail, furSoft)
            drawBody(breathe, fur)
            rotate(degrees = headTilt, pivot = Offset(center.x, size.height * 0.34f)) {
                drawEars(earPerk, fur, accent)
                drawHead(fur)
                drawFace(blink, chewing, fur, onFur, accent)
            }
        }
    }
}

private fun DrawScope.drawTail(swing: Float, color: Color) {
    val base = Offset(size.width * 0.79f, size.height * 0.82f)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(base.x, base.y)
        cubicTo(
            base.x + size.width * 0.16f,
            base.y - size.height * 0.02f + swing,
            base.x + size.width * 0.2f,
            base.y - size.height * 0.22f + swing,
            base.x + size.width * 0.1f,
            base.y - size.height * 0.3f + swing * 0.6f,
        )
    }
    drawPath(
        path,
        color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = size.minDimension * 0.055f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        ),
    )
}

private fun DrawScope.drawBody(breathe: Float, color: Color) {
    val w = size.width * 0.52f
    val h = size.height * 0.5f + breathe * 2f
    drawOval(color, topLeft = Offset(center.x - w / 2f, size.height - h), size = Size(w, h))
}

private fun DrawScope.drawEars(perk: Float, fur: Color, inner: Color) {
    val headTop = size.height * 0.34f
    val left = Offset(size.width * 0.24f, headTop + 6f - perk)
    val right = Offset(size.width * 0.76f, headTop + 6f - perk)
    fun ear(tip: Offset, innerOffset: Offset) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(tip.x - size.width * 0.1f, tip.y + size.height * 0.14f)
            lineTo(tip.x, tip.y - size.height * 0.02f)
            lineTo(tip.x + size.width * 0.1f, tip.y + size.height * 0.14f)
            close()
        }
        drawPath(path, fur)
        val innerPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(tip.x - size.width * 0.05f + innerOffset.x, tip.y + size.height * 0.1f)
            lineTo(tip.x + innerOffset.x, tip.y + size.height * 0.02f)
            lineTo(tip.x + size.width * 0.05f + innerOffset.x, tip.y + size.height * 0.1f)
            close()
        }
        drawPath(innerPath, inner)
    }
    ear(left, Offset(-2f, 0f))
    ear(right, Offset(2f, 0f))
}

private fun DrawScope.drawHead(fur: Color) {
    val cx = center.x
    val cy = size.height * 0.42f
    val rx = size.width * 0.3f
    val ry = size.height * 0.24f
    drawOval(fur, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f))
    // fluffy cheeks
    drawOval(
        fur,
        topLeft = Offset(cx - rx * 1.06f, cy + ry * 0.2f),
        size = Size(rx * 0.8f, ry * 0.7f),
    )
    drawOval(
        fur,
        topLeft = Offset(cx + rx * 0.26f, cy + ry * 0.2f),
        size = Size(rx * 0.8f, ry * 0.7f),
    )
}

private fun DrawScope.drawFace(blink: Float, chewing: Boolean, fur: Color, onFur: Color, accent: Color) {
    val cx = center.x
    val eyeY = size.height * 0.4f
    val eyeRx = size.width * 0.035f
    val eyeRy = size.height * (0.05f - blink * 0.042f)
    drawOval(onFur, topLeft = Offset(cx - size.width * 0.11f - eyeRx, eyeY - eyeRy), size = Size(eyeRx * 2f, eyeRy * 2f))
    drawOval(onFur, topLeft = Offset(cx + size.width * 0.11f - eyeRx, eyeY - eyeRy), size = Size(eyeRx * 2f, eyeRy * 2f))

    // nose + mouth
    val noseY = size.height * 0.46f
    drawCircle(accent, radius = size.width * 0.018f, center = Offset(cx, noseY))
    val mouthOpen = if (chewing) size.width * 0.012f else 0f
    drawLine(
        onFur,
        start = Offset(cx, noseY + size.width * 0.018f),
        end = Offset(cx, noseY + size.width * 0.036f + mouthOpen),
        strokeWidth = size.width * 0.012f,
    )
    drawArc(
        onFur,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(cx - size.width * 0.045f, noseY + size.width * 0.03f + mouthOpen),
        size = Size(size.width * 0.045f, size.width * 0.028f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.01f),
    )
    drawArc(
        onFur,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(cx, noseY + size.width * 0.03f + mouthOpen),
        size = Size(size.width * 0.045f, size.width * 0.028f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.01f),
    )
    // whiskers
    val wiskY = noseY + size.width * 0.01f
    drawLine(onFur, Offset(cx - size.width * 0.16f, wiskY), Offset(cx - size.width * 0.28f, wiskY - 4f), size.width * 0.008f)
    drawLine(onFur, Offset(cx - size.width * 0.16f, wiskY + 6f), Offset(cx - size.width * 0.28f, wiskY + 12f), size.width * 0.008f)
    drawLine(onFur, Offset(cx + size.width * 0.16f, wiskY), Offset(cx + size.width * 0.28f, wiskY - 4f), size.width * 0.008f)
    drawLine(onFur, Offset(cx + size.width * 0.16f, wiskY + 6f), Offset(cx + size.width * 0.28f, wiskY + 12f), size.width * 0.008f)
}
