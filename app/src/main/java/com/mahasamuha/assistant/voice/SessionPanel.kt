package com.mahasamuha.assistant.voice

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.ui.AppTheme
import com.mahasamuha.assistant.ui.AppType
import com.mahasamuha.assistant.ui.ChatBubble
import com.mahasamuha.assistant.ui.GhostButton
import com.mahasamuha.assistant.ui.Hairline
import com.mahasamuha.assistant.ui.LevelDot
import com.mahasamuha.assistant.ui.LevelIndicator
import com.mahasamuha.assistant.ui.Mark
import com.mahasamuha.assistant.ui.MonoLabel
import com.mahasamuha.assistant.ui.PrimaryButton
import com.mahasamuha.assistant.ui.PulseDot
import com.mahasamuha.assistant.ui.TypingBubble
import com.mahasamuha.assistant.voice.PanelState.Phase
import kotlinx.coroutines.delay

/** Bottom sheet-style panel in the design system's ChatPanel style; tapping outside it closes the session. */
@Composable
fun SessionPanel(
    state: PanelState,
    onListen: () -> Unit,
    onAnswer: (confirmed: Boolean) -> Unit,
    onOpenSource: (url: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    // The warm-up indicator waits a moment, so a warm start (engine up almost at once) never flashes it.
    var warmupDelayPassed by remember(state.listenId) { mutableStateOf(false) }
    LaunchedEffect(state.listenId) {
        delay(WARMUP_INDICATOR_DELAY_MS)
        warmupDelayPassed = true
    }
    val stage = listenStage(state, warmupDelayPassed)
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Starts as small as its content and grows upward with the conversation, up to two thirds of the screen.
        val maxPanelHeight = maxHeight * 2 / 3
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .animateContentSize()
                    // Swallow taps on the panel itself so they don't close it.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
            color = colors.panel,
            border = colors.panelEdge?.let { BorderStroke(1.dp, it) },
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Pulses while the assistant is busy or listening, so a wait never looks like nothing is happening.
                    // While words are arriving the pulser follows the input level instead.
                    if (stage == ListenStage.Transcribing || stage == ListenStage.Hearing) {
                        LevelDot(level = state.level)
                    } else {
                        PulseDot(
                            active = state.phase != Phase.Idle,
                            color = if (state.phase == Phase.Idle) colors.textMuted else colors.status,
                        )
                    }
                    MonoLabel("samu · ${statusText(state, stage)}", modifier = Modifier.weight(1f))
                    Mark(size = 32.dp)
                }
                // Takes the space left under the cap; scrolls once the conversation outgrows it.
                Transcript(state, onOpenSource, Modifier.weight(1f, fill = false))
                // Big and centred only while the speech engine is still warming up; then the header takes over.
                if (stage == ListenStage.Warmup) {
                    LevelIndicator(level = state.level, label = "listening")
                }
                state.error?.let { error ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoLabel("couldn't finish")
                        ChatBubble(error, fromUser = false)
                    }
                }
                state.confirming?.let { ConfirmationCard(it, onAnswer) }

                Hairline()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.confirming == null && (state.phase == Phase.Idle || state.phase == Phase.Speaking)) {
                        PrimaryButton("ask", onClick = onListen)
                    }
                    Spacer(Modifier.weight(1f))
                    GhostButton("close", onClick = onClose)
                }
            }
        }
    }
}

/** The conversation so far as bubbles, kept scrolled to the newest; words still being heard show faded. */
@Composable
private fun Transcript(
    state: PanelState,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // The bubble whose source list is open, by transcript index.
    var openSources by remember { mutableStateOf<Int?>(null) }
    // Waiting on the model or a tool; the phase moves on as soon as a reply starts streaming in.
    val working = state.phase == Phase.Thinking
    val itemCount = state.transcript.size + (if (state.hearing.isNotEmpty()) 1 else 0) + (if (working) 1 else 0)
    // Follow new bubbles and the one that is still streaming.
    LaunchedEffect(itemCount, state.transcript.lastOrNull()?.text, state.hearing, working) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(state.transcript) { index, message ->
            when {
                message.note -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MonoLabel(message.text) }
                }

                message.sources.isEmpty() -> {
                    Bubble(message.text, message.fromUser)
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Bubble(message.text, message.fromUser, sources = message.sources.size, onSources = {
                            openSources = if (openSources == index) null else index
                        })
                        if (openSources == index) SourceList(message.sources, onOpenSource)
                    }
                }
            }
        }
        if (state.hearing.isNotEmpty()) {
            item { Bubble(state.hearing, fromUser = true, modifier = Modifier.alpha(0.6f)) }
        }
        if (working) {
            item { TypingBubble() }
        }
    }
}

