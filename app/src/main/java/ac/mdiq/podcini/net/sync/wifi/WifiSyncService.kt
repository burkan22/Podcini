package ac.mdiq.podcini.net.sync.wifi

import ac.mdiq.podcini.R
import ac.mdiq.podcini.net.sync.LockingAsyncExecutor
import ac.mdiq.podcini.net.sync.LockingAsyncExecutor.executeLockedAsync
import ac.mdiq.podcini.net.sync.SyncService
import ac.mdiq.podcini.net.sync.SynchronizationSettings
import ac.mdiq.podcini.net.sync.model.*
import ac.mdiq.podcini.net.sync.model.EpisodeAction.Companion.readFromJsonObject
import ac.mdiq.podcini.storage.DBReader.getEpisodes
import ac.mdiq.podcini.storage.DBReader.getFeedItemByGuidOrEpisodeUrl
import ac.mdiq.podcini.storage.DBReader.getFeedMedia
import ac.mdiq.podcini.storage.DBWriter.persistFeedMediaPlaybackInfo
import ac.mdiq.podcini.storage.model.feed.FeedItem
import ac.mdiq.podcini.storage.model.feed.FeedItemFilter
import ac.mdiq.podcini.storage.model.feed.SortOrder
import ac.mdiq.podcini.util.FeedItemUtil.hasAlmostEnded
import ac.mdiq.podcini.util.event.SyncServiceEvent
import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat.getString
import androidx.media3.common.util.UnstableApi
import androidx.work.*
import org.apache.commons.lang3.StringUtils
import org.greenrobot.eventbus.EventBus
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

@UnstableApi class WifiSyncService(val context: Context, params: WorkerParameters)  : SyncService(context, params), ISyncService {

    var loginFail = false

