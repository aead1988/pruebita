package com.aatorque.stats

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

class NotiService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.lastOrNull { it.packageName == SPOTIFY_PACKAGE }?.let(::captureSpotifyArtwork)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE) captureSpotifyArtwork(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == SPOTIFY_PACKAGE && sbn.key == cachedNotificationKey) {
            cachedSpotifyArtwork = null
            cachedSpotifyArtworkKey = null
            cachedSpotifyTitle = null
            cachedSpotifyArtist = null
            cachedNotificationKey = null
        }
    }

    private fun captureSpotifyArtwork(sbn: StatusBarNotification) {
        val artwork = notificationArtwork(sbn.notification) ?: return
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val artist = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        cachedSpotifyArtwork = artwork
        cachedSpotifyArtworkKey = "$title:${artwork.generationId}"
        cachedSpotifyTitle = title
        cachedSpotifyArtist = artist
        cachedNotificationKey = sbn.key
    }

    private fun notificationArtwork(notification: Notification): Bitmap? {
        val extras = notification.extras
        val candidates = listOf(
            extras.get(Notification.EXTRA_PICTURE),
            extras.get(Notification.EXTRA_LARGE_ICON_BIG),
            extras.get(Notification.EXTRA_LARGE_ICON),
            notification.largeIcon
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            when (candidate) {
                is Bitmap -> candidate
                is Icon -> candidate.loadDrawable(this)?.let(::drawableToBitmap)
                is Drawable -> drawableToBitmap(candidate)
                else -> null
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        @Volatile private var cachedSpotifyArtwork: Bitmap? = null
        @Volatile private var cachedSpotifyArtworkKey: String? = null
        @Volatile private var cachedSpotifyTitle: String? = null
        @Volatile private var cachedSpotifyArtist: String? = null
        @Volatile private var cachedNotificationKey: String? = null

        data class SpotifyArtwork(
            val bitmap: Bitmap,
            val key: String,
            val title: String?,
            val artist: String?
        )

        fun isNotificationAccessEnabled(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(BuildConfig.APPLICATION_ID)
        }

        fun currentSpotifyArtwork(): SpotifyArtwork? {
            val bitmap = cachedSpotifyArtwork ?: return null
            val key = cachedSpotifyArtworkKey ?: bitmap.generationId.toString()
            return SpotifyArtwork(bitmap, key, cachedSpotifyTitle, cachedSpotifyArtist)
        }
    }
}
