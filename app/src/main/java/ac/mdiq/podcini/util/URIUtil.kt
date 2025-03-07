package ac.mdiq.podcini.util

import android.util.Log
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * Utility methods for dealing with URL encoding.
 */
object URIUtil {
    private const val TAG = "URIUtil"

    @JvmStatic
    fun getURIFromRequestUrl(source: String): URI {
        // try without encoding the URI
        try {
            return URI(source)
        } catch (e: URISyntaxException) {
            Logd(TAG, "Source is not encoded, encoding now")
        }
        try {
            val url = URL(source)
            return URI(url.protocol, url.userInfo, url.host, url.port, url.path, url.query, url.ref)
        } catch (e: MalformedURLException) {
            Log.d(TAG, "source: $source")
            throw IllegalArgumentException(e)
        } catch (e: URISyntaxException) {
            Log.d(TAG, "source: $source")
            throw IllegalArgumentException(e)
        }
    }
}
