package ac.mdiq.podcini.playback

import ac.mdiq.podcini.feed.util.PlaybackSpeedUtils.getCurrentPlaybackSpeed
import ac.mdiq.podcini.playback.base.MediaPlayerBase
import ac.mdiq.podcini.playback.base.PlayerStatus
import ac.mdiq.podcini.playback.service.PlaybackService
import ac.mdiq.podcini.playback.service.PlaybackService.LocalBinder
import ac.mdiq.podcini.playback.service.PlaybackServiceConstants
import ac.mdiq.podcini.preferences.PlaybackPreferences
import ac.mdiq.podcini.preferences.UserPreferences
import ac.mdiq.podcini.storage.DBWriter
import ac.mdiq.podcini.storage.model.feed.FeedMedia
import ac.mdiq.podcini.storage.model.playback.MediaType
import ac.mdiq.podcini.storage.model.playback.Playable
import ac.mdiq.podcini.util.Logd
import ac.mdiq.podcini.util.event.playback.PlaybackPositionEvent
import ac.mdiq.podcini.util.event.playback.PlaybackServiceEvent
import ac.mdiq.podcini.util.event.playback.SpeedChangedEvent
import android.content.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Pair
import android.view.SurfaceHolder
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Communicates with the playback service. GUI classes should use this class to
 * control playback instead of communicating with the PlaybackService directly.
 */
@UnstableApi
abstract class PlaybackController(private val activity: FragmentActivity) {

    private var mediaInfoLoaded = false
    private var released = false
    private var initialized = false
    private var eventsRegistered = false

    private var loadedFeedMedia: Long = -1

    val position: Int
        get() = playbackService?.currentPosition ?: getMedia()?.getPosition() ?: Playable.INVALID_TIME

    val duration: Int
        get() = playbackService?.duration ?: getMedia()?.getDuration() ?: Playable.INVALID_TIME

    val currentPlaybackSpeedMultiplier: Float
        get() = playbackService?.currentPlaybackSpeed ?: getCurrentPlaybackSpeed(getMedia())

    val isPlayingVideoLocally: Boolean
        get() = when {
            PlaybackService.isCasting -> false
            playbackService != null -> PlaybackService.currentMediaType == MediaType.VIDEO
            else -> getMedia()?.getMediaType() == MediaType.VIDEO
        }

    /**
     * Creates a new connection to the playbackService.
     */
    @Synchronized
    fun init() {
        if (!eventsRegistered) {
            EventBus.getDefault().register(this)
            eventsRegistered = true
        }
        if (PlaybackService.isRunning) initServiceRunning()
        else updatePlayButtonShowsPlay(true)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: PlaybackServiceEvent) {
        if (event.action == PlaybackServiceEvent.Action.SERVICE_STARTED) init()
    }

