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
import app.piggybank.backup.BackupFolder
import app.piggybank.backup.Backups
import app.piggybank.backup.BackupService
import app.piggybank.backup.DriveClient
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import app.piggybank.ui.PlaceAccess
import app.piggybank.ui.RestoreOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ---- Restore (J1–J3) ----
    private var found: Backups.Found? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@registerForActivityResult
        // Keep the grant: after a reinstall this is how nightly backups keep writing to the same folder.
        runCatching {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            BackupFolder.remember(this, tree)
        }
        offer { Backups.newestValid(app.piggybank.backup.TreeFolder(this, tree)) }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) offer { Backups.fromFile(this, uri) }
    }

    private fun offer(find: () -> Result<Backups.Found>) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { find() }
            found = r.getOrNull()
            controller.restoreFound(
                r.getOrNull()?.let { f ->
                    RestoreOffer(f.date, f.counts["txns"] ?: 0, f.counts["sms"] ?: 0, f.counts["rules"] ?: 0, f.skipped)
                },
                r.exceptionOrNull()?.message,
            )
        }
    }

    override fun pickRestore(fromFile: Boolean) {
        if (fromFile) pickFile.launch(arrayOf("*/*")) else pickFolder.launch(BackupFolder.initialUri())
    }

    override fun confirmRestore() {
        val f = found ?: return
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) { runCatching { Backups.stage(this@MainActivity, f) } }
            staged.exceptionOrNull()?.let { controller.restoreFound(null, it.message); return@launch }
            // Restart so the backup is swapped in before the database opens again.
            startActivity(Intent.makeRestartActivityTask(componentName))
            Runtime.getRuntime().exit(0)
        }
    }

    // ---- Google Drive (optional) ----
    /** What to do once Google sign-in hands back a token. */
    private var onDriveToken: ((String?, String?) -> Unit)? = null

    private val driveConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data) }
        deliverToken(r.getOrNull()?.accessToken, r.exceptionOrNull()?.message ?: "Google sign-in was cancelled")
    }

    private fun deliverToken(token: String?, error: String?) {
        val next = onDriveToken ?: return
        onDriveToken = null
        next(token, if (token == null) error else null)
    }

    /** Asks Google for the drive.appdata scope, showing the account picker and consent only when needed. */
    private fun withDriveToken(email: String?, then: (String?, String?) -> Unit) {
        onDriveToken = then
        Identity.getAuthorizationClient(this).authorize(DriveClient.request(email))
            .addOnSuccessListener { r: AuthorizationResult ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) driveConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                else deliverToken(r.accessToken, "Google didn't return access")
            }
            .addOnFailureListener { e -> deliverToken(null, "Google sign-in failed: ${e.message}") }
    }

    override fun connectDrive() = withDriveToken(null) { token, error ->
        if (token == null) { controller.driveConnected(null, error); return@withDriveToken }
        lifecycleScope.launch {
            val email = withContext(Dispatchers.IO) { DriveClient(token).email() }
            controller.driveConnected(email, if (email == null) "Couldn't reach Google Drive" else null)
        }
    }

    override fun restoreFromDrive() = withDriveToken(null) { token, error ->
        if (token == null) controller.restoreFound(null, error)
        else offer { Backups.newestValidOnDrive(token) }
    }

    override fun backUpNow(): String = Backups.backUpNow(this, db).getOrThrow()

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
