/*
 * WidgetSessionHub.kt
 *
 * Media-session tracking for the mpvEx home-screen widget.
 * Widget architecture adapted from VLC-Android's MiniPlayer widget
 * (Copyright © 2022 VLC authors and VideoLAN, GPLv2+), modified to
 * source state from ANY active MediaSession on the device instead of
 * a single app-private playback service.
 *
 * This file is licensed under the GNU General Public License v2 or later.
 */

package app.marlboroadvance.mpvex.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import androidx.palette.graphics.Palette
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

object WidgetSessionHub {

  private const val TAG = "MpvexWidgetHub"
  private const val TICK_MS = 1000L
  private const val ARTWORK_MAX_DIM = 384

  data class Snapshot(
    val hasAccess: Boolean = false,
    val hasSession: Boolean = false,
    val sessionCount: Int = 0,
    val sourcePackage: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val canSeek: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val positionTakenAt: Long = 0L,
    val artwork: Bitmap? = null,
    val palette: Palette? = null,
  ) {
    fun progressFraction(): Float {
      if (durationMs <= 0L) return -1f
      val extrapolated = if (playing) {
        positionMs + ((SystemClock.elapsedRealtime() - positionTakenAt) * speed).toLong()
      } else positionMs
      return min(1f, max(0f, extrapolated.toFloat() / durationMs))
    }

    fun currentPositionMs(): Long {
      val extrapolated = if (playing) {
        positionMs + ((SystemClock.elapsedRealtime() - positionTakenAt) * speed).toLong()
      } else positionMs
      return if (durationMs > 0) extrapolated.coerceIn(0L, durationMs) else max(0L, extrapolated)
    }
  }

  @Volatile
  var snapshot: Snapshot = Snapshot()
    private set

  private val mainHandler = Handler(Looper.getMainLooper())
  private val artworkExecutor = Executors.newSingleThreadExecutor()

  private var appContext: Context? = null
  private var sessionManager: MediaSessionManager? = null
  private var controller: MediaController? = null
  private var controllers: List<MediaController> = emptyList()
  private var manualToken: android.media.session.MediaSession.Token? = null
  private var artworkKey: String? = null
  private var tickerRunning = false
  private var started = false

