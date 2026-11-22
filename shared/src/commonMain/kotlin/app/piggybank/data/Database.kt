package app.piggybank.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.piggybank.db.PiggyDatabase

/** Each platform supplies its SQLite driver. The database file lives in app-private storage. */
expect class DriverFactory {
    fun create(): SqlDriver
}

const val DATABASE_NAME = "piggybank.db"

fun createDatabase(driver: SqlDriver): PiggyDatabase {
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    upgradeInPlace(driver)
    return PiggyDatabase(driver)
}

/**
 * Small additive upgrades for databases created by earlier builds, applied once when opened.
 * Each step checks before changing anything, so running it again is harmless.
 */
internal fun upgradeInPlace(driver: SqlDriver) {
    val txnColumns = driver.executeQuery(null, "PRAGMA table_info(txn)", { c ->
        val names = mutableListOf<String>()
        while (c.next().value) names += c.getString(1)!!
        QueryResult.Value(names)
    }, 0).value

    // M3: suggestions live apart from the kind, so a suspected self-transfer still counts as a spend
    // until the user marks it. Self-transfers that older builds marked on their own go back to review.
    if (txnColumns.isNotEmpty() && "suggested_kind" !in txnColumns) {
        driver.execute(null, "ALTER TABLE txn ADD COLUMN suggested_kind TEXT CHECK (suggested_kind IN ('SPEND', 'INVESTMENT', 'SELF_TRANSFER', 'CC_BILL', 'WALLET_TOPUP'))", 0)
        driver.execute(null, "ALTER TABLE txn ADD COLUMN suggest_reason TEXT", 0)
        driver.execute(
            null,
            "UPDATE txn SET kind = 'SPEND', category_id = NULL, need_want = NULL, tag_source = NULL, reviewed_at = NULL " +
                "WHERE kind = 'SELF_TRANSFER' AND tag_source = 'RULE' AND rule_id IS NULL",
            0,
        )
    }
}
