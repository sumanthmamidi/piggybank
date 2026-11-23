package app.piggybank.place

import app.piggybank.db.PiggyDatabase

/** A saved location for a payment. */
data class Place(val lat: Double, val lon: Double, val accuracyM: Double?, val fixedAt: Long)

/**
 * Opt-in "save where I paid": when a payment SMS arrives, the phone's location is attached to it.
 * Only for payments likely made in person; never for history, autopay, mandates or foreign charges.
 */
class PlaceService(private val db: PiggyDatabase) {

    var enabled: Boolean
        get() = db.appQueries.getSetting(SETTING).executeAsOneOrNull() == "1"
        set(on) = db.appQueries.putSetting(SETTING, if (on) "1" else "0")

    /**
     * Attaches [place] to every in-person payment whose SMS arrived within [WINDOW_MS] before [now]
     * and has no place yet, as long as the fix itself is that fresh too. Returns the payment ids.
     */
    fun attach(place: Place, now: Long, home: String): List<Long> {
        if (now - place.fixedAt > WINDOW_MS) return emptyList()
        val ids = db.placeQueries.selectRecentWithoutPlace(now - WINDOW_MS).executeAsList()
            .filter { it.kind == "SPEND" && it.duplicate_of == null && it.orig_currency == home && inPerson(it.body) }
            .map { it.id }
        db.transaction {
            ids.forEach { db.placeQueries.insertPlace(it, place.lat, place.lon, place.accuracyM, place.fixedAt) }
        }
        return ids
    }

    fun placeOf(txnId: Long): Place? = db.placeQueries.selectPlace(txnId).executeAsOneOrNull()
        ?.let { Place(it.lat, it.lon, it.accuracy_m, it.fixed_at) }

    fun placesFor(ids: Collection<Long>): Map<Long, Place> =
        if (ids.isEmpty()) emptyMap()
        else db.placeQueries.selectPlacesFor(ids).executeAsList().associate { it.txn_id to Place(it.lat, it.lon, it.accuracy_m, it.fixed_at) }

    fun remove(txnId: Long) = db.placeQueries.deletePlace(txnId)

    companion object {
        const val SETTING = "save_places"
        /** A payment SMS and the location fix must both be this recent. */
        const val WINDOW_MS = 3 * 60_000L

        /** Charges that don't happen where the phone is: mandates, autopay, subscriptions, bills, scheduled ones. */
        private val REMOTE = Regex(
            "mandate|auto ?pay|autodebit|auto-debit|recurring|standing instruction|\\bSI\\b|\\bNACH\\b|\\bECS\\b|" +
                "subscription|bill ?pay|bill payment|scheduled|emi\\b|insurance|premium",
            RegexOption.IGNORE_CASE,
        )

        fun inPerson(body: String): Boolean = !REMOTE.containsMatchIn(body)
    }
}
