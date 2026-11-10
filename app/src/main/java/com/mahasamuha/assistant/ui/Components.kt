package com.mahasamuha.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Phone versions of the design system's components (Button, MonoLabel, ChatBubble, status dots).
// Labels are lowercase by convention; MonoLabel uppercases its own text.

/** Ink fill (paper on the dark variant). One per view. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.actionBackground, contentColor = colors.actionContent),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, style = AppType.ui)
    }
}

/** Hairline outline, full-strength text: an action that isn't the primary but should still read as a button. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, colors.text.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, style = AppType.ui)
    }
}

/** Text only, at 0.75 opacity: the secondary action next to a primary. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.text.copy(alpha = 0.75f)),
        // No side padding, so the label lines up with the text around it; the touch target keeps its minimum size.
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
    ) {
        Text(label, style = AppType.ui)
    }
}

/** Geist Mono uppercase metadata. Separate parts with " · ". */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.textMuted,
) {
    Text(text.uppercase(), modifier = modifier, style = AppType.eyebrow, color = color)
}

/** The user's words in indigo, or the assistant's on a faint tint. The 4dp corner is the tail. */
@Composable
fun ChatBubble(
    text: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val shape = if (fromUser) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    Box(
        modifier =
            modifier
                .background(if (fromUser) colors.bubbleUser else colors.bubbleAssistant, shape)
                .padding(horizontal = 14.dp, vertical = if (fromUser) 10.dp else 12.dp),
    ) {
        Text(text, style = AppType.body, color = if (fromUser) colors.onBubbleUser else colors.text)
    }
}

/** A sage dot when done; an empty ring when not. */
@Composable
fun StatusDot(
    done: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Box(
        modifier =
            modifier
                .size(8.dp)
                .then(if (done) Modifier.background(colors.status, CircleShape) else Modifier.border(1.dp, colors.textMuted, CircleShape)),
    )
}

/** A dot that fades in and out while something is in progress; steady when [active] is false. */
@Composable
fun PulseDot(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val pulse =
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            label = "pulse-alpha",
        )
    Box(
        modifier =
            modifier
                .size(8.dp)
                .alpha(if (active) pulse.value else 1f)
                .background(color, CircleShape),
    )
}

/** Draws a solid disc with a halo that swells with the input [level] (0..1), smoothed. */
@Composable
private fun LevelOrb(
    level: Float,
    size: Dp,
    core: Float,
    modifier: Modifier = Modifier,
) {
    val color = AppTheme.colors.status
    val smoothed by animateFloatAsState(level, tween(LEVEL_SMOOTHING_MS), label = "level")
    Canvas(modifier.size(size)) {
        val coreRadius = this.size.minDimension / 2 * core // the disc's share of the box; the halo grows into the rest
        val haloRadius = coreRadius + (this.size.minDimension / 2 - coreRadius) * smoothed
        drawCircle(color, haloRadius, alpha = HALO_ALPHA)
        drawCircle(color, coreRadius)
    }
}

/**
 * Shows the user they are being heard: a large centred disc with a halo that swells with the input [level] (0..1),
 * over a [label].
 */
@Composable
fun LevelIndicator(
    level: Float,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LevelOrb(level, size = 120.dp, core = 0.5f, modifier = Modifier.semantics { contentDescription = label })
        MonoLabel(label)
    }
}

/** The header's pulser while transcribing: a small disc whose halo follows the input [level], in place of [PulseDot]. */
@Composable
fun LevelDot(
    level: Float,
    modifier: Modifier = Modifier,
) {
    // Box matches the 8 dp PulseDot footprint; the halo overflows it, so the row doesn't shift.
    Box(modifier.size(8.dp), contentAlignment = Alignment.Center) {
        LevelOrb(level, size = 20.dp, core = 0.4f, modifier = Modifier.requiredSize(20.dp))
    }
}

/** An assistant bubble with three dots fading in turn: the assistant is working on a reply. */
@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier =
            modifier
                .background(colors.bubbleAssistant, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { i ->
            val alpha =
                transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse, StartOffset(i * PULSE_MS / 3)),
                    label = "typing-dot-$i",
                )
            Box(Modifier.size(6.dp).alpha(alpha.value).background(colors.textMuted, CircleShape))
        }
    }
}

// Quiet, slow fades, in keeping with the design system's motion (no spinners).
private const val PULSE_MS = 600
private const val LEVEL_SMOOTHING_MS = 90
private const val HALO_ALPHA = 0.3f

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = AppTheme.colors.divider)
}
