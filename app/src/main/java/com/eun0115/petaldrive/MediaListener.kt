package com.eun0115.petaldrive

import android.app.Notification
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class MessagePreview(
    val title: String,
    val text: String,
    val source: String,
    val timestamp: Long
)

class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeListener = this
        notifyMessageUpdate()
    }

    override fun onListenerDisconnected() {
        if (activeListener === this) activeListener = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        notifyMessageUpdate()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notifyMessageUpdate()
    }

    fun getMessagePreviews(): List<MessagePreview> {
        if (activeListener !== this) return emptyList()

        return activeNotifications.mapNotNull { sbn ->
            val notification = sbn.notification
            val extras = notification.extras
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val latestMessage = messages?.lastOrNull() as? Bundle
            val isMessage = notification.category == Notification.CATEGORY_MESSAGE ||
                messages != null
            if (!isMessage) return@mapNotNull null

            val text = latestMessage?.getCharSequence("text")?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: return@mapNotNull null
            val source = sbn.packageName.substringAfterLast('.')
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?.takeIf { it.isNotBlank() } ?: source

            MessagePreview(title, text, source, sbn.postTime)
        }.sortedByDescending { it.timestamp }
    }

    private fun notifyMessageUpdate() {
        mainHandler.post { onMessageNotificationsChanged?.invoke() }
    }

    companion object {
        @Volatile
        var activeListener: MediaListener? = null
            private set

        @Volatile
        var onMessageNotificationsChanged: (() -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
