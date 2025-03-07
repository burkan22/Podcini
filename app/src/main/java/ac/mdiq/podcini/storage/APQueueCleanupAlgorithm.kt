package ac.mdiq.podcini.storage

import android.content.Context
import android.util.Log
import ac.mdiq.podcini.storage.DBReader.getEpisodes
import ac.mdiq.podcini.storage.model.feed.FeedItem
import ac.mdiq.podcini.storage.model.feed.FeedItemFilter
import ac.mdiq.podcini.storage.model.feed.SortOrder
import java.util.*
import java.util.concurrent.ExecutionException

/**
 * A cleanup algorithm that removes any item that isn't in the queue and isn't a favorite
 * but only if space is needed.
 */
class APQueueCleanupAlgorithm : EpisodeCleanupAlgorithm() {
    /**
     * @return the number of episodes that *could* be cleaned up, if needed
     */
    override fun getReclaimableItems(): Int {
        return candidates.size
    }

    public override fun performCleanup(context: Context, numberOfEpisodesToDelete: Int): Int {
        var candidates = candidates

        // in the absence of better data, we'll sort by item publication date
        candidates = candidates.sortedWith { lhs: FeedItem, rhs: FeedItem ->
            var l = lhs.getPubDate()
            var r = rhs.getPubDate()

            if (l == null) l = Date()
            if (r == null) r = Date()

            l.compareTo(r)
        }

        val delete = if (candidates.size > numberOfEpisodesToDelete) candidates.subList(0, numberOfEpisodesToDelete) else candidates

        for (item in delete) {
            try {
                DBWriter.deleteFeedMediaOfItem(context!!, item.media!!.id).get()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            } catch (e: ExecutionException) {
                e.printStackTrace()
            }
        }

        val counter = delete.size

        Log.i(TAG, String.format(Locale.US, "Auto-delete deleted %d episodes (%d requested)", counter, numberOfEpisodesToDelete))

        return counter
    }

    private val candidates: List<FeedItem>
        get() {
            val candidates: MutableList<FeedItem> = ArrayList()
            val downloadedItems = getEpisodes(0, Int.MAX_VALUE,
                FeedItemFilter(FeedItemFilter.DOWNLOADED), SortOrder.DATE_NEW_OLD)
            for (item in downloadedItems) {
                if (item.hasMedia() && item.media!!.isDownloaded() && !item.isTagged(FeedItem.TAG_QUEUE) && !item.isTagged(FeedItem.TAG_FAVORITE))
                    candidates.add(item)
            }
            return candidates
        }

    public override fun getDefaultCleanupParameter(): Int {
        return getNumEpisodesToCleanup(0)
    }

    companion object {
        private const val TAG = "APQueueCleanupAlgorithm"
    }
}
