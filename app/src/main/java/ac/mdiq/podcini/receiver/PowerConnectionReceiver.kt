package ac.mdiq.podcini.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import ac.mdiq.podcini.util.config.ClientConfigurator
import ac.mdiq.podcini.storage.DBTasks
import ac.mdiq.podcini.net.download.serviceinterface.DownloadServiceInterface
import ac.mdiq.podcini.preferences.UserPreferences.isEnableAutodownloadOnBattery

// modified from http://developer.android.com/training/monitoring-device-state/battery-monitoring.html
// and ConnectivityActionReceiver.java
// Updated based on http://stackoverflow.com/questions/20833241/android-charge-intent-has-no-extra-data
// Since the intent doesn't have the EXTRA_STATUS like the android.com article says it does
// (though it used to)
class PowerConnectionReceiver : BroadcastReceiver() {
    @UnstableApi override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        Log.d(TAG, "charging intent: $action")

        ClientConfigurator.initialize(context)
        if (Intent.ACTION_POWER_CONNECTED == action) {
            Log.d(TAG, "charging, starting auto-download")
            // we're plugged in, this is a great time to auto-download if everything else is
            // right. So, even if the user allows auto-dl on battery, let's still start
            // downloading now. They shouldn't mind.
            // autodownloadUndownloadedItems will make sure we're on the right wifi networks,
            // etc... so we don't have to worry about it.
            DBTasks.autodownloadUndownloadedItems(context)
        } else {
            // if we're not supposed to be auto-downloading when we're not charging, stop it
            if (!isEnableAutodownloadOnBattery) {
                Log.d(TAG, "not charging anymore, canceling auto-download")
                DownloadServiceInterface.get()?.cancelAll(context)
            } else Log.d(TAG, "not charging anymore, but the user allows auto-download when on battery so we'll keep going")
        }
    }

    companion object {
        private const val TAG = "PowerConnectionReceiver"
    }
}
