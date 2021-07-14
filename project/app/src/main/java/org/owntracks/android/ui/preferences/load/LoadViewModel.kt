package org.owntracks.android.ui.preferences.load

import android.content.ContentResolver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import okhttp3.*
import org.apache.commons.codec.binary.Base64
import org.apache.hc.core5.net.URLEncodedUtils
import org.owntracks.android.data.repos.WaypointsRepo
import org.owntracks.android.model.messages.MessageConfiguration
import org.owntracks.android.support.Parser
import org.owntracks.android.support.Parser.EncryptionException
import org.owntracks.android.support.Preferences
import timber.log.Timber
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import javax.inject.Inject

@HiltViewModel
class LoadViewModel @Inject constructor(
    private val preferences: Preferences,
    private val parser: Parser,
    private val waypointsRepo: WaypointsRepo
) : ViewModel() {
    private var configuration: MessageConfiguration? = null

    private val mutableConfig = MutableLiveData("")
    val displayedConfiguration: LiveData<String> = mutableConfig
    private val mutableImportStatus = MutableLiveData(ImportStatus.LOADING)
    val configurationImportStatus: LiveData<ImportStatus> = mutableImportStatus
    private val mutableImportError = MutableLiveData<String>()
    val importError: LiveData<String> = mutableImportError


    @Throws(IOException::class, EncryptionException::class)
    private fun setConfiguration(json: String) {
        val message = parser.fromJson(json.toByteArray())
        if (message is MessageConfiguration) {
            configuration = parser.fromJson(json.toByteArray()) as MessageConfiguration
            val prettyConfiguration: String = try {
                parser.toJsonPlainPretty(configuration!!)
            } catch (e: IOException) {
                Timber.e(e)
                "Unable to parse configuration"
            }
            mutableConfig.postValue(prettyConfiguration)
            mutableImportStatus.postValue(ImportStatus.SUCCESS)
        } else {
            throw IOException("Message is not a valid configuration message")
        }
    }

    fun saveConfiguration() {
        preferences.importFromMessage(configuration!!)
        if (!configuration!!.waypoints.isEmpty()) {
            waypointsRepo.importFromMessage(configuration!!.waypoints)
        }
        mutableImportStatus.postValue(ImportStatus.SAVED)
    }

    fun extractPreferences(content: ByteArray) {
        try {
            setConfiguration(String(content, StandardCharsets.UTF_8))
        } catch (e: IOException) {
            configurationImportFailed(e)
        } catch (e: EncryptionException) {
            configurationImportFailed(e)
        }
    }

    fun extractPreferences(uri: URI) {
        try {
            if (ContentResolver.SCHEME_FILE == uri.scheme) {
                // Note: left here to avoid breaking compatibility.  May be removed
                // with sufficient testing. Will not work on Android >5 without granting READ_EXTERNAL_STORAGE permission
                Timber.v("using file:// uri")
                val r = BufferedReader(InputStreamReader(FileInputStream(uri.path)))
                val total = StringBuilder()
                var content: String?
                while (r.readLine().also { content = it } != null) {
                    total.append(content)
                }
                setConfiguration(total.toString())
            } else if ("owntracks" == uri.scheme && "/config" == uri.path) {
                Timber.v("Importing config using owntracks: scheme")
                val queryParams = URLEncodedUtils.parse(uri, StandardCharsets.UTF_8)
                val urlQueryParam: MutableList<String> = ArrayList()
                val configQueryParam: MutableList<String> = ArrayList()
                for (queryParam in queryParams) {
                    if (queryParam.name == "url") {
                        urlQueryParam.add(queryParam.value)
                    }
                    if (queryParam.name == "inline") {
                        configQueryParam.add(queryParam.value)
                    }
                }
                when {
                    configQueryParam.size == 1 -> {
                        val config: ByteArray = Base64.decodeBase64(
                            configQueryParam[0].toByteArray()
                        )
                        setConfiguration(String(config, StandardCharsets.UTF_8))
                    }
                    urlQueryParam.size == 1 -> {
                        val remoteConfigUrl = URL(urlQueryParam[0])
                        val client = OkHttpClient()
                        val request: Request = Request.Builder()
                            .url(remoteConfigUrl)
                            .build()
                        client.newCall(request).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                configurationImportFailed(
                                    Exception(
                                        "Failure fetching config from remote URL",
                                        e
                                    )
                                )
                            }

                            @Throws(IOException::class)
                            override fun onResponse(call: Call, response: Response) {
                                try {
                                    response.body.use { responseBody ->
                                        if (!response.isSuccessful) {
                                            configurationImportFailed(
                                                IOException(
                                                    String.format(
                                                        "Unexpected status code: %s",
                                                        response
                                                    )
                                                )
                                            )
                                            return
                                        }
                                        setConfiguration(responseBody?.string() ?: "")
                                    }
                                } catch (e: EncryptionException) {
                                    configurationImportFailed(e)
                                }
                            }
                        })
                        // This is async, so result handled on the callback
                    }
                    else -> {
                        throw IOException("Invalid config URL")
                    }
                }
            } else {
                throw IOException("Invalid config URL")
            }
        } catch (e: OutOfMemoryError) {
            configurationImportFailed(e)
        } catch (e: IOException) {
            configurationImportFailed(e)
        } catch (e: EncryptionException) {
            configurationImportFailed(e)
        } catch (e: IllegalArgumentException) {
            configurationImportFailed(e)
        }
    }

    fun configurationImportFailed(e: Throwable) {
        Timber.e(e)
        mutableImportError.postValue(e.message)
        mutableImportStatus.postValue(ImportStatus.FAILED)
    }
}