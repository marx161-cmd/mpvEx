/*
 * MediaAppWidgetProvider.kt
 *
 * Adaptive home-screen media widget for mpvEx.
 * Rendering approach (size-adaptive pill/mini/macro RemoteViews layouts,
 * runtime-tinted icons, artwork palette theming, bitmap progress ring)
 * adapted from VLC-Android's MiniPlayerAppWidgetProvider
 * (Copyright © 2022 VLC authors and VideoLAN, GPLv2+).
 * Control/data path rewritten on top of the system MediaSession stack so
 * the widget follows ANY app's active media session.
 *
 * This file is licensed under the GNU General Public License v2 or later.
 */

package app.marlboroadvance.mpvex.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import app.marlboroadvance.mpvex.R
import kotlin.math.min
import kotlin.math.roundToInt

class MediaAppWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    WidgetSessionHub.start(context)
    appWidgetIds.forEach { renderOne(context, appWidgetManager, it) }
  }

  override fun onEnabled(context: Context) {
    super.onEnabled(context)
    WidgetSessionHub.start(context)
  }

  override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle?,
  ) {
    renderOne(context, appWidgetManager, appWidgetId)
    super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
  }

  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      CMD_PLAY_PAUSE, CMD_NEXT, CMD_PREV, CMD_SESSION_NEXT, CMD_SESSION_PREV ->
        WidgetSessionHub.command(context, intent.action!!)
      else -> super.onReceive(context, intent)
    }
  }

  enum class WidgetType(val layout: Int) {
    PILL(R.layout.widget_media_pill),
    MINI(R.layout.widget_media_mini),
    MACRO(R.layout.widget_media_macro),
  }

  companion object {
    private const val TAG = "MpvexMediaWidget"

    const val CMD_PLAY_PAUSE = "app.marlboroadvance.mpvex.widget.PLAY_PAUSE"
    const val CMD_NEXT = "app.marlboroadvance.mpvex.widget.NEXT"
    const val CMD_PREV = "app.marlboroadvance.mpvex.widget.PREV"
    const val CMD_SESSION_NEXT = "app.marlboroadvance.mpvex.widget.SESSION_NEXT"
    const val CMD_SESSION_PREV = "app.marlboroadvance.mpvex.widget.SESSION_PREV"

    fun hasWidgets(context: Context): Boolean =
      widgetIds(context).isNotEmpty()

    private fun widgetIds(context: Context): IntArray =
      AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, MediaAppWidgetProvider::class.java))

    fun renderAll(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      widgetIds(context).forEach { renderOne(context, manager, it) }
    }

    fun renderProgressOnly(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val snapshot = WidgetSessionHub.snapshot
      widgetIds(context).forEach { id ->
        val type = widgetType(context, manager, id)
        val views = RemoteViews(context.packageName, type.layout)
        setProgress(context, views, type, snapshot)
        try {
          manager.partiallyUpdateAppWidget(id, views)
        } catch (e: Exception) {
          Log.w(TAG, "partial update failed for $id", e)
        }
      }
    }

    private fun renderOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
      val type = widgetType(context, manager, appWidgetId)
      val views = buildViews(context, type, appWidgetId)
      try {
        manager.updateAppWidget(appWidgetId, views)
      } catch (e: Exception) {
        Log.w(TAG, "update failed for $appWidgetId", e)
      }
    }

    /** Size-to-type mapping, thresholds borrowed from VLC's WidgetUtils. */
    private fun widgetType(context: Context, manager: AppWidgetManager, appWidgetId: Int): WidgetType {
      val options = manager.getAppWidgetOptions(appWidgetId)
      val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
      val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
      return when {
        width > 220 && height > 220 -> WidgetType.MACRO
        width > 220 && height > 72 -> WidgetType.MINI
        width > 220 -> WidgetType.MINI
        else -> WidgetType.PILL
      }
    }

    /* ---------------- colors ---------------- */

    private fun isNight(context: Context): Boolean =
      (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private fun backgroundColor(context: Context, palette: Palette?): Int {
      val night = isNight(context)
      palette?.let {
        val swatch = if (night) it.darkMutedSwatch ?: it.darkVibrantSwatch
        else it.lightMutedSwatch ?: it.lightVibrantSwatch
        if (swatch != null) return swatch.rgb
      }
      return if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.getColor(
          context,
          if (night) android.R.color.system_neutral2_800 else android.R.color.system_neutral2_50,
        )
      } else if (night) 0xFF202124.toInt() else 0xFFF1F3F4.toInt()
    }

    private fun foregroundColor(context: Context, palette: Palette?): Int {
      val night = isNight(context)
      palette?.let {
        val swatch = if (night) it.lightVibrantSwatch ?: it.lightMutedSwatch
        else it.darkVibrantSwatch ?: it.darkMutedSwatch
        if (swatch != null) return swatch.rgb
      }
      return if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.getColor(
          context,
          if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600,
        )
      } else if (night) Color.WHITE else 0xFF1F1F1F.toInt()
    }

    /** Monet tertiary (accent3) — the magenta half of a teal/magenta scheme. */
    private fun accentTertiary(context: Context): Int =
      if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.getColor(
          context,
          if (isNight(context)) android.R.color.system_accent3_200 else android.R.color.system_accent3_600,
        )
      } else foregroundColor(context, null)

    /* ---------------- rendering ---------------- */

    private fun buildViews(context: Context, type: WidgetType, appWidgetId: Int): RemoteViews {
      val snapshot = WidgetSessionHub.snapshot
      val views = RemoteViews(context.packageName, type.layout)
      val palette = if (snapshot.artwork != null) snapshot.palette else null
      val fg = foregroundColor(context, palette)
      val bg = backgroundColor(context, palette)
      val artistColor = ColorUtils.setAlphaComponent(fg, 0xB3)

      views.setInt(R.id.player_container_background, "setColorFilter", bg)
      if (type == WidgetType.MACRO) {
        views.setInt(
          R.id.panel_background, "setColorFilter",
          ColorUtils.setAlphaComponent(bg, 0xE6),
        )
      }

      /* buttons */
      views.setImageViewBitmap(
        R.id.play_pause,
        tintedBitmap(
          context,
          if (snapshot.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
          fg, 32.dp(context),
        ),
      )
      views.setImageViewBitmap(R.id.prev, tintedBitmap(context, R.drawable.ic_widget_prev, fg, 28.dp(context)))
      views.setImageViewBitmap(R.id.next, tintedBitmap(context, R.drawable.ic_widget_next, fg, 28.dp(context)))

      views.setOnClickPendingIntent(R.id.play_pause, commandIntent(context, CMD_PLAY_PAUSE, 1))
      views.setOnClickPendingIntent(R.id.prev, commandIntent(context, CMD_PREV, 2))
      views.setOnClickPendingIntent(R.id.next, commandIntent(context, CMD_NEXT, 3))
      views.setOnClickPendingIntent(R.id.widget_container, tapThroughIntent(context, snapshot))
      views.setOnClickPendingIntent(R.id.cover, tapThroughIntent(context, snapshot))
      views.setOnClickPendingIntent(R.id.app_icon, tapThroughIntent(context, snapshot))

      /* session switchers (outer row slots, mini/macro only) */
      if (type != WidgetType.PILL) {
        val switcherColor = ColorUtils.setAlphaComponent(fg, 0xB3)
        views.setImageViewBitmap(
          R.id.session_prev,
          tintedBitmap(context, R.drawable.ic_widget_session_prev, switcherColor, 24.dp(context)),
        )
        views.setImageViewBitmap(
          R.id.session_next,
          tintedBitmap(context, R.drawable.ic_widget_session_next, switcherColor, 24.dp(context)),
        )
        views.setOnClickPendingIntent(R.id.session_prev, commandIntent(context, CMD_SESSION_PREV, 6))
        views.setOnClickPendingIntent(R.id.session_next, commandIntent(context, CMD_SESSION_NEXT, 7))
        val switchersVisible = snapshot.hasAccess && snapshot.sessionCount > 1
        views.setViewVisibility(R.id.session_prev, if (switchersVisible) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.session_next, if (switchersVisible) View.VISIBLE else View.INVISIBLE)
      }

      /* texts */
      val title = when {
        !snapshot.hasAccess -> context.getString(R.string.widget_grant_access)
        !snapshot.hasSession || snapshot.title.isNullOrBlank() ->
          context.getString(R.string.widget_nothing_playing)
        else -> snapshot.title
      }
      views.setTextViewText(R.id.songName, title)
      views.setTextViewText(R.id.artist, snapshot.artist.orEmpty())
      views.setTextColor(R.id.songName, fg)
      views.setTextColor(R.id.artist, artistColor)
      views.setViewVisibility(
        R.id.artist,
        if (snapshot.hasSession && !snapshot.artist.isNullOrBlank()) View.VISIBLE else View.GONE,
      )

      /* controls visibility */
      val controlsVisible = snapshot.hasAccess && snapshot.hasSession
      views.setViewVisibility(R.id.prev, if (controlsVisible) View.VISIBLE else View.INVISIBLE)
      views.setViewVisibility(R.id.next, if (controlsVisible) View.VISIBLE else View.INVISIBLE)
      views.setViewVisibility(R.id.play_pause, if (controlsVisible) View.VISIBLE else View.INVISIBLE)

      /* cover */
      val coverSize = if (type == WidgetType.MACRO) 512 else 256
      val artwork = snapshot.artwork
      if (artwork != null) {
        val coverBitmap = when (type) {
          WidgetType.PILL -> circleBitmap(artwork, coverSize)
          WidgetType.MINI -> roundedBitmap(artwork, coverSize, 24f)
          WidgetType.MACRO -> roundedBitmap(artwork, coverSize, 32f)
        }
        views.setImageViewBitmap(R.id.cover, coverBitmap)
        views.setViewVisibility(R.id.cover, View.VISIBLE)
        views.setViewVisibility(R.id.app_icon, View.GONE)
      } else {
        views.setViewVisibility(R.id.cover, View.GONE)
        views.setViewVisibility(R.id.app_icon, View.VISIBLE)
        views.setImageViewBitmap(
          R.id.app_icon,
          tintedBitmap(context, R.drawable.ic_launcher_monochrome, fg, 48.dp(context)),
        )
      }

      /* glass base while no artwork is shown (Monet-tinted) */
      val glass = artwork == null
      views.setInt(R.id.player_container_background, "setImageAlpha", if (glass) 92 else 255)
      views.setViewVisibility(R.id.glass_overlay, if (glass) View.VISIBLE else View.GONE)
      views.setViewVisibility(R.id.glass_stroke, if (glass) View.VISIBLE else View.GONE)
      if (glass) {
        views.setInt(R.id.glass_overlay, "setColorFilter", foregroundColor(context, null))
        views.setInt(R.id.glass_stroke, "setColorFilter", accentTertiary(context))
      }
      if (type == WidgetType.MACRO) {
        views.setViewVisibility(R.id.panel_background, if (glass) View.INVISIBLE else View.VISIBLE)
      }

      setProgress(context, views, type, snapshot)
      return views
    }

    private fun setProgress(
      context: Context,
      views: RemoteViews,
      type: WidgetType,
      snapshot: WidgetSessionHub.Snapshot,
    ) {
      val fraction = if (snapshot.hasSession) snapshot.progressFraction() else -1f
      if (fraction < 0f) {
        views.setViewVisibility(R.id.progress_round, View.GONE)
        return
      }
      val fg = foregroundColor(context, if (snapshot.artwork != null) snapshot.palette else null)
      val sizePx = if (type == WidgetType.PILL) 56.dp(context) else 44.dp(context)
      views.setViewVisibility(R.id.progress_round, View.VISIBLE)
      views.setImageViewBitmap(
        R.id.progress_round,
        ringBitmap(sizePx, 2.dp(context).toFloat(), fraction, fg),
      )
    }

    /* ---------------- intents ---------------- */

    private fun commandIntent(context: Context, action: String, requestCode: Int): PendingIntent =
      PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(action, null, context, MediaAppWidgetProvider::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    private fun tapThroughIntent(context: Context, snapshot: WidgetSessionHub.Snapshot): PendingIntent {
      if (!snapshot.hasAccess) {
        val settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
          context, 4, settings,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      }
      WidgetSessionHub.sessionActivityIntent()?.let { return it }
      val launch = snapshot.sourcePackage
        ?.let { context.packageManager.getLaunchIntentForPackage(it) }
        ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
      return PendingIntent.getActivity(
        context, 5,
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: Intent(),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    /* ---------------- bitmap helpers ---------------- */

    private fun Int.dp(context: Context): Int =
      (this * context.resources.displayMetrics.density).roundToInt()

    private fun tintedBitmap(context: Context, @DrawableRes res: Int, color: Int, sizePx: Int): Bitmap? {
      val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
      drawable.setTint(color)
      val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      drawable.setBounds(0, 0, sizePx, sizePx)
      drawable.draw(canvas)
      return bitmap
    }

    private fun ringBitmap(sizePx: Int, strokePx: Float, fraction: Float, color: Int): Bitmap {
      val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
      }
      val inset = strokePx / 2f + 1f
      val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)
      paint.color = ColorUtils.setAlphaComponent(color, 0x40)
      canvas.drawArc(rect, 0f, 360f, false, paint)
      paint.color = color
      canvas.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, paint)
      return bitmap
    }

    private fun cropSquare(src: Bitmap, sizePx: Int): Bitmap {
      val side = min(src.width, src.height)
      val scale = sizePx.toFloat() / side
      val matrix = Matrix().apply { setScale(scale, scale) }
      val squared = Bitmap.createBitmap(
        src,
        (src.width - side) / 2,
        (src.height - side) / 2,
        side, side, matrix, true,
      )
      return squared
    }

    private fun circleBitmap(src: Bitmap, sizePx: Int): Bitmap {
      val squared = cropSquare(src, sizePx)
      val output = Bitmap.createBitmap(squared.width, squared.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(output)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(squared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
      }
      val radius = squared.width / 2f
      canvas.drawCircle(radius, radius, radius, paint)
      return output
    }

    private fun roundedBitmap(src: Bitmap, sizePx: Int, radiusPx: Float): Bitmap {
      val squared = cropSquare(src, sizePx)
      val output = Bitmap.createBitmap(squared.width, squared.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(output)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(squared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
      }
      val path = Path().apply {
        addRoundRect(
          RectF(0f, 0f, squared.width.toFloat(), squared.height.toFloat()),
          radiusPx, radiusPx, Path.Direction.CW,
        )
      }
      canvas.drawPath(path, paint)
      return output
    }
  }
}
