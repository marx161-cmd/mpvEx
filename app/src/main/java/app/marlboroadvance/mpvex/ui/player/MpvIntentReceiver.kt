package app.marlboroadvance.mpvex.ui.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class MpvIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val activity = PlayerActivity.instance

        when (action) {
            ACTION_ENTER_FREEFORM -> handleEnterFreeform(context, activity)
            ACTION_ENTER_PIP -> handleEnterPip(context, activity)
            ACTION_EXIT_FULLSCREEN -> handleExitFullscreen(context, activity)
            ACTION_TOGGLE_PLAY -> handleTogglePlay(context, activity)
            ACTION_TOGGLE_PAUSE -> handleTogglePause(context, activity)
            ACTION_ASPECT_WIDESCREEN -> handleAspect(activity, ASPECT_WIDESCREEN, "16:9")
            ACTION_ASPECT_CINEMA -> handleAspect(activity, ASPECT_CINEMA, "2.39:1")
            ACTION_ASPECT_SQUARE -> handleAspect(activity, ASPECT_SQUARE, "1:1")
        }
    }

    private fun handleEnterFreeform(context: Context, activity: PlayerActivity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "FREEFORM refused: PlayerActivity not available")
            return
        }
        if (activity.isInFreeform) {
            Log.w(TAG, "FREEFORM refused: already in freeform mode")
            return
        }
        activity.enterFreeformMode()
        triggerFreeformLaunch(context)
        Log.i(TAG, "FREEFORM: entered freeform mode")
    }

    private fun handleEnterPip(context: Context, activity: PlayerActivity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "PIP refused: PlayerActivity not available")
            return
        }
        if (activity.isInPictureInPictureMode) {
            Log.w(TAG, "PIP refused: already in PiP mode")
            return
        }
        activity.enterPipModeHidingOverlay()
        Log.i(TAG, "PIP: entered PiP mode")
    }

    private fun handleExitFullscreen(context: Context, activity: PlayerActivity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "EXIT_FULLSCREEN refused: PlayerActivity not available")
            return
        }
        if (!activity.isInFreeform && !activity.isInPictureInPictureMode) {
            Log.w(TAG, "EXIT_FULLSCREEN refused: not in freeform or PiP")
            return
        }
        activity.exitToFullscreen()
        Log.i(TAG, "EXIT_FULLSCREEN: returned to fullscreen")
    }

    private fun handleTogglePlay(context: Context, activity: PlayerActivity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "PLAY refused: PlayerActivity not available")
            return
        }
        activity.cyclePause()
        Log.i(TAG, "PLAY: toggled playback")
    }

    private fun handleTogglePause(context: Context, activity: PlayerActivity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "PAUSE refused: PlayerActivity not available")
            return
        }
        activity.cyclePause()
        Log.i(TAG, "PAUSE: toggled playback")
    }

    private fun handleAspect(activity: PlayerActivity?, aspect: Float, label: String) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "ASPECT $label refused: PlayerActivity not available")
            return
        }
        if (!activity.isInPictureInPictureMode) {
            Log.w(TAG, "ASPECT $label refused: not in PiP mode")
            return
        }
        activity.setPipAspectRatio(aspect)
        Log.i(TAG, "ASPECT: set to $label ($aspect)")
    }

    private fun triggerFreeformLaunch(context: Context) {
        val intent = Intent(FREEFORM_ACTION).apply {
            component = ComponentName(
                FREEFORM_PACKAGE,
                FREEFORM_RECEIVER,
            )
            putExtra("packageName", "com.termux.mpv")
            putExtra("activityName", "app.marlboroadvance.mpvex.ui.player.PlayerActivity")
            putExtra("userId", 0)
            putExtra("taskId", -1)
        }
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "FREEFORM launch broadcast failed", e)
        }
    }

    companion object {
        private const val TAG = "MpvIntent"

        const val ACTION_ENTER_FREEFORM = "com.termux.mpv.action.ENTER_FREEFORM"
        const val ACTION_ENTER_PIP = "com.termux.mpv.action.ENTER_PIP"
        const val ACTION_EXIT_FULLSCREEN = "com.termux.mpv.action.EXIT_FULLSCREEN"
        const val ACTION_TOGGLE_PLAY = "com.termux.mpv.action.TOGGLE_PLAY"
        const val ACTION_TOGGLE_PAUSE = "com.termux.mpv.action.TOGGLE_PAUSE"
        const val ACTION_ASPECT_WIDESCREEN = "com.termux.mpv.action.ASPECT_WIDESCREEN"
        const val ACTION_ASPECT_CINEMA = "com.termux.mpv.action.ASPECT_CINEMA"
        const val ACTION_ASPECT_SQUARE = "com.termux.mpv.action.ASPECT_SQUARE"

        const val ASPECT_WIDESCREEN = 1.78f
        const val ASPECT_CINEMA = 2.39f
        const val ASPECT_SQUARE = 1.00f

        const val FREEFORM_ACTION = "com.libremobileos.freeform.START_FREEFORM"
        const val FREEFORM_PACKAGE = "com.libremobileos.freeform"
        const val FREEFORM_RECEIVER = "com.libremobileos.freeform.receiver.StartFreeformReceiver"
    }
}
