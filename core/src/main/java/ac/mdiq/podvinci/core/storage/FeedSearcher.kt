package ac.mdiq.podvinci.core.storage

import ac.mdiq.podvinci.core.storage.DBTasks.searchFeedItems
import ac.mdiq.podvinci.model.feed.Feed
import ac.mdiq.podvinci.model.feed.FeedItem
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ExecutionException

/**
 * Performs search on Feeds and FeedItems.
 */
@UnstableApi
object FeedSearcher {
    fun searchFeedItems(query: String, selectedFeed: Long): List<FeedItem> {
        try {
            val itemSearchTask = searchFeedItems(selectedFeed, query)
            itemSearchTask.run()
            return itemSearchTask.get()
        } catch (e: ExecutionException) {
            e.printStackTrace()
            return emptyList()
        } catch (e: InterruptedException) {
            e.printStackTrace()
            return emptyList()
        }
    }

    fun searchFeeds(query: String): List<Feed> {
        try {
            val feedSearchTask = DBTasks.searchFeeds(query)
            feedSearchTask.run()
            return feedSearchTask.get()
        } catch (e: ExecutionException) {
            e.printStackTrace()
            return emptyList()
        } catch (e: InterruptedException) {
            e.printStackTrace()
            return emptyList()
        }
    }
}
