package com.abdllh.aura.media

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.phone.MessageBanner
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.Notices
import com.abdllh.aura.system.SystemProps

/**
 * AMRI's notification listener. Being one is what lets AMRI see other apps' media sessions where it is not a
 * privileged app ([MediaMonitor]); since 1.13 it also does [Notices]' work: every notification another app posts is
 * looked at once. A message (from WhatsApp and the other messaging apps, or anything Android marks as a message) is
 * shown as AMRI's own card; then, while Android's notifications are to be hidden, the notification is taken away again.
 * Android lets a listener in only when it is allowed for it (Notices.allowListener does that), and binds it by itself
 * from then on. With the kill switch on (AMRI is not the home screen) it touches nothing.
 */
class AuraNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    private val off get() = SystemProps.get(Device.DISABLED_PROP) == "1"

    override fun onListenerConnected() {
        Log.i(TAG, "connected")
        // what other apps posted while AMRI was not listening goes too — a moment later: the listener is bound while
        // AMRI starts, and the start-up has the main thread then
        main.postDelayed({
            if (Notices.hideAndroid && !off) try { activeNotifications?.forEach { if (hideable(it)) cancel(it) } } catch (t: Throwable) { Log.w(TAG, "clear: $t") }
        }, 2000L)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (BuildConfig.DEBUG) Log.d(TAG, "posted ${n.packageName} ${n.key} clearable=${n.isClearable}")
        if (n.packageName == packageName || off) return // AMRI's own (the trip in progress) stays
        try {
            if (Notices.showMessages) Message.from(this, n)?.let { m -> main.post { MessageBanner.show(applicationContext, m) } }
        } catch (t: Throwable) {
            Log.w(TAG, "message: $t")
        }
        if (Notices.hideAndroid && hideable(n)) cancel(n)
    }

    /** Taken away only what can be: an ongoing notification (a running service, a download) cannot be cancelled. */
    private fun hideable(n: StatusBarNotification) = n.packageName != packageName && n.isClearable

    private fun cancel(n: StatusBarNotification) {
        try { cancelNotification(n.key) } catch (t: Throwable) { Log.w(TAG, "cancel: $t") }
    }

    /** One message as the card shows it: from which app, who wrote it (and in which group), what it says, where it opens. */
    class Message(
        val pkg: String,
        val app: String,
        val icon: Drawable?,
        val sender: String,
        val group: String?,
        val text: String,
        val open: PendingIntent?
    ) {
        companion object {
            /** Apps whose notifications are messages people send (Android's category says it for the others). */
            private val MESSAGING = setOf(
                "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
                "org.thunderdog.challegram", "com.google.android.apps.messaging", "com.android.mms",
                "com.samsung.android.messaging", "com.facebook.orca", "com.facebook.mlite", "org.thoughtcrime.securesms",
                "com.viber.voip", "jp.naver.line.android", "com.imo.android.imoim", "com.discord", "com.snapchat.android",
                "com.instagram.android", "com.microsoft.teams", "com.skype.raider"
            )

            /** The last message shown, so the same one posted again (an app updating its notification) is not shown twice. */
            private var lastKey = ""
            private var lastAt = 0L

            fun from(s: NotificationListenerService, sbn: StatusBarNotification): Message? {
                val n = sbn.notification ?: return null
                val pkg = sbn.packageName
                // (WhatsApp's "checking for new messages" and other running work; a group's summary repeats its children)
                if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
                if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
                val x = n.extras ?: return null
                val messaging = pkg in MESSAGING || n.category == Notification.CATEGORY_MESSAGE ||
                    x.getString(Notification.EXTRA_TEMPLATE).orEmpty().endsWith("MessagingStyle") || debugMessaging(pkg)
                if (!messaging) return null
                var sender = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                var text = (x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
                var group: String? = null
                // a conversation: its newest message, and who wrote it (in a group the title is the group's name)
                val msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES)
                val last = msgs?.lastOrNull() as? Bundle
                if (last != null) {
                    val t = last.getCharSequence("text")?.toString()
                    if (!t.isNullOrBlank()) text = t
                    val who = (last.getParcelable<android.app.Person>("sender_person")?.name ?: last.getCharSequence("sender"))?.toString()
                    val conversation = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                    if (!who.isNullOrBlank()) {
                        if (!conversation.isNullOrBlank() && conversation != who) group = conversation
                        sender = who
                    }
                }
                sender = sender.trim()
                text = text.trim()
                if (sender.isEmpty() && text.isEmpty()) return null
                val key = "$pkg|$sender|$text"
                val now = SystemClock.elapsedRealtime()
                if (key == lastKey && now - lastAt < 90_000L) return null
                lastKey = key
                lastAt = now
                val pm = s.packageManager
                val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Throwable) { null }
                val app = info?.let { pm.getApplicationLabel(it).toString() } ?: pkg
                val icon = try { pm.getApplicationIcon(pkg) } catch (_: Throwable) { null }
                return Message(pkg, app, icon, sender, group, text, n.contentIntent)
            }

            /** Emulator tests: debug builds take a chosen package's notifications as messages (setprop debug.aura.msgpkg com.android.shell). */
            private fun debugMessaging(pkg: String) = BuildConfig.DEBUG && SystemProps.get("debug.aura.msgpkg") == pkg
        }
    }

    companion object {
        private const val TAG = "AuraNotices"
    }
}
