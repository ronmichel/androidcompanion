package io.homeassistant.companion.android.launch

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.android.material.composethemeadapter.MdcTheme
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.BuildConfig
import io.homeassistant.companion.android.common.data.authentication.AuthenticationRepository
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.url.UrlRepository
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.onboarding.OnboardApp
import io.homeassistant.companion.android.onboarding.getMessagingToken
import io.homeassistant.companion.android.sensors.LocationSensorManager
import io.homeassistant.companion.android.webview.WebViewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import io.homeassistant.companion.android.common.R as commonR

@AndroidEntryPoint
class LaunchActivity : AppCompatActivity(), LaunchView {

    companion object {
        const val TAG = "LaunchActivity"
    }

    @Inject
    lateinit var presenter: LaunchPresenter

    @Inject
    lateinit var urlRepository: UrlRepository

    @Inject
    lateinit var authenticationRepository: AuthenticationRepository

    @Inject
    lateinit var integrationRepository: IntegrationRepository

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val registerActivityResult = registerForActivityResult(
        OnboardApp(),
        this::onOnboardingComplete
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                MdcTheme {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
        presenter.onViewReady()
    }

    override fun displayWebview() {
        presenter.setSessionExpireMillis(0)

        startActivity(WebViewActivity.newInstance(this, intent.data?.path))
        finish()
        overridePendingTransition(0, 0) // Disable activity start/stop animation
    }

    override fun displayOnBoarding(sessionConnected: Boolean) {
        registerActivityResult.launch(OnboardApp.Input())
    }

    override fun onDestroy() {
        presenter.onFinish()
        super.onDestroy()
    }

    private fun onOnboardingComplete(result: OnboardApp.Output?) {
        mainScope.launch {
            if (result != null) {
                val (url, authCode, deviceName, deviceTrackingEnabled) = result
                val messagingToken = getMessagingToken()
                if (messagingToken.isBlank() && BuildConfig.FLAVOR == "full") {
                    AlertDialog.Builder(this@LaunchActivity)
                        .setTitle(commonR.string.firebase_error_title)
                        .setMessage(commonR.string.firebase_error_message)
                        .setPositiveButton(commonR.string.continue_connect) { _, _ ->
                            mainScope.launch {
                                registerAndOpenWebview(
                                    url,
                                    authCode,
                                    deviceName,
                                    messagingToken,
                                    deviceTrackingEnabled
                                )
                            }
                        }
                        .show()
                } else {
                    registerAndOpenWebview(
                        url,
                        authCode,
                        deviceName,
                        messagingToken,
                        deviceTrackingEnabled
                    )
                }
            } else
                Log.e(TAG, "onOnboardingComplete: Activity result returned null intent data")
        }
    }

    private suspend fun registerAndOpenWebview(
        url: String,
        authCode: String,
        deviceName: String,
        messagingToken: String,
        deviceTrackingEnabled: Boolean
    ) {
        try {
            urlRepository.saveUrl(url)
            authenticationRepository.registerAuthorizationCode(authCode)
            integrationRepository.registerDevice(
                DeviceRegistration(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    deviceName,
                    messagingToken
                )
            )
        } catch (e: Exception) {
            // Fatal errors: if one of these calls fail, the app cannot proceed.
            // Because this runs after the webview, the only expected errors are system
            // version related in OkHttp (cryptography), or general connection issues (offline/unknown).
            Log.e(TAG, "Exception while registering", e)
            AlertDialog.Builder(this@LaunchActivity)
                .setTitle(commonR.string.error_connection_failed)
                .setMessage(
                    when (e) {
                        is SSLHandshakeException -> commonR.string.webview_error_FAILED_SSL_HANDSHAKE
                        is SSLException -> commonR.string.webview_error_SSL_INVALID
                        else -> commonR.string.webview_error
                    }
                )
                .setCancelable(false)
                .setPositiveButton(commonR.string.ok) { dialog, _ ->
                    dialog.dismiss()
                    displayOnBoarding(false)
                }
                .show()
            return
        }
        setLocationTracking(deviceTrackingEnabled)
        displayWebview()
    }

    private suspend fun setLocationTracking(enabled: Boolean) {
        val sensorDao = AppDatabase.getInstance(applicationContext).sensorDao()
        sensorDao.setSensorsEnabled(
            sensorIds = listOf(
                LocationSensorManager.backgroundLocation.id,
                LocationSensorManager.zoneLocation.id,
                LocationSensorManager.singleAccurateLocation.id
            ),
            enabled = enabled
        )
    }
}
