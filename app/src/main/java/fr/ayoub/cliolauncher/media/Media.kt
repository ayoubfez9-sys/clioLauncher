package fr.ayoub.cliolauncher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import fr.ayoub.cliolauncher.data.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service vide : Android exige qu'il existe (et que l'utilisateur l'autorise)
 * pour qu'une appli puisse lire la musique en cours des autres applis.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaRepository.start(applicationContext)
    }
}

data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val appLabel: String,
    val durationMs: Long,
    val positionMs: Long,
    val updateTime: Long,
    val speed: Float,
    val playing: Boolean,
    val art: ImageBitmap?,
)

/** Suit l'appli qui joue de la musique (Spotify, YouTube Music, lecteur Junsun, Zlink...). */
object MediaRepository {
    private val _info = MutableStateFlow<NowPlayingInfo?>(null)
    val info: StateFlow<NowPlayingInfo?> = _info

    private var appCtx: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            refresh()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (manager != null || !Permissions.hasNotifAccess(app)) return
        val m = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(app, MediaListenerService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, Handler(Looper.getMainLooper()))
            manager = m
            pick(m.getActiveSessions(cn))
        } catch (e: SecurityException) {
            // acces aux notifications pas encore accorde
        }
    }

    private fun refresh() {
        val m = manager ?: return
        val app = appCtx ?: return
        try {
            pick(m.getActiveSessions(ComponentName(app, MediaListenerService::class.java)))
        } catch (e: SecurityException) {
            _info.value = null
        }
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, Handler(Looper.getMainLooper()))
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) { _info.value = null; return }
        val md = c.metadata
        if (md == null) { _info.value = null; return }
        val st = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) { _info.value = null; return }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val label = try {
            val pm = appCtx?.packageManager
            pm?.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0))?.toString() ?: c.packageName
        } catch (e: Exception) { c.packageName }
        _info.value = NowPlayingInfo(
            title = title,
            artist = artist,
            appLabel = label,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = st?.position ?: 0L,
            updateTime = st?.lastPositionUpdateTime ?: 0L,
            speed = st?.playbackSpeed ?: 1f,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            art = bmp?.asImageBitmap(),
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
}
