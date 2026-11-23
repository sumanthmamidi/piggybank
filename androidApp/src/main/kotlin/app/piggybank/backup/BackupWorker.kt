package app.piggybank.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.piggybank.PiggyApplication
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** I1/I2: one backup a night, around 2 AM, waiting while the battery is low. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = (applicationContext as PiggyApplication).database
        if (!BackupService(db).nightly || Backups.doneToday(db)) return Result.success()
        // A failure is logged and shown on Today; the next night tries again.
        Backups.backUpNow(applicationContext, db)
        return Result.success()
    }

    companion object {
        private const val NAME = "nightly-backup"

        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 2); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