/** User bubbles sit on the right at up to 78% of the width; the assistant's on the left at up to 92%. */
@Composable
private fun Bubble(
    text: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
    sources: Int = 0,
    onSources: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.fillMaxWidth(if (fromUser) 0.78f else 0.92f),
            contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            if (sources == 0) {
                ChatBubble(text, fromUser = fromUser)
            } else {
                // The sources pill hangs off the bubble's tail corner, the way reactions sit on a chat bubble.
                Box {
                    ChatBubble(text, fromUser = fromUser, modifier = Modifier.padding(bottom = 12.dp))
                    SourcesPill(
                        sources,
                        onSources,
                        Modifier.align(if (fromUser) Alignment.BottomEnd else Alignment.BottomStart).padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

/** "↗ 9": the number of pages behind a search answer; tap to list them. */
@Composable
private fun SourcesPill(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Box(
        modifier =
            modifier
                .shadow(2.dp, RoundedCornerShape(999.dp))
                .background(colors.panel, RoundedCornerShape(999.dp))
                .border(1.dp, colors.divider, RoundedCornerShape(999.dp))
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClickLabel = "show sources", onClick = onClick)
                .semantics { contentDescription = "$count sources" }
                .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        MonoLabel("↗ $count")
    }
}

/** A reply's sources in an inset well under the bubble; tapping one opens it in the browser. */
@Composable
private fun SourceList(
    sources: List<SourceLink>,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth(0.92f)
                .background(AppTheme.colors.inset, RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp),
    ) {
        MonoLabel("sources", Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        sources.forEach { source ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(source.url) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(source.title, style = AppType.caption, color = AppTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MonoLabel(
                    Uri
                        .parse(source.url)
                        .host
                        .orEmpty()
                        .removePrefix("www."),
                )
            }
        }
    }
}

/** The action waiting on the user, in an inset well: confirm or cancel for an ask, cancel for an announcement. */
@Composable
private fun ConfirmationCard(
    confirmation: Confirmation,
    onAnswer: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.inset, RoundedCornerShape(10.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MonoLabel(if (confirmation is Confirmation.Ask) "confirm" else "about to")
        Text(confirmation.text, style = AppType.bodyLarge, color = AppTheme.colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (confirmation is Confirmation.Ask) PrimaryButton("confirm →", onClick = { onAnswer(true) })
            GhostButton("cancel", onClick = { onAnswer(false) })
        }
    }
}

private fun statusText(
    state: PanelState,
    stage: ListenStage?,
): String =
    when {
        stage == ListenStage.Transcribing -> {
            "transcribing"
        }

        stage == ListenStage.Hearing && state.confirming == null -> {
            "listening"
        }

        stage != null && state.confirming is Confirmation.Ask -> {
            "say yes or no"
        }

        else -> {
            when (state.phase) {
                Phase.Starting -> "…"
                Phase.Listening -> "listening…"
                Phase.Thinking -> "thinking…"
                Phase.Speaking -> "speaking"
                Phase.Idle -> "ready"
            }
        }
    }

/** How long a listen must have run, with the engine still down, before the centre indicator appears. */
internal const val WARMUP_INDICATOR_DELAY_MS = 300L

/** Where a listen is, from the panel's point of view. */
internal enum class ListenStage {
    /** Engine not up, and the warm-up delay has not passed: nothing extra is shown yet. */
    Waiting,

    /** Engine not up for longer than the delay: the big centre indicator shows. */
    Warmup,

    /** Engine up, no words yet. */
    Hearing,

    /** Words are arriving. */
    Transcribing,
}

/** The listen stage, or null when not listening. [delayPassed]: [WARMUP_INDICATOR_DELAY_MS] since the listen began. */
internal fun listenStage(
    state: PanelState,
    delayPassed: Boolean,
): ListenStage? =
    when {
        state.phase != Phase.Listening -> null
        state.hearing.isNotEmpty() -> ListenStage.Transcribing
        state.engineUp -> ListenStage.Hearing
        delayPassed -> ListenStage.Warmup
        else -> ListenStage.Waiting
    }