  private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
    pickController(controllers ?: emptyList())
  }

  private val controllerCallback = object : MediaController.Callback() {
    override fun onPlaybackStateChanged(state: PlaybackState?) {
      refreshFromController(partialPreferred = false)
    }

    override fun onMetadataChanged(metadata: MediaMetadata?) {
      refreshFromController(partialPreferred = false)
    }

    override fun onSessionDestroyed() {
      mainHandler.post { restart() }
    }
  }

  fun start(context: Context) {
    val ctx = context.applicationContext
    appContext = ctx
    if (Looper.myLooper() == Looper.getMainLooper()) startInternal(ctx)
    else mainHandler.post { startInternal(ctx) }
  }

  private fun startInternal(ctx: Context) {
    val manager = ctx.getSystemService<MediaSessionManager>() ?: return
    sessionManager = manager
    val listenerComponent = ComponentName(ctx, MediaNotificationListener::class.java)

    if (started) {
      refreshSessions()
      return
    }

    val sessions = querySessions(manager, listenerComponent) ?: run {
      snapshot = Snapshot(hasAccess = false)
      MediaAppWidgetProvider.renderAll(ctx)
      return
    }

    try {
      manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, mainHandler)
    } catch (e: SecurityException) {
      Log.w(TAG, "addOnActiveSessionsChangedListener denied", e)
    }
    started = true
    pickController(sessions)
  }

  fun stop() {
    mainHandler.post {
      try {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
      } catch (_: Exception) {
      }
      controller?.unregisterCallback(controllerCallback)
      controller = null
      started = false
      stopTicker()
    }
  }

  private fun restart() {
    val ctx = appContext ?: return
    controller?.unregisterCallback(controllerCallback)
    controller = null
    refreshSessions()
    if (controller == null) {
      snapshot = snapshot.copy(hasSession = false, playing = false)
      MediaAppWidgetProvider.renderAll(ctx)
    }
  }

  private fun refreshSessions() {
    val ctx = appContext ?: return
    val manager = sessionManager ?: return
    val sessions = querySessions(manager, ComponentName(ctx, MediaNotificationListener::class.java))
    if (sessions == null) {
      snapshot = Snapshot(hasAccess = false)
      MediaAppWidgetProvider.renderAll(ctx)
      return
    }
    pickController(sessions)
  }

  private fun querySessions(
    manager: MediaSessionManager,
    listenerComponent: ComponentName,
  ): List<MediaController>? =
    try {
      manager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
      try {
        manager.getActiveSessions(null)
      } catch (e2: SecurityException) {
        Log.w(TAG, "No media session access (listener disabled, MEDIA_CONTENT_CONTROL not granted)")
        null
      }
    }

  private fun pickController(available: List<MediaController>) {
    val ctx = appContext ?: return
    controllers = available

    val manual = manualToken?.let { token ->
      available.firstOrNull { it.sessionToken == token }
    }
    if (manual == null) manualToken = null

    val best = manual
      ?: available.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
      ?: available.firstOrNull { it.playbackState != null && it.metadata != null }
      ?: available.firstOrNull()

    switchTo(best)

    if (best == null) {
      snapshot = Snapshot(hasAccess = true, hasSession = false, sessionCount = 0)
      stopTicker()
      MediaAppWidgetProvider.renderAll(ctx)
    } else {
      refreshFromController(partialPreferred = false)
    }
  }

  private fun switchTo(target: MediaController?) {
    if (target?.sessionToken != controller?.sessionToken) {
      controller?.unregisterCallback(controllerCallback)
      controller = target
      artworkKey = null
      target?.registerCallback(controllerCallback, mainHandler)
    }
  }

  /** Cycle manual selection through the currently active sessions. */
  fun cycleSession(context: Context, delta: Int) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { cycleSession(context, delta) }
      return
    }
    start(context)
    if (controllers.size < 2) return
    val currentIndex = controllers.indexOfFirst { it.sessionToken == controller?.sessionToken }
    val nextIndex = ((if (currentIndex < 0) 0 else currentIndex) + delta + controllers.size) % controllers.size
    val target = controllers[nextIndex]
    manualToken = target.sessionToken
    switchTo(target)
    refreshFromController(partialPreferred = false)
  }

  private fun refreshFromController(partialPreferred: Boolean) {
    val ctx = appContext ?: return
    val c = controller ?: return
    val state = c.playbackState
    val metadata = c.metadata

    val playing = state?.state == PlaybackState.STATE_PLAYING
    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    snapshot = snapshot.copy(
      hasAccess = true,
      hasSession = true,
      sessionCount = controllers.size,
      sourcePackage = c.packageName,
      title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
      artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
      playing = playing,
      canSeek = state != null && (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
      positionMs = state?.position ?: 0L,
      durationMs = if (duration > 0) duration else 0L,
      speed = if (playing && state != null && state.playbackSpeed > 0f) state.playbackSpeed else 1f,
      positionTakenAt = SystemClock.elapsedRealtime(),
    )

    loadArtworkIfNeeded(ctx, metadata)
    if (playing) startTicker() else stopTicker()
    MediaAppWidgetProvider.renderAll(ctx)
  }

  private fun loadArtworkIfNeeded(ctx: Context, metadata: MediaMetadata?) {
    val key = metadata?.let {
      it.getString(MediaMetadata.METADATA_KEY_ART_URI)
        ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        ?: ((it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") + "/" +
          (it.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""))
    }
    if (key == artworkKey) return
    artworkKey = key

    if (metadata == null) {
      snapshot = snapshot.copy(artwork = null, palette = null)
      return
    }

    artworkExecutor.execute {
      val bitmap = extractArtwork(ctx, metadata)
      val palette = bitmap?.let { runCatching { Palette.from(it).generate() }.getOrNull() }
      mainHandler.post {
        snapshot = snapshot.copy(artwork = bitmap, palette = palette)
        MediaAppWidgetProvider.renderAll(ctx)
      }
    }
  }

  private fun extractArtwork(ctx: Context, metadata: MediaMetadata): Bitmap? {
    val direct = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
      ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
      ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    if (direct != null) return downscale(direct)

    val uriString = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
      ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
      ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
      ?: return null

    return runCatching {
      ctx.contentResolver.openInputStream(Uri.parse(uriString))?.use {
        android.graphics.BitmapFactory.decodeStream(it)
      }
    }.getOrNull()?.let { downscale(it) }
  }

  private fun downscale(src: Bitmap): Bitmap {
    val maxDim = max(src.width, src.height)
    if (maxDim <= ARTWORK_MAX_DIM) return src
    val scale = ARTWORK_MAX_DIM.toFloat() / maxDim
    return Bitmap.createScaledBitmap(
      src,
      max(1, (src.width * scale).toInt()),
      max(1, (src.height * scale).toInt()),
      true,
    )
  }

  /* ---------------- commands ---------------- */

  fun command(context: Context, action: String) {
    if (controller == null) start(context)
    when (action) {
      MediaAppWidgetProvider.CMD_SESSION_NEXT -> {
        cycleSession(context, 1)
        return
      }
      MediaAppWidgetProvider.CMD_SESSION_PREV -> {
        cycleSession(context, -1)
        return
      }
    }
    val transport = controller?.transportControls ?: return
    when (action) {
      MediaAppWidgetProvider.CMD_PLAY_PAUSE ->
        if (snapshot.playing) transport.pause() else transport.play()
      MediaAppWidgetProvider.CMD_NEXT -> transport.skipToNext()
      MediaAppWidgetProvider.CMD_PREV -> transport.skipToPrevious()
    }
  }

  fun sessionActivityIntent(): android.app.PendingIntent? = controller?.sessionActivity

  /* ---------------- progress ticker ---------------- */

  private val ticker = object : Runnable {
    override fun run() {
      if (!tickerRunning) return
      val ctx = appContext ?: return
      if (!snapshot.playing || !MediaAppWidgetProvider.hasWidgets(ctx)) {
        tickerRunning = false
        return
      }
      MediaAppWidgetProvider.renderProgressOnly(ctx)
      mainHandler.postDelayed(this, TICK_MS)
    }
  }

  private fun startTicker() {
    val ctx = appContext ?: return
    if (tickerRunning || !MediaAppWidgetProvider.hasWidgets(ctx)) return
    tickerRunning = true
    mainHandler.postDelayed(ticker, TICK_MS)
  }

  private fun stopTicker() {
    tickerRunning = false
    mainHandler.removeCallbacks(ticker)
  }
}