    override fun doWork(): Result {
        Log.d(TAG, "doWork() called")

        SynchronizationSettings.updateLastSynchronizationAttempt()
        setCurrentlyActive(true)

        login()

        if (socket != null && !loginFail) {
            if (isGuest) {
                Thread.sleep(1000)
//                TODO: not using lastSync
                val lastSync = SynchronizationSettings.lastEpisodeActionSynchronizationTimestamp
                val newTimeStamp = pushEpisodeActions(this, 0L, System.currentTimeMillis())
                SynchronizationSettings.setLastEpisodeActionSynchronizationAttemptTimestamp(newTimeStamp)
                EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "50"))
                sendToPeer("AllSent", "AllSent")

                var receivedBye = false
                while (!receivedBye) {
                    try {
                        receivedBye = receiveFromPeer()
                    } catch (e: SocketTimeoutException) {
                        Log.e("Guest", getString(context, R.string.sync_error_host_not_respond))
                        logout()
                        EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "100"))
                        EventBus.getDefault().postSticky(SyncServiceEvent(R.string.sync_status_error, getString(context, R.string.sync_error_host_not_respond)))
                        SynchronizationSettings.setLastSynchronizationAttemptSuccess(false)
                        return Result.failure()
                    }
                }
            } else {
                var receivedBye = false
                while (!receivedBye) {
                    try {
                        receivedBye = receiveFromPeer()
                    } catch (e: SocketTimeoutException) {
                        Log.e("Host", getString(context, R.string.sync_error_guest_not_respond))
                        logout()
                        EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "100"))
                        EventBus.getDefault().postSticky(SyncServiceEvent(R.string.sync_status_error, getString(context, R.string.sync_error_guest_not_respond)))
                        SynchronizationSettings.setLastSynchronizationAttemptSuccess(false)
                        return Result.failure()
                    }
                }
                EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "50"))
                //                TODO: not using lastSync
                val lastSync = SynchronizationSettings.lastEpisodeActionSynchronizationTimestamp
                val newTimeStamp = pushEpisodeActions(this, 0L, System.currentTimeMillis())
                SynchronizationSettings.setLastEpisodeActionSynchronizationAttemptTimestamp(newTimeStamp)
                sendToPeer("AllSent", "AllSent")
            }
        } else {
            logout()
            EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "100"))
            EventBus.getDefault().postSticky(SyncServiceEvent(R.string.sync_status_error, "Login failure"))
            SynchronizationSettings.setLastSynchronizationAttemptSuccess(false)
            return Result.failure()
        }

        logout()
        EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "100"))
        EventBus.getDefault().postSticky(SyncServiceEvent(R.string.sync_status_success))
        SynchronizationSettings.setLastSynchronizationAttemptSuccess(true)
        return Result.success()
    }

    private var socket: Socket? = null

    @OptIn(UnstableApi::class) override fun login() {
        Log.d(TAG, "serverIp: $hostIp serverPort: $hostPort $isGuest")
        EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "2"))
        if (!isPortInUse(hostPort)) {
            if (isGuest) {
                val maxTries = 120
                var numTries = 0
                while (numTries < maxTries) {
                    try {
                        socket = Socket(hostIp, hostPort)
                        break
                    } catch (e: ConnectException) {
                        Thread.sleep(1000)
                    }
                    numTries++
                }
                if (numTries >= maxTries) loginFail = true
                if (socket != null) {
                    sendToPeer("Hello", "Hello, Server!")
                    receiveFromPeer()
                }
            } else {
                try {
                    if (serverSocket == null) serverSocket = ServerSocket(hostPort)
                    serverSocket!!.soTimeout = 120000
                    try {
                        socket = serverSocket!!.accept()
                        while (true) {
                            Log.d(TAG, "waiting for guest message")
                            try {
                                receiveFromPeer()
                                sendToPeer("Hello", "Hello, Client")
                                break
                            } catch (e: SocketTimeoutException) {
                                Log.e("Server", "Guest not responding in 120 seconds, giving up")
                                loginFail = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Server", "No guest connecing in 120 seconds, giving up")
                        loginFail = true
                    }
                } catch (e: BindException) {
                    Log.e("Server", "Failed to start server: Port $hostPort already in use")
                    loginFail = true
                }
            }
        } else {
            Log.w(TAG, "port $hostPort in use, ignored")
            loginFail = true
        }
        EventBus.getDefault().post(SyncServiceEvent(R.string.sync_status_in_progress, "5"))
    }

    @OptIn(UnstableApi::class) private fun isPortInUse(port: Int): Boolean {
        val command = "netstat -tlnp"
        val process = Runtime.getRuntime().exec(command)
        val output = process.inputStream.bufferedReader().use { it.readText() }
//        Log.d(TAG, "isPortInUse: $output")
        return output.contains(":$port") // Check if output contains the port
    }

    private fun sendToPeer(messageType: String, message: String) {
        val writer = PrintWriter(socket!!.getOutputStream(), true)
        writer.println("$messageType|$message")
    }

    @Throws(SocketTimeoutException::class)
    private fun receiveFromPeer() : Boolean {
        val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
        val message: String?
        socket!!.soTimeout = 120000
        try {
            message = reader.readLine()
        } catch (e: SocketTimeoutException) {
            throw e
        }
        if (message != null) {
            val parts = message.split("|")
            if (parts.size == 2) {
                val messageType = parts[0]
                val messageData = parts[1]
                // Process the message based on the type
                when (messageType) {
                    "Hello" -> Log.d(TAG, "Received Hello message: $messageData")
                    "EpisodeActions" -> {
                        val remoteActions = mutableListOf<EpisodeAction>()
                        val jsonArray = JSONArray(messageData)
                        for (i in 0 until jsonArray.length()) {
                            val jsonAction = jsonArray.getJSONObject(i)

//                            TODO: this conversion shouldn't be needed, check about the uploader
//                            val timeStr = jsonAction.getString("timestamp")
//                            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
//                            val date = format.parse(timeStr)
//                            jsonAction.put("timestamp", date?.time?:0L)

                            Log.d(TAG, "Received EpisodeActions message: $i $jsonAction")
                            val action = readFromJsonObject(jsonAction)
                            if (action != null) remoteActions.add(action)
                        }
                        processEpisodeActions(remoteActions)
                    }
                    "AllSent" -> {
                        Log.d(TAG, "Received AllSent message: $messageData")
                        return true
                    }
                    else -> Log.d(TAG, "Received unknown message: $messageData")
                }
            }
        }
        return false
    }

    @Throws(SyncServiceException::class)
    override fun getSubscriptionChanges(lastSync: Long): SubscriptionChanges? {
        Log.d(TAG, "getSubscriptionChanges does nothing")
        return null
    }

    @Throws(SyncServiceException::class)
    override fun uploadSubscriptionChanges(added: List<String>, removed: List<String>): UploadChangesResponse? {
        Log.d(TAG, "uploadSubscriptionChanges does nothing")
        return null
    }

    @Throws(SyncServiceException::class)
    override fun getEpisodeActionChanges(timestamp: Long): EpisodeActionChanges? {
        Log.d(TAG, "getEpisodeActionChanges does nothing")
        return null
    }

    override fun pushEpisodeActions(syncServiceImpl: ISyncService, lastSync: Long, newTimeStamp_: Long): Long {
        var newTimeStamp = newTimeStamp_
        EventBus.getDefault().postSticky(SyncServiceEvent(R.string.sync_status_episodes_upload))
        val queuedEpisodeActions: MutableList<EpisodeAction> = synchronizationQueueStorage.queuedEpisodeActions
        Log.d(TAG, "pushEpisodeActions queuedEpisodeActions: ${queuedEpisodeActions.size}")

        if (lastSync == 0L) {
            EventBus.getDefault().postSticky(SyncServiceEvent(R.string.sync_status_upload_played))
//            only push downloaded items
            val pausedItems = getEpisodes(0, Int.MAX_VALUE, FeedItemFilter(FeedItemFilter.PAUSED), SortOrder.DATE_NEW_OLD)
            val readItems = getEpisodes(0, Int.MAX_VALUE, FeedItemFilter(FeedItemFilter.PLAYED), SortOrder.DATE_NEW_OLD)
            val comItems = mutableSetOf<FeedItem>()
            comItems.addAll(pausedItems)
            comItems.addAll(readItems)
            Log.d(TAG, "First sync. Upload state for all " + comItems.size + " played episodes")
            for (item in comItems) {
                val media = item.media ?: continue
                val played = EpisodeAction.Builder(item, EpisodeAction.PLAY)
                    .timestamp(Date(media.getLastPlayedTime()))
                    .started(media.getPosition() / 1000)
                    .position(media.getPosition() / 1000)
                    .total(media.getDuration() / 1000)
                    .build()
                queuedEpisodeActions.add(played)
            }
        }
        if (queuedEpisodeActions.isNotEmpty()) {
            LockingAsyncExecutor.lock.lock()
            try {
                Log.d(TAG, "Uploading ${queuedEpisodeActions.size} actions: ${StringUtils.join(queuedEpisodeActions, ", ")}")
                val postResponse = uploadEpisodeActions(queuedEpisodeActions)
                newTimeStamp = postResponse.timestamp
                Log.d(TAG, "Upload episode response: $postResponse")
                synchronizationQueueStorage.clearEpisodeActionQueue()
            } finally {
                LockingAsyncExecutor.lock.unlock()
            }
        }
        return newTimeStamp
    }

    @Throws(SyncServiceException::class)
    override fun uploadEpisodeActions(queuedEpisodeActions: List<EpisodeAction>): UploadChangesResponse {
//        Log.d(TAG, "uploadEpisodeActions called")
        var i = 0
        while (i < queuedEpisodeActions.size) {
            uploadEpisodeActionsPartial(queuedEpisodeActions, i, min(queuedEpisodeActions.size.toDouble(), (i + UPLOAD_BULK_SIZE).toDouble()).toInt())
            i += UPLOAD_BULK_SIZE
            Thread.sleep(1000)
        }
        return WifiEpisodeActionPostResponse(System.currentTimeMillis() / 1000)
    }

    @Throws(SyncServiceException::class)
    private fun uploadEpisodeActionsPartial(queuedEpisodeActions: List<EpisodeAction>, from: Int, to: Int) {
//        Log.d(TAG, "uploadEpisodeActionsPartial called")
        try {
            val list = JSONArray()
            for (i in from until to) {
                val episodeAction = queuedEpisodeActions[i]
                val obj = episodeAction.writeToJsonObject()
                if (obj != null) {
                    Log.d(TAG, "sending EpisodeAction: $obj")
                    list.put(obj)
                }
            }
            sendToPeer("EpisodeActions", list.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            throw SyncServiceException(e)
        }
    }

    override fun processEpisodeAction(action: EpisodeAction): Pair<Long, FeedItem>? {
        val guid = if (isValidGuid(action.guid)) action.guid else null
        val feedItem = getFeedItemByGuidOrEpisodeUrl(guid, action.episode?:"")
        if (feedItem == null) {
            Log.i(TAG, "Unknown feed item: $action")
            return null
        }
        if (feedItem.media == null) {
            Log.i(TAG, "Feed item has no media: $action")
            return null
        }
        feedItem.media = getFeedMedia(feedItem.media!!.id)
        var idRemove = 0L
        Log.d(TAG, "processEpisodeAction ${feedItem.media!!.getLastPlayedTime()} ${(action.timestamp?.time?:0L)} ${action.position} ${feedItem.title}")
        if (feedItem.media!!.getLastPlayedTime() < (action.timestamp?.time?:0L)) {
            feedItem.media!!.setPosition(action.position * 1000)
            feedItem.media!!.setLastPlayedTime(action.timestamp!!.time)
            if (hasAlmostEnded(feedItem.media!!)) {
                Log.d(TAG, "Marking as played")
                feedItem.setPlayed(true)
                feedItem.media!!.setPosition(0)
                idRemove = feedItem.id
            } else Log.d(TAG, "Setting position")
            persistFeedMediaPlaybackInfo(feedItem.media)
        } else Log.d(TAG, "local is newer, no change")

        return Pair(idRemove, feedItem)
    }

    override fun logout() {
        socket?.close()
    }

    private class WifiEpisodeActionPostResponse(epochSecond: Long) : UploadChangesResponse(epochSecond)

    companion object {
        const val TAG: String = "WifiSyncService"
        private const val WORK_ID_SYNC = "SyncServiceWorkId"
        private const val UPLOAD_BULK_SIZE = 30

        var serverSocket:  ServerSocket? = null
        var isGuest = false
        var hostIp : String = ""
        var hostPort: Int = 54628

        private var isCurrentlyActive = false
        internal fun setCurrentlyActive(active: Boolean) {
            isCurrentlyActive = active
        }

        fun startInstantSync(context: Context, hostPort_: Int = 54628, hostIp_: String="", isGuest_: Boolean = false) {
            hostIp = hostIp_
            isGuest = isGuest_
            hostPort = hostPort_
            executeLockedAsync {
                SynchronizationSettings.resetTimestamps()
                val builder: OneTimeWorkRequest.Builder = OneTimeWorkRequest.Builder(WifiSyncService::class.java)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)

                // Give it some time, so other possible actions can be queued.
                builder.setInitialDelay(20L, TimeUnit.SECONDS)
                EventBus.getDefault().postSticky(SyncServiceEvent(R.string.sync_status_started))

                val workRequest: OneTimeWorkRequest = builder.setInitialDelay(0L, TimeUnit.SECONDS).build()
                WorkManager.getInstance(context).enqueueUniqueWork(hostIp_, ExistingWorkPolicy.REPLACE, workRequest)
            }
        }
    }
}