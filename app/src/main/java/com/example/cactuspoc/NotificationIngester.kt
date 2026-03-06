package com.example.cactuspoc

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens to notifications from messaging/email apps and enqueues them
 * into the pipeline for extraction.
 *
 * Supported packages: WhatsApp, Telegram, Slack, Gmail, Microsoft Teams.
 *
 * Must be declared in AndroidManifest.xml:
 *
 *   <service
 *       android:name=".agent1.NotificationIngester"
 *       android:label="Cactus POC"
 *       android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.service.notification.NotificationListenerService" />
 *       </intent-filter>
 *   </service>
 *
 * The user must also grant notification access via:
 *   Settings → Apps → Special app access → Notification access
 */
class NotificationIngester : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationIngester"

        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",            // WhatsApp Business
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.Slack",
            "com.google.android.gm",       // Gmail
            "com.microsoft.teams"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        if (packageName !in SUPPORTED_PACKAGES) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract title (sender) and text (message body)
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text  = extras.getCharSequence("android.text")?.toString()  ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: text

        if (title.isBlank() && bigText.isBlank()) {
            Log.d(TAG, "Skipping empty notification from $packageName")
            return
        }

        val contact = title.ifBlank { "Unknown" }
        val contactGroup = classifyGroup(packageName)
        val body = if (bigText.isNotBlank()) bigText else text
        val notificationText = buildNotificationText(packageName, contact, body)

        Log.d(TAG, "Notification from $packageName / $contact: ${body.take(60)}")

        PipelineManager.enqueueNotification(
            contact = contact,
            contactGroup = contactGroup,
            notificationText = notificationText
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op — we don't need to react to removed notifications
    }

    private fun classifyGroup(packageName: String): String {
        return when (packageName) {
            "com.Slack",
            "com.microsoft.teams" -> "work"
            else -> "personal"
        }
    }

    private fun buildNotificationText(packageName: String, sender: String, body: String): String {
        val appLabel = when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
            "com.Slack"                         -> "Slack"
            "com.google.android.gm"             -> "Gmail"
            "com.microsoft.teams"               -> "Teams"
            else                                -> packageName
        }
        return "[$appLabel] $sender: $body"
    }
}
