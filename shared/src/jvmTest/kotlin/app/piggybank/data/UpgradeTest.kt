package app.piggybank.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.piggybank.db.PiggyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpgradeTest {
    /** A database from an earlier build: no suggestion columns, and a self-transfer it marked on its own. */
    @Test fun oldAutoMarkedTransfersGoBackToReview() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { PiggyDatabase.Schema.create(it) }
        driver.execute(null, "ALTER TABLE txn DROP COLUMN suggest_reason", 0)
        driver.execute(null, "ALTER TABLE txn DROP COLUMN suggested_kind", 0)
        driver.execute(null, "INSERT INTO txn(occurred_at, date_source, orig_currency, orig_amount_minor, home_amount_minor, fx_source, kind, tag_source, reviewed_at) " +
            "VALUES (1, 'SMS', 'INR', 100, 100, 'SAME', 'SELF_TRANSFER', 'RULE', 5)", 0)
        driver.execute(null, "INSERT INTO txn(occurred_at, date_source, orig_currency, orig_amount_minor, home_amount_minor, fx_source, kind, tag_source, reviewed_at) " +
            "VALUES (2, 'SMS', 'INR', 200, 200, 'SAME', 'SELF_TRANSFER', 'USER', 5)", 0) // the user's own decision stays

        val db = createDatabase(driver)
        createDatabase(driver) // running it twice is harmless
        val (auto, user) = db.txnQueries.selectWithDetailsBetween(0, 10).executeAsList().sortedBy { it.occurred_at }
        assertEquals("SPEND", auto.kind)
        assertNull(auto.reviewed_at)
        assertEquals("SELF_TRANSFER", user.kind)
    }

    /** A database from before saved places gets the side table on open. */
    @Test fun oldDatabaseGetsThePlaceTable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { PiggyDatabase.Schema.create(it) }
        driver.execute(null, "DROP TABLE txn_place", 0)
        val db = createDatabase(driver)
        createDatabase(driver)
        assertNull(db.placeQueries.selectPlace(1).executeAsOneOrNull())
    }
}
