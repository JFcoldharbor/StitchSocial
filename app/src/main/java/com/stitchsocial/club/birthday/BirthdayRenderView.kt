package com.stitchsocial.club.birthday

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.DismissX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/*
 * BirthdayRenderView.kt — "Making your card…", then Preview & approve.
 * iOS parity with Birthday/BirthdayRenderView.swift.
 *
 * birthday_render takes 15–45 seconds, and there is no progress to report:
 * the server answers once, when the video exists. So the bar is a feel, not a
 * measurement — it eases toward ~90% over the expected time and never claims
 * to be done before the answer arrives. The confetti keeps the wait looking
 * like a party rather than a stall.
 *
 * An error shows the server's own message (HttpsError carries a human one)
 * with Try again, which re-sends the same request.
 *
 * Back does nothing while rendering: closing would orphan a render the person
 * is about to want.
 *
 * Layer 8: Views
 */

private sealed class RenderStage {
    object Rendering : RenderStage()
    data class Failed(val message: String) : RenderStage()
    data class Ready(val result: BirthdayRenderResult) : RenderStage()
}

/**
 * @param onChange back to the style grid.
 * @param onFinished close the whole birthday flow.
 */
@Composable
internal fun BirthdayRenderView(
    request: BirthdayRenderRequest,
    onChange: () -> Unit,
    onFinished: () -> Unit
) {
    val view = LocalView.current
    var stage by remember { mutableStateOf<RenderStage>(RenderStage.Rendering) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        if (stage != RenderStage.Rendering) return@LaunchedEffect
        stage = try {
            val result = BirthdayService.render(request)
            BirthdayCenter.refreshAfterAction()
            view.birthdaySuccessHaptic()
            RenderStage.Ready(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RenderStage.Failed(BirthdayService.message(e))
        }
    }

    BackHandler(enabled = stage !is RenderStage.Ready) {
        if (stage is RenderStage.Failed) onChange()
        // Rendering: swallowed.
    }

    AnimatedContent(
        targetState = stage is RenderStage.Ready,
        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) },
        label = "renderStage"
    ) { ready ->
        val current = stage
        if (ready && current is RenderStage.Ready) {
            BirthdayApproveView(
                videoURL = current.result.videoURL,
                notify = request.notify,
                onChange = onChange,
                onFinished = onFinished
            )
        } else {
            MakingScreen(
                failedMessage = (current as? RenderStage.Failed)?.message,
                startedAt = startedAt,
                onBack = onChange,
                onRetry = {
                    startedAt = System.currentTimeMillis()
                    stage = RenderStage.Rendering
                    attempt += 1
                }
            )
        }
    }
}

@Composable
private fun MakingScreen(
    failedMessage: String?,
    startedAt: Long,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0.11f, 0.05f, 0.12f), Color.Black)))
    ) {
        BirthdayConfetti(Modifier.fillMaxSize(), count = 60)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (failedMessage != null) DismissX(onClick = onBack)
            }
            Spacer(Modifier.weight(1f))

            Text("🎂", fontSize = 72.sp, modifier = Modifier.padding(bottom = 18.dp))

            if (failedMessage != null) {
                Failed(failedMessage, onRetry = onRetry, onChange = onBack)
            } else {
                Making(startedAt)
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Making(startedAt: Long) {
    var elapsed by remember { mutableStateOf(0.0) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
            delay(250)
        }
    }
    val caption = caption(elapsed)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Making your card. This takes up to a minute."
        }
    ) {
        Text(
            buildAnnotatedString {
                append("Making ")
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Normal,
                        fontSize = 38.sp,
                        brush = BirthdayPalette.accentGradient
                    )
                ) { append("your card…") }
            },
            color = Color.White,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.8).sp,
            textAlign = TextAlign.Center
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Crossfade(targetState = caption, animationSpec = tween(300), label = "caption") { text ->
                Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            }
            ProgressBar(feelProgress(elapsed).toFloat())
        }

        Text(
            "This takes up to a minute. Keep this screen open.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, onChange: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Your card didn't come out",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            message,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        BirthdayPrimaryButton(title = "Try again", modifier = Modifier.padding(top = 8.dp), onClick = onRetry)
        Text(
            "Choose a different style",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onChange)
                .padding(8.dp)
        )
    }
}

@Composable
private fun ProgressBar(value: Float) {
    val animated by animateFloatAsState(value, tween(250), label = "progress")
    Box(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .widthIn(min = 12.dp)
                .clip(RoundedCornerShape(50))
                .background(BirthdayPalette.accentGradient)
        )
    }
}

/**
 * Eases toward 90% over ~45s and creeps after that. Never reaches 100 on its
 * own — only the answer does.
 */
private fun feelProgress(elapsed: Double): Double {
    val base = 0.9 * (1 - exp(-elapsed / 18))
    val creep = if (elapsed > 45) min(0.07, (elapsed - 45) / 600) else 0.0
    return min(0.97, max(0.0, 0.04 + base + creep))
}

/** What's happening, roughly. None of it is a promise about time. */
private fun caption(elapsed: Double): String = when {
    elapsed < 6 -> "Picking your best moments"
    elapsed < 14 -> "Framing your photo"
    elapsed < 24 -> "Adding the confetti"
    elapsed < 34 -> "Mixing the soundtrack"
    elapsed < 46 -> "Wrapping it up"
    else -> "Almost there"
}
