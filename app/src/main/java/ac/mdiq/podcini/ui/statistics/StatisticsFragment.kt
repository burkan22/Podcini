package ac.mdiq.podcini.ui.statistics


import ac.mdiq.podcini.R
import ac.mdiq.podcini.databinding.PagerFragmentBinding
import ac.mdiq.podcini.storage.DBWriter
import ac.mdiq.podcini.ui.activity.MainActivity
import ac.mdiq.podcini.ui.fragment.PagedToolbarFragment
import ac.mdiq.podcini.ui.dialog.ConfirmationDialog
import ac.mdiq.podcini.ui.statistics.downloads.DownloadStatisticsFragment
import ac.mdiq.podcini.ui.statistics.subscriptions.SubscriptionStatisticsFragment
import ac.mdiq.podcini.ui.statistics.years.YearsStatisticsFragment
import ac.mdiq.podcini.util.event.StatisticsEvent
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus

/**
 * Displays the 'statistics' screen
 */
class StatisticsFragment : PagedToolbarFragment() {
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var toolbar: MaterialToolbar

    private var _binding: PagerFragmentBinding? = null
    private val binding get() = _binding!!

    @OptIn(UnstableApi::class) override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        super.onCreateView(inflater, container, savedInstanceState)
        setHasOptionsMenu(true)
        _binding = PagerFragmentBinding.inflate(inflater)
        viewPager = binding.viewpager
        toolbar = binding.toolbar
        toolbar.title = getString(R.string.statistics_label)
        toolbar.inflateMenu(R.menu.statistics)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        (activity as MainActivity).setupToolbarToggle(toolbar, false)

        viewPager.adapter = StatisticsPagerAdapter(this)
        // Give the TabLayout the ViewPager
        tabLayout = binding.slidingTabs
        super.setupPagedToolbar(toolbar, viewPager)

        TabLayoutMediator(tabLayout, viewPager) { tab: TabLayout.Tab, position: Int ->
            when (position) {
                POS_SUBSCRIPTIONS -> tab.setText(R.string.subscriptions_label)
                POS_YEARS -> tab.setText(R.string.years_statistics_label)
                POS_SPACE_TAKEN -> tab.setText(R.string.downloads_label)
                else -> {}
            }
        }.attach()
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @UnstableApi override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.statistics_reset) {
            confirmResetStatistics()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @UnstableApi private fun confirmResetStatistics() {
        val conDialog: ConfirmationDialog = object : ConfirmationDialog(
            requireContext(),
            R.string.statistics_reset_data,
            R.string.statistics_reset_data_msg) {
            override fun onConfirmButtonPressed(dialog: DialogInterface) {
                dialog.dismiss()
                doResetStatistics()
            }
        }
        conDialog.createNewDialog().show()
    }

    @UnstableApi private fun doResetStatistics() {
        requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_INCLUDE_MARKED_PLAYED, false)
            .putLong(PREF_FILTER_FROM, 0)
            .putLong(PREF_FILTER_TO, Long.MAX_VALUE)
            .apply()

        val disposable = Completable.fromFuture(DBWriter.resetStatistics())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ EventBus.getDefault().post(StatisticsEvent()) },
                { error: Throwable? -> Log.e(TAG, Log.getStackTraceString(error)) })
    }

    class StatisticsPagerAdapter internal constructor(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                POS_SUBSCRIPTIONS -> SubscriptionStatisticsFragment()
                POS_YEARS -> YearsStatisticsFragment()
                POS_SPACE_TAKEN -> DownloadStatisticsFragment()
                else -> DownloadStatisticsFragment()
            }
        }

        override fun getItemCount(): Int {
            return TOTAL_COUNT
        }
    }

    companion object {
        const val TAG: String = "StatisticsFragment"
        const val PREF_NAME: String = "StatisticsActivityPrefs"
        const val PREF_INCLUDE_MARKED_PLAYED: String = "countAll"
        const val PREF_FILTER_FROM: String = "filterFrom"
        const val PREF_FILTER_TO: String = "filterTo"


        private const val POS_SUBSCRIPTIONS = 0
        private const val POS_YEARS = 1
        private const val POS_SPACE_TAKEN = 2
        private const val TOTAL_COUNT = 3
    }
}
