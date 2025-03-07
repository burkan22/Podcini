package ac.mdiq.podcini.preferences.fragments

import ac.mdiq.podcini.R
import ac.mdiq.podcini.ui.activity.BugReportActivity
import ac.mdiq.podcini.ui.activity.PreferenceActivity
import ac.mdiq.podcini.ui.activity.PreferenceActivity.Companion.getTitleOfPage
import ac.mdiq.podcini.preferences.fragments.about.AboutFragment
import ac.mdiq.podcini.util.IntentUtils.openInBrowser
import ac.mdiq.podcini.util.Logd
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.bytehamster.lib.preferencesearch.SearchPreference

class MainPreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        Logd("MainPreferencesFragment", "onCreatePreferences")

        addPreferencesFromResource(R.xml.preferences)
        setupMainScreen()
        setupSearch()

        // If you are writing a spin-off, please update the details on screens like "About" and "Report bug"
        // and afterwards remove the following lines. Please keep in mind that Podcini is licensed under the GPL.
        // This means that your application needs to be open-source under the GPL, too.
        // It must also include a prominent copyright notice.
        val packageHash = requireContext().packageName.hashCode()
        when {
            packageHash != -418727741 && packageHash != 296705384 -> {
                findPreference<Preference>(PREF_CATEGORY_PROJECT)!!.isVisible = false
                val copyrightNotice = Preference(requireContext())
                copyrightNotice.setIcon(R.drawable.ic_info_white)
                copyrightNotice.icon!!.mutate().colorFilter = PorterDuffColorFilter(-0x340000, PorterDuff.Mode.MULTIPLY)
                copyrightNotice.summary = ("This application is based on Podcini."
                        + " The Podcini team does NOT provide support for this unofficial version."
                        + " If you can read this message, the developers of this modification"
                        + " violate the GNU General Public License (GPL).")
                findPreference<Preference>(PREF_CATEGORY_PROJECT)!!.parent!!.addPreference(copyrightNotice)
            }
            packageHash == 296705384 -> {
                val debugNotice = Preference(requireContext())
                debugNotice.setIcon(R.drawable.ic_info_white)
                debugNotice.icon!!.mutate().colorFilter = PorterDuffColorFilter(-0x340000, PorterDuff.Mode.MULTIPLY)
                debugNotice.order = -1
                debugNotice.summary = "This is a development version of Podcini and not meant for daily use"
                findPreference<Preference>(PREF_CATEGORY_PROJECT)!!.parent!!.addPreference(debugNotice)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (activity as PreferenceActivity).supportActionBar!!.setTitle(R.string.settings_label)
    }

    @SuppressLint("CommitTransaction")
    private fun setupMainScreen() {
        findPreference<Preference>(PREF_SCREEN_USER_INTERFACE)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            (activity as PreferenceActivity).openScreen(R.xml.preferences_user_interface)
            true
        }
        findPreference<Preference>(PREF_SCREEN_PLAYBACK)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            (activity as PreferenceActivity).openScreen(R.xml.preferences_playback)
            true
        }
        findPreference<Preference>(PREF_SCREEN_DOWNLOADS)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            (activity as PreferenceActivity).openScreen(R.xml.preferences_downloads)
            true
        }
        findPreference<Preference>(PREF_SCREEN_SYNCHRONIZATION)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            (activity as PreferenceActivity).openScreen(R.xml.preferences_synchronization)
            true
        }
        findPreference<Preference>(PREF_SCREEN_IMPORT_EXPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            (activity as PreferenceActivity).openScreen(R.xml.preferences_import_export)
            true
        }
        findPreference<Preference>(PREF_NOTIFICATION)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            (activity as PreferenceActivity).openScreen(R.xml.preferences_notifications)
            true
        }
        findPreference<Preference>(PREF_ABOUT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, AboutFragment())
                .addToBackStack(getString(R.string.about_pref))
                .commit()
            true
        }
        findPreference<Preference>(PREF_DOCUMENTATION)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            openInBrowser(requireContext(), "https://github.com/XilinJia/Podcini")
            true
        }
        findPreference<Preference>(PREF_VIEW_FORUM)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            openInBrowser(requireContext(), "https://github.com/XilinJia/Podcini/discussions")
            true
        }
        findPreference<Preference>(PREF_CONTRIBUTE)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            openInBrowser(requireContext(), "https://github.com/XilinJia/Podcini")
            true
        }
        findPreference<Preference>(PREF_SEND_BUG_REPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivity(Intent(activity, BugReportActivity::class.java))
            true
        }
    }

