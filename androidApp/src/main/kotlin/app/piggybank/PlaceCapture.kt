package app.piggybank

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import app.piggybank.db.PiggyDatabase
import app.piggybank.place.Place
import app.piggybank.place.PlaceService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gets the phone's location from the OS (no Play Services, no network calls by this app) and
 * attaches it to payment SMS that just arrived. Started the moment an SMS is received, so the
 * fix is taken while the user is still where they paid.
 */
object PlaceCapture {
    private const val FRESH_MS = 2 * 60_000L

    fun hasForeground(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Needed for the receiver to read the location while the app is closed (Android 10+). */
    fun hasBackground(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fix in progress; [await] waits for it up to the given time. */
    class Pending internal constructor(private val result: AtomicReference<Location?>, private val latch: CountDownLatch) {
        fun await(maxMs: Long): Location? {
            if (maxMs > 0) latch.await(maxMs, TimeUnit.MILLISECONDS)
            return result.get()
        }
    }

    /** Starts getting a location if the user turned the feature on and allowed it; null otherwise. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, db: PiggyDatabase): Pending? {
        if (!PlaceService(db).enabled || !hasForeground(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val result = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)

        // A recent fix the OS already has is good enough and costs nothing.
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMs(it) <= FRESH_MS }
            .minByOrNull { it.accuracy }
            ?.let { result.set(it) }

        val provider = providers.firstOrNull()
        if (provider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                lm.getCurrentLocation(provider, null, Executors.newSingleThreadExecutor()) { loc ->
                    if (loc != null) result.set(loc)
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        } else {
            latch.countDown()
        }
        return Pending(result, latch)
    }

    /** Attaches [location] to the payments that just arrived. */
    fun attach(db: PiggyDatabase, location: Location) {
        val fixedAt = System.currentTimeMillis() - ageMs(location)
        val svc = PlaceService(db)
        val home = db.appQueries.getSetting("home_currency").executeAsOneOrNull() ?: "INR"
        svc.attach(
            Place(location.latitude, location.longitude, location.accuracy.toDouble().takeIf { location.hasAccuracy() }, fixedAt),
            System.currentTimeMillis(), home,
        )
    }

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
}