    @Synchronized
    private fun initServiceRunning() {
        if (initialized) return
        initialized = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(statusUpdate, IntentFilter(PlaybackService.ACTION_PLAYER_STATUS_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            activity.registerReceiver(notificationReceiver, IntentFilter(PlaybackServiceConstants.ACTION_PLAYER_NOTIFICATION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(statusUpdate, IntentFilter(PlaybackService.ACTION_PLAYER_STATUS_CHANGED))
            activity.registerReceiver(notificationReceiver, IntentFilter(PlaybackServiceConstants.ACTION_PLAYER_NOTIFICATION))
        }

        if (!released) bindToService()
        else throw IllegalStateException("Can't call init() after release() has been called")

        checkMediaInfoLoaded()
    }

    /**
     * Should be called if the PlaybackController is no longer needed, for
     * example in the activity's onStop() method.
     */
    fun release() {
        Logd(TAG, "Releasing PlaybackController")

        try {
            activity.unregisterReceiver(statusUpdate)
        } catch (e: IllegalArgumentException) {
            // ignore
        }

        try {
            activity.unregisterReceiver(notificationReceiver)
        } catch (e: IllegalArgumentException) {
            // ignore
        }
        unbind()
        media = null
        released = true

        if (eventsRegistered) {
            EventBus.getDefault().unregister(this)
            eventsRegistered = false
        }
    }

    private fun unbind() {
        try {
            activity.unbindService(mConnection)
        } catch (e: IllegalArgumentException) {
            // ignore
        }
        initialized = false
    }

    /**
     * Should be called in the activity's onPause() method.
     */
    fun pause() {
//        TODO: why set it to false
//        mediaInfoLoaded = false
    }

    /**
     * Tries to establish a connection to the PlaybackService. If it isn't
     * running, the PlaybackService will be started with the last played media
     * as the arguments of the launch intent.
     */
    private fun bindToService() {
        Logd(TAG, "Trying to connect to service")
        check(PlaybackService.isRunning) { "Trying to bind but service is not running" }
        val bound = activity.bindService(Intent(activity, PlaybackService::class.java), mConnection, 0)
        Logd(TAG, "Result for service binding: $bound")
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            if (service is LocalBinder) {
                playbackService = service.service
                onPlaybackServiceConnected()
                if (!released) {
                    queryService()
                    Logd(TAG, "Connection to Service established")
                } else Log.i(TAG, "Connection to playback service has been established, but controller has already been released")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
            initialized = false
            Logd(TAG, "Disconnected from Service")
        }
    }

    var prevStatus = PlayerStatus.STOPPED
    private val statusUpdate: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (playbackService != null) {
                val info = playbackService!!.mPlayerInfo
                if (prevStatus != info.playerStatus || media == null || media!!.getIdentifier() != info.playable?.getIdentifier()) {
//                    Log.d(TAG, "statusUpdate onReceive $prevStatus ${MediaPlayerBase.status} ${info.playerStatus} ${media?.getIdentifier()} ${info.playable?.getIdentifier()}.")
                    MediaPlayerBase.status = info.playerStatus
                    prevStatus = MediaPlayerBase.status
                    media = info.playable
                    handleStatus()
                }
            } else {
                Log.w(TAG, "statusUpdate onReceive: Couldn't receive status update: playbackService was null")
                if (PlaybackService.isRunning) bindToService()
                else {
                    MediaPlayerBase.status = PlayerStatus.STOPPED
                    handleStatus()
                }
            }
        }
    }

    private val notificationReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = intent.getIntExtra(PlaybackServiceConstants.EXTRA_NOTIFICATION_TYPE, -1)
            val code = intent.getIntExtra(PlaybackServiceConstants.EXTRA_NOTIFICATION_CODE, -1)
            if (code == -1 || type == -1) {
                Logd(TAG, "Bad arguments. Won't handle intent")
                return
            }
            when (type) {
                PlaybackServiceConstants.NOTIFICATION_TYPE_RELOAD -> {
                    if (playbackService == null && PlaybackService.isRunning) {
                        bindToService()
                        return
                    }
                    mediaInfoLoaded = false
                    queryService()
                }
                PlaybackServiceConstants.NOTIFICATION_TYPE_PLAYBACK_END -> onPlaybackEnd()
            }
        }
    }

    open fun onPlaybackEnd() {}

    /**
     * Is called whenever the PlaybackService changes its status. This method
     * should be used to update the GUI or start/cancel background threads.
     */
    private fun handleStatus() {
//        Log.d(TAG, "handleStatus() called status: ${MediaPlayerBase.status}")
        checkMediaInfoLoaded()
        when (MediaPlayerBase.status) {
            PlayerStatus.PLAYING -> updatePlayButtonShowsPlay(false)
            PlayerStatus.PREPARING -> updatePlayButtonShowsPlay(!(playbackService?.isStartWhenPrepared ?: false))
            PlayerStatus.FALLBACK, PlayerStatus.PAUSED, PlayerStatus.PREPARED, PlayerStatus.STOPPED, PlayerStatus.INITIALIZED ->
                updatePlayButtonShowsPlay(true)
            else -> {}
        }
    }

    private fun checkMediaInfoLoaded() {
        if (!mediaInfoLoaded || loadedFeedMedia != PlaybackPreferences.currentlyPlayingFeedMediaId) {
            loadedFeedMedia = PlaybackPreferences.currentlyPlayingFeedMediaId
            loadMediaInfo()
        }
        mediaInfoLoaded = true
    }

    protected open fun updatePlayButtonShowsPlay(showPlay: Boolean) {}

    abstract fun loadMediaInfo()

    open fun onPlaybackServiceConnected() { }

    /**
     * Called when connection to playback service has been established or
     * information has to be refreshed
     */
    private fun queryService() {
        Logd(TAG, "Querying service info")
        if (playbackService != null) {
            val info = playbackService!!.mPlayerInfo
            MediaPlayerBase.status = info.playerStatus
            media = info.playable
            // make sure that new media is loaded if it's available
            mediaInfoLoaded = false
            handleStatus()
        } else {
            Log.e(TAG, "queryService() was called without an existing connection to playbackservice")
        }
    }

    fun ensureService() {
        if (media == null) return
        if (playbackService == null) {
            PlaybackServiceStarter(activity, media!!).start()
            Log.w(TAG, "playbackservice was null, restarted!")
        }
    }

    fun playPause() {
        if (media == null) return
        if (playbackService == null) {
            PlaybackServiceStarter(activity, media!!).start()
            Log.w(TAG, "playbackservice was null, restarted!")
            return
        }
        when (MediaPlayerBase.status) {
            PlayerStatus.FALLBACK -> fallbackSpeed(1.0f)
            PlayerStatus.PLAYING -> playbackService?.pause(abandonAudioFocus = true, reinit = false)
            PlayerStatus.PAUSED, PlayerStatus.PREPARED -> playbackService?.resume()
            PlayerStatus.PREPARING -> playbackService!!.isStartWhenPrepared = !playbackService!!.isStartWhenPrepared
            PlayerStatus.INITIALIZED -> {
                if (playbackService != null) playbackService!!.isStartWhenPrepared = true
                playbackService?.prepare()
            }
            else -> {
                PlaybackServiceStarter(activity, media!!).callEvenIfRunning(true).start()
                Log.w(TAG, "Play/Pause button was pressed and PlaybackService state was unknown")
            }
        }
    }

    fun getMedia(): Playable? {
        if (media == null && playbackService != null) media = playbackService!!.mPlayerInfo.playable
        if (media == null) media = PlaybackPreferences.createInstanceFromPreferences(activity)

        return media
    }

    fun seekTo(time: Int) {
        if (playbackService != null) playbackService!!.seekTo(time)
        else {
//            val media = getMedia()
            if (media == null) return
            PlaybackServiceStarter(activity, media!!).start()
            if (media is FeedMedia) {
                media!!.setPosition(time)
                DBWriter.persistFeedItem((media as FeedMedia).item)
                EventBus.getDefault().post(PlaybackPositionEvent(time, media!!.getDuration()))
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackController"

        private var playbackService: PlaybackService? = null
        private var media: Playable? = null

        val sleepTimerTimeLeft: Long
            get() = playbackService?.sleepTimerTimeLeft ?: Playable.INVALID_TIME.toLong()

        val audioTracks: List<String>
            get() {
                if (playbackService?.audioTracks.isNullOrEmpty()) return emptyList()
                return playbackService!!.audioTracks.filterNotNull().map { it }
            }

        val selectedAudioTrack: Int
            get() = playbackService?.selectedAudioTrack?: -1

        val videoSize: Pair<Int, Int>?
            get() = playbackService?.videoSize

        fun isPlaybackServiceReady() : Boolean {
            return playbackService != null && playbackService!!.isServiceReady()
        }

        fun speedForward(speed: Float) {
            playbackService?.speedForward(speed)
        }

        fun fallbackSpeed(speed: Float) {
            if (playbackService != null) {
                when (MediaPlayerBase.status) {
                    PlayerStatus.PLAYING -> {
                        MediaPlayerBase.status = PlayerStatus.FALLBACK
                        playbackService!!.fallbackSpeed(speed)
                    }
                    PlayerStatus.FALLBACK -> {
                        MediaPlayerBase.status = PlayerStatus.PLAYING
                        playbackService!!.fallbackSpeed(speed)
                    }
                    else -> {}
                }
            }
        }

        fun setSkipSilence(skipSilence: Boolean) {
            playbackService?.skipSilence(skipSilence)
        }

        fun setAudioTrack(track: Int) {
            playbackService?.setAudioTrack(track)
        }

        fun notifyVideoSurfaceAbandoned() {
            playbackService?.notifyVideoSurfaceAbandoned()
        }

        fun setVideoSurface(holder: SurfaceHolder?) {
            playbackService?.setVideoSurface(holder)
        }

        fun setPlaybackSpeed(speed: Float, codeArray: BooleanArray? = null) {
            if (playbackService != null) playbackService!!.setSpeed(speed, codeArray)
            else {
                UserPreferences.setPlaybackSpeed(speed)
                EventBus.getDefault().post(SpeedChangedEvent(speed))
            }
        }

        fun disableSleepTimer() {
            playbackService?.disableSleepTimer()
        }

        fun extendSleepTimer(extendTime: Long) {
            val timeLeft = sleepTimerTimeLeft
            if (playbackService != null && timeLeft != Playable.INVALID_TIME.toLong()) setSleepTimer(timeLeft + extendTime)
        }

        fun setSleepTimer(time: Long) {
            playbackService?.setSleepTimer(time)
        }

        fun sleepTimerActive(): Boolean {
            return playbackService?.sleepTimerActive() ?: false
        }

    }
}
