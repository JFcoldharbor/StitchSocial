package com.stitchsocial.club.birthday

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.DismissX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * BirthdayPreviewPlayer.kt — watching the card.
 * iOS parity with Birthday/BirthdayPreviewPlayer.swift.
 *
 *  - BirthdayPreviewPlayer: full-screen loop with sound and a close button.
 *    "Preview" on a card that's already set.
 *  - BirthdayApproveView: the same loop with Approve / Change / Skip, then the
 *    success state. Shown after a render, and for "Review your card" when a
 *    rendered card was never approved.
 *
 * Both play through BirthdayLoopingVideo — a 10-second card doesn't need the
 * feed's player stack. The feed is paused first so two soundtracks never
 * overlap.
 *
 * Success wording comes from a fresh birthday_status after approving, so the
 * day it names is the server's. Only if that call fails does the screen fall
 * back to the status's own `weekday` — still the server's word, never the
 * phone's calendar.
 *
 * Layer 8: Views
 */

// MARK: - Watch only

@Composable
internal fun BirthdayPreviewPlayer(videoURL: String, onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { pauseFeedPlayback(context) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BirthdayLoopingVideo(videoURL, Modifier.fillMaxSize())
        DismissX(
            onClick = onClose,
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp)
        )
    }
}

// MARK: - Preview & approve

private sealed class ApproveStage {
    object Reviewing : ApproveStage()
    object Approving : ApproveStage()
    data class Done(val title: String, val subtitle: String?, val liveVideoID: String?) : ApproveStage()
}

/**
 * @param onChange back to the style grid.
 * @param onFinished close the whole birthday flow (after Done, Skip, or opening the card).
 */
@Composable
internal fun BirthdayApproveView(
    videoURL: String,
    notify: BirthdayNotify,
    onChange: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<ApproveStage>(ApproveStage.Reviewing) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmingSkip by remember { mutableStateOf(false) }
    var isSkipping by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { pauseFeedPlayback(context) }
    // Back is the X: out of the whole flow. Not mid-approve.
    BackHandler { if (stage != ApproveStage.Approving) onFinished() }

    fun approve() {
        if (stage != ApproveStage.Reviewing) return
        stage = ApproveStage.Approving
        val weekdayBefore = BirthdayCenter.status?.weekday
        scope.launch {
            stage = try {
                val result = BirthdayService.approve(notify)
                val fresh = BirthdayCenter.refreshAfterAction()
                view.birthdaySuccessHaptic()
                doneStage(result, fresh, weekdayBefore)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = BirthdayService.message(e)
                ApproveStage.Reviewing
            }
        }
    }

    fun skip() {
        isSkipping = true
        scope.launch {
            try {
                BirthdayService.dismiss(BirthdayService.DismissMode.YEAR)
                BirthdayCenter.refreshAfterAction()
                onFinished()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = BirthdayService.message(e)
            }
            isSkipping = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BirthdayLoopingVideo(videoURL, Modifier.fillMaxSize())

        // Legibility for the controls at the bottom.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
        )

        AnimatedContent(
            targetState = stage is ApproveStage.Done,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            label = "approveStage"
        ) { done ->
            val current = stage
            if (done && current is ApproveStage.Done) {
                DoneState(current, onFinished = onFinished)
            } else {
                ReviewControls(
                    approving = stage == ApproveStage.Approving,
                    isSkipping = isSkipping,
                    onClose = onFinished,
                    onApprove = { approve() },
                    onChange = onChange,
                    onSkip = { confirmingSkip = true }
                )
            }
        }
    }

    if (confirmingSkip) {
        AlertDialog(
            onDismissRequest = { confirmingSkip = false },
            title = { Text("Skip your card this year?") },
            text = { Text("Nothing will post, and we won't ask again until next year.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSkip = false
                    skip()
                }) { Text("Skip this year", color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSkip = false }) { Text("Keep my card") }
            }
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
        )
    }
}

private fun doneStage(result: BirthdayApproveResult, fresh: BirthdayStatus?, weekdayFallback: String?): ApproveStage.Done {
    if (result.postedNow) {
        return ApproveStage.Done("🎉 Your card is live!", fresh?.copy?.subline, fresh?.card?.videoID)
    }
    if (fresh != null && fresh.phase == BirthdayPhase.SET && fresh.copy.headline.isNotEmpty()) {
        return ApproveStage.Done("✓ ${fresh.copy.headline}", fresh.copy.subline, null)
    }
    val weekday = fresh?.weekday ?: weekdayFallback.orEmpty()
    return ApproveStage.Done(
        if (weekday.isEmpty()) "✓ Your card is scheduled" else "✓ Scheduled for $weekday at midnight",
        null,
        null
    )
}

@Composable
private fun ReviewControls(
    approving: Boolean,
    isSkipping: Boolean,
    onClose: () -> Unit,
    onApprove: () -> Unit,
    onChange: () -> Unit,
    onSkip: () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DismissX(onClick = onClose)
            Text(
                "Your card",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset.Zero, 8f)
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(32.dp))
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Nothing posts until you approve it.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            BirthdayPrimaryButton(title = "Approve", isBusy = approving, onClick = onApprove)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.alpha(if (isSkipping) 0.5f else 1f)
            ) {
                val enabled = !approving && !isSkipping
                BirthdaySecondaryButton(
                    title = "Change", icon = Icons.Default.Palette, enabled = enabled,
                    modifier = Modifier.weight(1f), onClick = onChange
                )
                BirthdaySecondaryButton(
                    title = "Skip", icon = Icons.Default.SkipNext, enabled = enabled,
                    modifier = Modifier.weight(1f), onClick = onSkip
                )
            }
        }
    }
}

@Composable
private fun DoneState(done: ApproveStage.Done, onFinished: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
        BirthdayConfetti(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                done.title,
                color = Color.White,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            if (!done.subtitle.isNullOrEmpty()) {
                Text(
                    done.subtitle,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.weight(1f))
            if (done.liveVideoID != null) {
                BirthdayPrimaryButton(title = "See your card") { BirthdayCenter.openThread(done.liveVideoID) }
                BirthdaySecondaryButton(title = "Done", onClick = onFinished)
            } else {
                BirthdayPrimaryButton(title = "Done", onClick = onFinished)
            }
        }
    }
}
