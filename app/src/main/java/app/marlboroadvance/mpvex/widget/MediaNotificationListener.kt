/*
 * MediaNotificationListener.kt
 *
 * Empty NotificationListenerService: its only purpose is to hold the
 * user-granted notification access that unlocks
 * MediaSessionManager.getActiveSessions() for the media widget, and to
 * give the widget a system-bound host process.
 *
 * Licensed under the GNU General Public License v2 or later.
 */

package app.marlboroadvance.mpvex.widget

import android.service.notification.NotificationListenerService

class MediaNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    super.onListenerConnected()
    WidgetSessionHub.start(applicationContext)
  }

  override fun onListenerDisconnected() {
    WidgetSessionHub.stop()
    super.onListenerDisconnected()
  }
}
