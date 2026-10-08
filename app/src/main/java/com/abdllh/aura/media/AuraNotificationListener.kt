package com.abdllh.aura.media

import android.service.notification.NotificationListenerService

/**
 * Empty listener. Declaring it (and having notification access enabled) is what authorises
 * MediaSessionManager.getActiveSessions() on devices where Aura is not a privileged system app.
 */
class AuraNotificationListener : NotificationListenerService()
