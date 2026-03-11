package com.example.cactuspoc.agent1

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.cactuspoc.PipelineManager

class NotificationIngester : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationIngester"
        private const val DEBOUNCE_MS = 3_000L  // ignore same content within 3 seconds

        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.Slack",
            "com.google.android.gm",
            "com.microsoft.teams"
        )
    }

    // key = "$packageName|$sender|$body", value = timestamp last seen
    private val recentlySeen = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        if (packageName !in SUPPORTED_PACKAGES) return

        val extras = sbn.notification?.extras ?: return

        val title   = extras.getCharSequence("android.title")?.toString() ?: ""
        val text    = extras.getCharSequence("android.text")?.toString()  ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: text
        val body    = if (bigText.isNotBlank()) bigText else text

        if (title.isBlank() && body.isBlank()) return

        // ── Debounce ──────────────────────────────────────────────────────────
        val dedupeKey = "$packageName|$title|$body"
        val now = System.currentTimeMillis()
        val lastSeen = recentlySeen[dedupeKey]
        if (lastSeen != null && now - lastSeen < DEBOUNCE_MS) {
            Log.d(TAG, "Debounced duplicate from $packageName / $title")
            return
        }
        recentlySeen[dedupeKey] = now

        // Prune old entries so the map doesn't grow forever
        recentlySeen.entries.removeIf { now - it.value > DEBOUNCE_MS * 10 }

        // ── Enqueue ───────────────────────────────────────────────────────────
        val contact      = title.ifBlank { "Unknown" }
        val contactGroup = classifyGroup(packageName)
        val notifText    = buildNotificationText(packageName, contact, body)

        Log.d(TAG, "Notification from $packageName / $contact: ${body.take(60)}")

        PipelineManager.enqueueNotification(
            contact          = contact,
            contactGroup     = contactGroup,
            notificationText = notifText
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun classifyGroup(packageName: String) = when (packageName) {
        "com.Slack", "com.microsoft.teams" -> "work"
        else -> "personal"
    }

    private fun buildNotificationText(packageName: String, sender: String, body: String): String {
        val app = when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b"                     -> "WhatsApp"
            "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
            "com.Slack"                                             -> "Slack"
            "com.google.android.gm"                                 -> "Gmail"
            "com.microsoft.teams"                                   -> "Teams"
            else                                                    -> packageName
        }
        return "[$app] $sender: $body"
    }
}