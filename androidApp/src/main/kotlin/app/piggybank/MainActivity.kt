package app.piggybank

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
