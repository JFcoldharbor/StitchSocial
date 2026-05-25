package com.stitchsocial.club.live

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin Agora SDK wrapper for the live-stream feature on Android. Mirrors the
 * iOS `AgoraStreamService`.
 *
 * Roles:
 *  - Viewer joins as `BROADCASTER`? No — as `AUDIENCE` so they don't publish
 *    audio/video into the channel.
 *  - Creator (Phase 3) joins as `BROADCASTER` and publishes their camera.
 *
 * Lifecycle:
 *  - [initEngine] once per process. Idempotent.
 *  - [joinAsViewer] when entering [LiveStreamViewerScreen].
 *  - [leaveChannel] on exit.
 *  - [destroy] only if you want to fully tear down (e.g., app logout).
 *
 * Renders the creator's remote video into a SurfaceView the caller passes in
 * — see [setupRemoteVideo].
 */
class AgoraStreamService private constructor() {

    companion object {
        @Volatile private var INSTANCE: AgoraStreamService? = null
        fun getInstance(): AgoraStreamService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgoraStreamService().also { INSTANCE = it }
            }

        private const val TAG = "AgoraStream"

        // Agora project App ID — same as the iOS app's `AgoraConfig.appID`.
        // Agora projects are platform-agnostic, so iOS + Android share one ID.
        // If you rotate the key, update both here AND iOS AgoraConfig.swift.
        // TODO: move to BuildConfig sourced from local.properties so the key
        // isn't committed in plaintext.
        private const val APP_ID = "a9f983979e42435f8d2000f06382ffc6"
    }

    private var engine: RtcEngine? = null
    private var currentChannel: String? = null

    // ── Published state ─────────────────────────────────────────────────────

    private val _remoteUserJoined = MutableStateFlow(false)
    val remoteUserJoined: StateFlow<Boolean> = _remoteUserJoined.asStateFlow()

    private val _remoteUid = MutableStateFlow(0)
    val remoteUid: StateFlow<Int> = _remoteUid.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Engine init — call once before any join.
    // ─────────────────────────────────────────────────────────────────────────

    /// Engine init — guarded so a misconfigured App ID logs an error instead
    /// of crashing the host process. Callers check `isReady` (or just call
    /// join methods which no-op when the engine is null).
    fun initEngine(context: Context) {
        if (engine != null) return

        // Refuse to init with the placeholder — Agora's RtcEngine.create
        // throws / fatals when given anything that isn't a real App ID, and
        // the crash propagates up through Compose.
        if (APP_ID == "REPLACE_WITH_AGORA_APP_ID" || APP_ID.isBlank()) {
            Log.e(TAG, "❌ Agora APP_ID not configured — set it in AgoraStreamService.kt before going live")
            return
        }

        runCatching {
            val config = RtcEngineConfig().apply {
                mContext = context.applicationContext
                mAppId = APP_ID
                mEventHandler = handler
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            }
            engine = RtcEngine.create(config)
            engine?.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoEncoderConfiguration.VideoDimensions(540, 960),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30,
                    1500,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT,
                )
            )
            Log.d(TAG, "✅ engine initialized (540×960@30fps, 1500kbps)")
        }.onFailure { err ->
            Log.e(TAG, "❌ engine init failed: ${err.localizedMessage}")
            engine = null
        }
    }

    /// True once `initEngine` succeeded. UI uses this to render a friendly
    /// "Stream unavailable" state instead of an empty black screen.
    fun isReady(): Boolean = engine != null

    // ─────────────────────────────────────────────────────────────────────────
    // Viewer join — joins the channel as AUDIENCE (no publishing). Returns
    // immediately; the actual join completes asynchronously and the event
    // handler logs success.
    // ─────────────────────────────────────────────────────────────────────────

    fun joinAsViewer(channelName: String, uid: Int = 0, token: String? = null) {
        val rtc = engine ?: run {
            Log.w(TAG, "joinAsViewer called before initEngine")
            return
        }

        // Idempotent — if we're already in this channel, no-op.
        if (currentChannel == channelName) return
        if (currentChannel != null) rtc.leaveChannel()

        // Strict audience: receive remote A/V, never capture local. Without
        // disabling local capture, Agora probes the camera + mic on join,
        // which can fail on devices that haven't granted those permissions
        // (the viewer doesn't need them but the engine doesn't know that).
        rtc.setClientRole(Constants.CLIENT_ROLE_AUDIENCE)
        rtc.enableVideo()
        rtc.enableLocalVideo(false)
        rtc.enableLocalAudio(false)

        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            autoSubscribeVideo = true
            autoSubscribeAudio = true
            publishCameraTrack = false
            publishMicrophoneTrack = false
        }

        Log.d(TAG, "📡 viewer joining channel='$channelName' uid=$uid tokenSet=${token != null}")
        val joinResult = rtc.joinChannel(token, channelName, uid, options)
        if (joinResult == 0) {
            currentChannel = channelName
            Log.d(TAG, "✅ joinChannel call accepted")
        } else {
            Log.w(TAG, "❌ joinChannel returned $joinResult — see Agora error codes")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Creator join — broadcaster role, publishes camera + mic to the channel.
    // ─────────────────────────────────────────────────────────────────────────

    fun joinAsBroadcaster(channelName: String, uid: Int = 0, token: String? = null) {
        val rtc = engine ?: run {
            Log.w(TAG, "joinAsBroadcaster called before initEngine")
            return
        }

        if (currentChannel == channelName) return
        if (currentChannel != null) rtc.leaveChannel()

        rtc.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
        rtc.enableVideo()
        rtc.enableAudio()
        rtc.startPreview()

        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            publishCameraTrack = true
            publishMicrophoneTrack = true
            autoSubscribeVideo = false
            autoSubscribeAudio = false
        }

        val joinResult = rtc.joinChannel(token, channelName, uid, options)
        if (joinResult == 0) {
            currentChannel = channelName
            Log.d(TAG, "✅ joining channel '$channelName' as broadcaster")
        } else {
            Log.w(TAG, "❌ broadcaster joinChannel returned $joinResult")
        }
    }

    /// Render the LOCAL camera preview into a SurfaceView the creator screen
    /// owns. uid=0 means "local user" to Agora.
    fun setupLocalVideo(surfaceView: SurfaceView) {
        engine?.setupLocalVideo(
            VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0)
        )
    }

    fun switchCamera() {
        engine?.switchCamera()
    }

    fun muteLocalAudio(muted: Boolean) {
        engine?.muteLocalAudioStream(muted)
    }

    fun leaveChannel() {
        engine?.leaveChannel()
        engine?.stopPreview()
        currentChannel = null
        _remoteUserJoined.value = false
        _remoteUid.value = 0
    }

    fun destroy() {
        leaveChannel()
        RtcEngine.destroy()
        engine = null
        Log.d(TAG, "engine destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Remote video rendering. Caller creates a SurfaceView, hands it in, and
    // Agora paints the remote stream onto it.
    // ─────────────────────────────────────────────────────────────────────────

    fun setupRemoteVideo(surfaceView: SurfaceView, uid: Int) {
        engine?.setupRemoteVideo(
            VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid)
        )
    }

    fun createRendererView(context: Context): SurfaceView =
        runCatching { RtcEngine.CreateRendererView(context.applicationContext) }
            .getOrElse { SurfaceView(context.applicationContext) }

    // ── Event handler ───────────────────────────────────────────────────────

    private val handler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.d(TAG, "✅ JOINED channel='$channel' uid=$uid elapsed=${elapsed}ms")
        }

        override fun onRejoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.d(TAG, "↻ rejoined '$channel' uid=$uid")
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.d(TAG, "✅ REMOTE USER $uid joined (elapsed=${elapsed}ms)")
            _remoteUid.value = uid
            _remoteUserJoined.value = true
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.d(TAG, "remote user $uid offline (reason=$reason)")
            if (_remoteUid.value == uid) {
                _remoteUserJoined.value = false
                _remoteUid.value = 0
            }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            Log.d(TAG, "🔌 connection state=$state reason=$reason")
        }

        override fun onConnectionLost() {
            Log.w(TAG, "⚠️ connection lost")
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            Log.w(TAG, "🔑 token expiring soon — need refresh")
        }

        override fun onRequestToken() {
            // Agora project requires a token but none was provided. Common
            // root cause when "viewer joins but never sees the stream" — the
            // join silently fails on Agora's side.
            Log.e(TAG, "🔑 Agora demands a token. Project is in 'App ID + Token' mode. " +
                       "Either switch the project to 'App ID only' (testing) OR generate a token server-side.")
        }

        override fun onError(err: Int) {
            val label = when (err) {
                2 -> "INVALID_ARGUMENT"
                17 -> "JOIN_CHANNEL_REJECTED"
                110 -> "TOKEN_INVALID"
                109 -> "TOKEN_EXPIRED"
                else -> "see io.agora.rtc2.Constants ERR_*"
            }
            Log.w(TAG, "❌ Agora error code=$err ($label)")
        }
    }
}