//    private val localizedWebsiteLink: String
//        get() {
//            try {
//                requireContext().assets.open("website-languages.txt").use { `is` ->
//                    val languages = IOUtils.toString(`is`, StandardCharsets.UTF_8.name()).split("\n".toRegex())
//                        .dropLastWhile { it.isEmpty() }
//                        .toTypedArray()
//                    val deviceLanguage = Locale.getDefault().language
//                    return if (ArrayUtils.contains(languages, deviceLanguage) && "en" != deviceLanguage) {
//                        "https://podcini.org/$deviceLanguage"
//                    } else {
//                        "https://podcini.org"
//                    }
//                }
//            } catch (e: IOException) {
//                throw RuntimeException(e)
//            }
//        }

    private fun setupSearch() {
        val searchPreference = findPreference<SearchPreference>("searchPreference")
        val config = searchPreference!!.searchConfiguration
        config.setActivity((activity as AppCompatActivity))
        config.setFragmentContainerViewId(R.id.settingsContainer)
        config.setBreadcrumbsEnabled(true)

        config.index(R.xml.preferences_user_interface).addBreadcrumb(getTitleOfPage(R.xml.preferences_user_interface))
        config.index(R.xml.preferences_playback).addBreadcrumb(getTitleOfPage(R.xml.preferences_playback))
        config.index(R.xml.preferences_downloads).addBreadcrumb(getTitleOfPage(R.xml.preferences_downloads))
        config.index(R.xml.preferences_import_export).addBreadcrumb(getTitleOfPage(R.xml.preferences_import_export))
        config.index(R.xml.preferences_autodownload)
            .addBreadcrumb(getTitleOfPage(R.xml.preferences_downloads))
            .addBreadcrumb(R.string.automation)
            .addBreadcrumb(getTitleOfPage(R.xml.preferences_autodownload))
        config.index(R.xml.preferences_synchronization).addBreadcrumb(getTitleOfPage(R.xml.preferences_synchronization))
        config.index(R.xml.preferences_notifications).addBreadcrumb(getTitleOfPage(R.xml.preferences_notifications))
        config.index(R.xml.feed_settings).addBreadcrumb(getTitleOfPage(R.xml.feed_settings))
        config.index(R.xml.preferences_swipe)
            .addBreadcrumb(getTitleOfPage(R.xml.preferences_user_interface))
            .addBreadcrumb(getTitleOfPage(R.xml.preferences_swipe))
    }

    companion object {
        private const val PREF_SCREEN_USER_INTERFACE = "prefScreenInterface"
        private const val PREF_SCREEN_PLAYBACK = "prefScreenPlayback"
        private const val PREF_SCREEN_DOWNLOADS = "prefScreenDownloads"
        private const val PREF_SCREEN_IMPORT_EXPORT = "prefScreenImportExport"
        private const val PREF_SCREEN_SYNCHRONIZATION = "prefScreenSynchronization"
        private const val PREF_DOCUMENTATION = "prefDocumentation"
        private const val PREF_VIEW_FORUM = "prefViewForum"
        private const val PREF_SEND_BUG_REPORT = "prefSendBugReport"
        private const val PREF_CATEGORY_PROJECT = "project"
        private const val PREF_ABOUT = "prefAbout"
        private const val PREF_NOTIFICATION = "notifications"
        private const val PREF_CONTRIBUTE = "prefContribute"
    }
}
