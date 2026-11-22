package app.piggybank.data

import app.piggybank.db.PiggyDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SchemaTest {
    private lateinit var db: PiggyDatabase

    @BeforeTest fun open() {
        db = createDatabase(DriverFactory().create())
    }

    @Test fun startsWithSeededCategories() {
        val cats = db.categoryQueries.selectActive().executeAsList()
        assertEquals(15, cats.size)
        assertEquals(3, db.categoryQueries.selectByTopLevel("EXCLUDED").executeAsList().size)
        assertTrue(cats.any { it.name == "Card bill payment" && it.top_level == "EXCLUDED" })
    }

    @Test fun sameSmsIsStoredOnce() { // A6
        repeat(2) {
            db.smsQueries.insertSms(1, "AX-HDFCBK", "Rs.320.00 spent on HDFC Bank Card x4421", 1_000, "hash-1", null, "PARSED")
        }
        assertEquals(1L, db.smsQueries.countSms().executeAsOne())
    }

    @Test fun spendTotalSkipsHiddenDuplicatesAndRefunds() { // G2, A6b, B6
        val q = db.txnQueries
        fun add(minor: Long, kind: String): Long {
            q.insert(null, 10_000, "SMS", "INR", minor, minor, null, "SAME", null, null, null, null, kind)
            return q.lastInsertId().executeAsOne()
        }
        add(32_000, "SPEND")
        val swiggy = add(61_200, "SPEND")
        add(2_000_000, "SELF_TRANSFER")
        add(1_820_000, "CC_BILL")
        add(500_000, "INVESTMENT")
        val dup = add(48_600, "SPEND")
        val original = add(48_600, "SPEND")
        q.markDuplicate(original, "USER", dup)
        q.setRefund(20_000, swiggy)

        // 320 + (612 − 200) + 486 = ₹1,218
        assertEquals(121_800L, q.spendTotalBetween(0, 20_000).executeAsOne())
    }

    @Test fun refundCannotExceedTheSpend() { // B6
        db.txnQueries.insert(null, 10_000, "SMS", "INR", 61_200, 61_200, null, "SAME", null, null, null, null, "SPEND")
        val id = db.txnQueries.lastInsertId().executeAsOne()
        assertFails { db.txnQueries.setRefund(61_201, id) }
    }

    @Test fun rejectsUnknownKind() {
        assertFails {
            db.txnQueries.insert(null, 10_000, "SMS", "INR", 100, 100, null, "SAME", null, null, null, null, "INCOME")
        }
    }

    @Test fun foreignKeysAreEnforced() {
        // account 4242 doesn't exist
        assertFails {
            db.txnQueries.insert(null, 1, "SMS", "INR", 1, 1, null, "SAME", 4_242, null, null, null, "SPEND")
        }
    }
}
