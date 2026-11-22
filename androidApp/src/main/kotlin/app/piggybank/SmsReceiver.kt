package app.piggybank

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlin.concurrent.thread

/**
 * Wakes on every incoming SMS, even when the app is closed (A1). It doesn't parse the PDUs itself:
 * it waits for the SMS app to write the message to the inbox, then runs the normal inbox scan,
 * so a live SMS and a later rescan can never create two rows.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        val app = context.applicationContext as PiggyApplication
        thread(name = "piggy-sms") {
            try {
                // goAsync allows ~10 s. Try at 2.5 s, then once more at 7 s if the inbox wasn't written yet.
                for (wait in longArrayOf(2_500, 4_500)) {
                    Thread.sleep(wait)
                    val s = SmsSync.catchUp(app, app.database) ?: break
                    if (s.read > s.alreadyStored) break
                }
            } catch (e: Exception) {
                Log.w("PiggyBank", "SMS scan after broadcast failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
