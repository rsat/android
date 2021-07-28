package org.owntracks.android.ui.preferences.load

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.owntracks.android.R
import org.owntracks.android.databinding.UiPreferencesLoadBinding
import org.owntracks.android.support.AppRestarter
import timber.log.Timber
import java.io.IOException
import java.net.URI
import javax.inject.Inject

@SuppressLint("GoogleAppIndexingApiWarning")
@AndroidEntryPoint
class LoadActivity : AppCompatActivity() {
    private val viewModel: LoadViewModel by viewModels()
    private lateinit var binding: UiPreferencesLoadBinding

    @Inject
    lateinit var appRestarter: AppRestarter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.ui_preferences_load)
        binding.vm = viewModel
        binding.lifecycleOwner = this
        setSupportActionBar(binding.appbar.toolbar)

        viewModel.displayedConfiguration.observe(this, { invalidateOptionsMenu() })
        viewModel.configurationImportStatus.observe(this, {
            invalidateOptionsMenu()
            if (it == ImportStatus.SAVED) {
                showFinishDialog()
            }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setHasBack(false)
        handleIntent(intent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.save) {
            viewModel.saveConfiguration()
            return true
        } else if (itemId == R.id.close || itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setHasBack(hasBackArrow: Boolean) {
        supportActionBar?.run { setDisplayHomeAsUpEnabled(hasBackArrow) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_load, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.close).isVisible =
            viewModel.configurationImportStatus.value !== ImportStatus.LOADING
        menu.findItem(R.id.save).isVisible =
            viewModel.configurationImportStatus.value === ImportStatus.SUCCESS
        return true
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            Timber.e("no intent provided")
            return
        }

        setHasBack(intent.getBundleExtra("_args")?.getBoolean(FLAG_IN_APP, false) ?: false)

        val action = intent.action
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data
            if (uri != null) {
                Timber.v("uri: %s", uri)
                if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
                    viewModel.extractPreferences(getContentFromURI(uri))
                } else {
                    viewModel.extractPreferences(URI(uri.toString()))
                }
            } else {
                viewModel.configurationImportFailed(Exception(getString(R.string.preferencesImportNoURIGiven)))
            }
        } else {
            val pickerIntent = Intent(Intent.ACTION_GET_CONTENT)
            pickerIntent.addCategory(Intent.CATEGORY_OPENABLE)
            pickerIntent.type = "*/*"
            try {
                filePickerResultLauncher.launch(
                    Intent.createChooser(
                        pickerIntent,
                        getString(R.string.loadActivitySelectAFile)
                    )
                )
            } catch (ex: ActivityNotFoundException) {
                Snackbar.make(
                    binding.root,
                    R.string.loadActivityNoFileExplorerFound,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val filePickerResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                var content = ByteArray(0)
                try {
                    content = getContentFromURI(it.data?.data)
                } catch (e: IOException) {
                    Timber.e(e, "Could not extract content from ${it.data}")
                }
                viewModel.extractPreferences(content)
            } else {
                finish()
            }
        }


    @Throws(IOException::class)
    private fun getContentFromURI(uri: Uri?): ByteArray {
        val stream = contentResolver.openInputStream(uri!!)
        val output = ByteArray(stream!!.available())
        val bytesRead = stream.read(output)
        Timber.d("Read %d bytes from content URI", bytesRead)
        return output
    }

    private fun showFinishDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.importConfigurationSuccessTitle))
            .setMessage(getString(R.string.importConfigurationSuccessMessage))
            .setPositiveButton(getString(R.string.restart)) { _: DialogInterface?, _: Int ->
                appRestarter.restart()
            }
            .setNegativeButton(getString(R.string.cancel)) { _: DialogInterface?, _: Int -> finish() }
            .show()
    }

    companion object {
        const val FLAG_IN_APP = "INAPP"
    }
}