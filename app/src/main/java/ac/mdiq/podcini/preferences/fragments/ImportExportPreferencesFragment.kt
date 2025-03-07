package ac.mdiq.podcini.preferences.fragments

import ac.mdiq.podcini.PodciniApp.Companion.forceRestart
import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.DatabaseTransporter
import ac.mdiq.podcini.storage.PreferencesTransporter
import ac.mdiq.podcini.storage.asynctask.DocumentFileExportWorker
import ac.mdiq.podcini.storage.asynctask.ExportWorker
import ac.mdiq.podcini.storage.export.ExportWriter
import ac.mdiq.podcini.storage.export.favorites.FavoritesWriter
import ac.mdiq.podcini.storage.export.html.HtmlWriter
import ac.mdiq.podcini.storage.export.opml.OpmlWriter
import ac.mdiq.podcini.ui.activity.OpmlImportActivity
import ac.mdiq.podcini.ui.activity.PreferenceActivity
import android.app.Activity.RESULT_OK
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.annotation.StringRes
import androidx.core.app.ShareCompat.IntentBuilder
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ImportExportPreferencesFragment : PreferenceFragmentCompat() {

    private val chooseOpmlExportPathLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.chooseOpmlExportPathResult(result) }
    private val chooseHtmlExportPathLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.chooseHtmlExportPathResult(result) }
    private val chooseFavoritesExportPathLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.chooseFavoritesExportPathResult(result) }
    private val restoreDatabaseLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.restoreDatabaseResult(result) }
    private val backupDatabaseLauncher = registerForActivityResult<String, Uri>(BackupDatabase()) { uri: Uri? -> this.backupDatabaseResult(uri) }
    private val chooseOpmlImportPathLauncher = registerForActivityResult<String, Uri>(ActivityResultContracts.GetContent()) { uri: Uri? ->
        this.chooseOpmlImportPathResult(uri) }

    private val restorePreferencesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.restorePreferencesResult(result)
    }
    private val backupPreferencesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val data: Uri? = it.data?.data
            if (data != null) PreferencesTransporter.exportToDocument(data, requireContext())
        }
    }

    private var disposable: Disposable? = null
    private var progressDialog: ProgressDialog? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_import_export)
        setupStorageScreen()
        progressDialog = ProgressDialog(context)
        progressDialog!!.isIndeterminate = true
        progressDialog!!.setMessage(requireContext().getString(R.string.please_wait))
    }

    override fun onStart() {
        super.onStart()
        (activity as PreferenceActivity).supportActionBar!!.setTitle(R.string.import_export_pref)
    }

    override fun onStop() {
        super.onStop()
        disposable?.dispose()
    }

    private fun dateStampFilename(fname: String): String {
        return String.format(fname, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
    }

    private fun setupStorageScreen() {
        findPreference<Preference>(PREF_OPML_EXPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            openExportPathPicker(Export.OPML, chooseOpmlExportPathLauncher, OpmlWriter())
            true
        }
        findPreference<Preference>(PREF_HTML_EXPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            openExportPathPicker(Export.HTML, chooseHtmlExportPathLauncher, HtmlWriter())
            true
        }
        findPreference<Preference>(PREF_OPML_IMPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            try {
                chooseOpmlImportPathLauncher.launch("*/*")
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "No activity found. Should never happen...")
            }
            true
        }
        findPreference<Preference>(PREF_DATABASE_IMPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            importDatabase()
            true
        }
        findPreference<Preference>(PREF_DATABASE_EXPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            exportDatabase()
            true
        }
        findPreference<Preference>(PREF_PREFERENCES_IMPORT)?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            importPreferences()
            true
        }
        findPreference<Preference>(PREF_PREFERENCES_EXPORT)?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            exportPreferences()
            true
        }

        findPreference<Preference>(PREF_FAVORITE_EXPORT)!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            openExportPathPicker(Export.FAVORITES, chooseFavoritesExportPathLauncher, FavoritesWriter())
            true
        }
    }

    private fun exportWithWriter(exportWriter: ExportWriter, uri: Uri?, exportType: Export) {
        val context: Context? = activity
        progressDialog!!.show()
        if (uri == null) {
            val observable = ExportWorker(exportWriter, requireContext()).exportObservable()
            disposable = observable.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ output: File? ->
                    val fileUri = FileProvider.getUriForFile(context!!.applicationContext, context.getString(R.string.provider_authority), output!!)
                    showExportSuccessSnackbar(fileUri, exportType.contentType)
                }, { error: Throwable -> this.showExportErrorDialog(error) }, { progressDialog!!.dismiss() })
        } else {
            val worker = DocumentFileExportWorker(exportWriter, context!!, uri)
            disposable = worker.exportObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ output: DocumentFile? -> showExportSuccessSnackbar(output?.uri, exportType.contentType) },
                    { error: Throwable -> this.showExportErrorDialog(error) },
                    { progressDialog!!.dismiss() })
        }
    }

    private fun exportPreferences() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        backupPreferencesLauncher.launch(intent)
    }

    private fun importPreferences() {
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.preferences_import_label)
        builder.setMessage(R.string.preferences_import_warning)

        // add a button
        builder.setNegativeButton(R.string.no, null)
        builder.setPositiveButton(R.string.confirm_label) { _: DialogInterface?, _: Int ->
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            restorePreferencesLauncher.launch(intent)
        }

        // create and show the alert dialog
        builder.show()
    }

    private fun exportDatabase() {
        backupDatabaseLauncher.launch(dateStampFilename(DATABASE_EXPORT_FILENAME))
    }

    private fun importDatabase() {
        // setup the alert builder
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.database_import_label)
        builder.setMessage(R.string.database_import_warning)

        // add a button
        builder.setNegativeButton(R.string.no, null)
        builder.setPositiveButton(R.string.confirm_label) { _: DialogInterface?, _: Int ->
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.setType("*/*")
            restoreDatabaseLauncher.launch(intent)
        }

        // create and show the alert dialog
        builder.show()
    }

    private fun showDatabaseImportSuccessDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.successful_import_label)
        builder.setMessage(R.string.import_ok)
        builder.setCancelable(false)
        builder.setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int -> forceRestart() }
        builder.show()
    }

    private fun showExportSuccessSnackbar(uri: Uri?, mimeType: String?) {
        Snackbar.make(requireView(), R.string.export_success_title, Snackbar.LENGTH_LONG)
            .setAction(R.string.share_label) { IntentBuilder(requireContext()).setType(mimeType).addStream(uri!!).setChooserTitle(R.string.share_label).startChooser() }
            .show()
    }

    private fun showExportErrorDialog(error: Throwable) {
        progressDialog!!.dismiss()
        val alert = MaterialAlertDialogBuilder(requireContext())
        alert.setPositiveButton(android.R.string.ok) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
        alert.setTitle(R.string.export_error_label)
        alert.setMessage(error.message)
        alert.show()
    }

    private fun chooseOpmlExportPathResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK || result.data == null) return
        val uri = result.data!!.data
        exportWithWriter(OpmlWriter(), uri, Export.OPML)
    }

    private fun chooseHtmlExportPathResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK || result.data == null) return
        val uri = result.data!!.data
        exportWithWriter(HtmlWriter(), uri, Export.HTML)
    }

    private fun chooseFavoritesExportPathResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK || result.data == null) return
        val uri = result.data!!.data
        exportWithWriter(FavoritesWriter(), uri, Export.FAVORITES)
    }

    private fun restoreDatabaseResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK || result.data == null) return
        val uri = result.data!!.data
        progressDialog!!.show()
        disposable = Completable.fromAction { DatabaseTransporter.importBackup(uri, requireContext()) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                showDatabaseImportSuccessDialog()
                progressDialog!!.dismiss()
            }, { error: Throwable -> this.showExportErrorDialog(error) })
    }

    private fun restorePreferencesResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK || result.data?.data == null) return
        val uri = result.data!!.data!!
        progressDialog!!.show()
        disposable = Completable.fromAction { PreferencesTransporter.importBackup(uri, requireContext()) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                showDatabaseImportSuccessDialog()
                progressDialog!!.dismiss()
            }, { error: Throwable -> this.showExportErrorDialog(error) })
    }

    private fun backupDatabaseResult(uri: Uri?) {
        if (uri == null) return
        progressDialog!!.show()
        disposable = Completable.fromAction { DatabaseTransporter.exportToDocument(uri, requireContext()) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                showExportSuccessSnackbar(uri, "application/x-sqlite3")
                progressDialog!!.dismiss()
            }, { error: Throwable -> this.showExportErrorDialog(error) })
    }

    private fun chooseOpmlImportPathResult(uri: Uri?) {
        if (uri == null) return
        val intent = Intent(context, OpmlImportActivity::class.java)
        intent.setData(uri)
        startActivity(intent)
    }

    private fun openExportPathPicker(exportType: Export, result: ActivityResultLauncher<Intent>, writer: ExportWriter) {
        val title = dateStampFilename(exportType.outputNameTemplate)

        val intentPickAction = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(exportType.contentType)
            .putExtra(Intent.EXTRA_TITLE, title)

        // Creates an implicit intent to launch a file manager which lets
        // the user choose a specific directory to export to.
        try {
            result.launch(intentPickAction)
            return
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity found. Should never happen...")
        }

        // If we are using a SDK lower than API 21 or the implicit intent failed
        // fallback to the legacy export process
        exportWithWriter(writer, null, exportType)
    }

    private class BackupDatabase : CreateDocument() {
        override fun createIntent(context: Context, input: String): Intent {
            return super.createIntent(context, input)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-sqlite3")
        }
    }

    private enum class Export(val contentType: String, val outputNameTemplate: String, @field:StringRes val labelResId: Int) {
        OPML(CONTENT_TYPE_OPML, DEFAULT_OPML_OUTPUT_NAME, R.string.opml_export_label),
        HTML(CONTENT_TYPE_HTML, DEFAULT_HTML_OUTPUT_NAME, R.string.html_export_label),
        FAVORITES(CONTENT_TYPE_HTML, DEFAULT_FAVORITES_OUTPUT_NAME, R.string.favorites_export_label),
    }

    companion object {
        private const val TAG = "ImportExPrefFragment"
        private const val PREF_OPML_EXPORT = "prefOpmlExport"
        private const val PREF_OPML_IMPORT = "prefOpmlImport"
        private const val PREF_HTML_EXPORT = "prefHtmlExport"
        private const val PREF_PREFERENCES_IMPORT = "prefPrefImport"
        private const val PREF_PREFERENCES_EXPORT = "prefPrefExport"
        private const val PREF_DATABASE_IMPORT = "prefDatabaseImport"
        private const val PREF_DATABASE_EXPORT = "prefDatabaseExport"
        private const val PREF_FAVORITE_EXPORT = "prefFavoritesExport"
        private const val DEFAULT_OPML_OUTPUT_NAME = "podcini-feeds-%s.opml"
        private const val CONTENT_TYPE_OPML = "text/x-opml"
        private const val DEFAULT_HTML_OUTPUT_NAME = "podcini-feeds-%s.html"
        private const val CONTENT_TYPE_HTML = "text/html"
        private const val DEFAULT_FAVORITES_OUTPUT_NAME = "podcini-favorites-%s.html"
        private const val DATABASE_EXPORT_FILENAME = "PodciniBackup-%s.db"
    }
}
