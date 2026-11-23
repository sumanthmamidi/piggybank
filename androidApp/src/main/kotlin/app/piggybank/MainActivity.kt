package app.piggybank

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.piggybank.sms.IngestSummary
import app.piggybank.ui.App
import app.piggybank.ui.AppController
import app.piggybank.ui.PlaceAccess
import app.piggybank.ui.Platform

class MainActivity : ComponentActivity(), Platform {
    private val db get() = (application as PiggyApplication).database
    private lateinit var controller: AppController
    override var smsDenied = false
        private set

    private val askSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        smsDenied = result[Manifest.permission.READ_SMS] != true
        controller.refresh()
    }

    private val askPlaceWhileOpen = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Android asks for "all the time" separately, and only after "while in use" was given.
        if (PlaceCapture.hasForeground(this) && !PlaceCapture.hasBackground(this)) askPlaceAlways()
        controller.refresh()
    }

    private val askPlaceBackground = registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refresh() }

    private fun askPlaceAlways() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askPlaceBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is light-themed for now, so ask for dark status/nav icons even when the phone is in dark mode.
        val lightBars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
        controller = AppController(db, this, lifecycleScope)
        setContent { App(controller) }
    }

    override fun onResume() {
        super.onResume()
        SmsSync.onChange = { runOnUiThread { controller.refresh() } }
        controller.refresh()
        // Catch up on anything missed while closed or after a reboot (A5).
        if (hasSmsPermission() && importDone()) controller.syncNow()
    }

    override fun onPause() {
        SmsSync.onChange = null
        super.onPause()
    }

    override fun hasSmsPermission() = SmsSync.hasPermission(this)
    override fun importDone() = SmsSync.importDone(db)
    override fun importHistory(months: Int): IngestSummary = SmsSync.importHistory(this, db, months)
    override fun catchUp(): IngestSummary? = SmsSync.catchUp(this, db)

    override fun requestSmsAccess() {
        askSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
    }

    override val placeAccess: PlaceAccess
        get() = when {
            !PlaceCapture.hasForeground(this) -> PlaceAccess.NONE
            !PlaceCapture.hasBackground(this) -> PlaceAccess.WHILE_OPEN
            else -> PlaceAccess.ALWAYS
        }

    override fun requestPlaceAccess() {
        if (!PlaceCapture.hasForeground(this)) {
            askPlaceWhileOpen.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (!PlaceCapture.hasBackground(this)) {
            askPlaceAlways()
        }
    }

    override fun openMap(lat: Double, lon: Double, label: String) {
        val q = Uri.encode("$lat,$lon($label)")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$q"))
        runCatching { startActivity(intent) }
    }

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
