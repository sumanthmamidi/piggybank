package app.piggybank.place

import app.piggybank.data.DriverFactory
import app.piggybank.data.createDatabase
import app.piggybank.db.PiggyDatabase
import app.piggybank.review.Classifier
import app.piggybank.review.ReviewService
import app.piggybank.sms.IncomingSms
import app.piggybank.sms.SmsIngestor
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Save where I paid": which payments get the phone's location. Synthetic SMS only. */
class PlaceServiceTest {
    private val tz = TimeZone.of("Asia/Kolkata")
    private lateinit var db: PiggyDatabase
    private lateinit var places: PlaceService
    private var nextId = 1L
    private val now = 1_800_000_000_000L
    private val spot = Place(12.97160, 77.59460, 15.0, now - 5_000)

    @BeforeTest fun open() {
        db = createDatabase(DriverFactory().create())
        places = PlaceService(db)
    }

    private fun receive(body: String, at: Long, sender: String = "VM-HDFCBK"): Long {
        SmsIngestor(db, tz).ingest(listOf(IncomingSms(nextId++, sender, body, at, at)))
        Classifier(db, now = { at }, tz = tz).run()
        return db.txnQueries.selectWithDetailsBetween(0, Long.MAX_VALUE).executeAsList().maxBy { it.id }.id
    }

    @Test fun offUntilTheUserTurnsItOn() {
        assertFalse(places.enabled)
        places.enabled = true
        assertTrue(places.enabled)
    }

    @Test fun inPersonPaymentThatJustArrivedGetsThePlace() {
        val cafe = receive("Rs.180.00 spent on HDFC Bank Card x4421 at BLUE TOKAI CAFE on 21-11-26.", now - 20_000)
        assertEquals(listOf(cafe), places.attach(spot, now, "INR"))
        assertEquals(spot, places.placeOf(cafe))
        assertEquals(emptyList(), places.attach(spot, now, "INR")) // only once
    }

    @Test fun remoteOldOrForeignPaymentsAreSkipped() {
        receive("Transaction Successful! INR499.00 has been debited to your SBI Credit Card XX9999 against recurring e-mandate. Details: Merchant: STREAMCO SiHub ID: Y1", now - 10_000, sender = "VM-SBICRD")
        receive("Transaction Successful! USD5.00 has been debited to your SBI Credit Card XX9999. Merchant: ACMECLOUD", now - 10_000, sender = "VM-SBICRD")
        receive("Rs.180.00 spent on HDFC Bank Card x4421 at BLUE TOKAI CAFE on 21-11-26.", now - 10 * 60_000) // arrived 10 min ago
        assertEquals(emptyList(), places.attach(spot, now, "INR"))
    }

    @Test fun staleFixIsNotUsed() {
        receive("Rs.180.00 spent on HDFC Bank Card x4421 at BLUE TOKAI CAFE on 21-11-26.", now - 20_000)
        assertEquals(emptyList(), places.attach(spot.copy(fixedAt = now - 10 * 60_000), now, "INR"))
    }

    @Test fun removingThePlaceOrThePaymentClearsIt() {
        val cafe = receive("Rs.180.00 spent on HDFC Bank Card x4421 at BLUE TOKAI CAFE on 21-11-26.", now - 20_000)
        places.attach(spot, now, "INR")
        places.remove(cafe)
        assertNull(places.placeOf(cafe))

        val shop = receive("Rs.250.00 spent on HDFC Bank Card x4421 at CORNER STORE on 21-11-26.", now - 15_000)
        places.attach(spot, now, "INR")
        ReviewService(db, tz) { now }.notAPayment(shop) // deleting a payment with a place must not fail
        assertNull(places.placeOf(shop))
    }
}
