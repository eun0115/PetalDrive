package com.eun0115.petaldrive

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Android only lets an app read other apps' media sessions
 * (for the Now Playing card) if it holds notification access via a listener service.
 */
class MediaListener : NotificationListenerService()
