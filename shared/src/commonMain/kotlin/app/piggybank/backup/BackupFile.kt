package app.piggybank.backup

import app.piggybank.sms.Sha256
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** What a backup says about itself. Written as one line of JSON ahead of the database bytes. */
data class Manifest(
    val format: Int,
    val schemaVersion: Long,
    val appVersion: String,
    val createdAt: Long,
    val counts: Map<String, Long>,
    val sha256: String,
    val dbSize: Long,
)

sealed interface Decoded {
    data class Ok(val manifest: Manifest, val db: ByteArray) : Decoded
    data class Bad(val reason: String) : Decoded
}

/**
 * A `.pbk` backup: a magic line, a manifest line, then a consistent SQLite snapshot.
 * Shared by Android and iOS so either can restore the other's backups.
 */
object BackupFile {
    const val FORMAT = 1
    private const val MAGIC = "PIGGYBANK-BACKUP\n"
    private val NAME = Regex("""^piggybank-(\d{4}-\d{2}-\d{2})\.pbk$""")

    fun fileName(date: LocalDate) = "piggybank-$date.pbk"
    fun dateOf(name: String): LocalDate? = NAME.find(name)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun encode(db: ByteArray, schemaVersion: Long, appVersion: String, createdAt: Long, counts: Map<String, Long>): ByteArray {
        val m = Manifest(FORMAT, schemaVersion, appVersion, createdAt, counts, Sha256.digest(db).hex(), db.size.toLong())
        return (MAGIC + toJson(m) + "\n").encodeToByteArray() + db
    }

    /** Checks the magic, manifest, size and checksum; a damaged or foreign file is [Decoded.Bad]. */
    fun decode(bytes: ByteArray, currentSchema: Long): Decoded {
        val magic = MAGIC.encodeToByteArray()
        if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) return Decoded.Bad("Not a PiggyBank backup")
        val end = (magic.size until minOf(bytes.size, magic.size + 64_000)).firstOrNull { bytes[it] == '\n'.code.toByte() }
            ?: return Decoded.Bad("The backup's header is damaged")
        val m = fromJson(bytes.copyOfRange(magic.size, end).decodeToString()) ?: return Decoded.Bad("The backup's header is damaged")
        if (m.format > FORMAT) return Decoded.Bad("Made by a newer version of PiggyBank. Update the app first.")
        if (m.schemaVersion > currentSchema) return Decoded.Bad("Made by a newer version of PiggyBank. Update the app first.")
        val db = bytes.copyOfRange(end + 1, bytes.size)
        if (db.size.toLong() != m.dbSize) return Decoded.Bad("The backup is incomplete")
        if (Sha256.digest(db).hex() != m.sha256) return Decoded.Bad("The backup is damaged (checksum doesn't match)")
        return Decoded.Ok(m, db)
    }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // A tiny fixed-shape JSON writer/reader: no serialization library needed for six fields.
    internal fun toJson(m: Manifest): String =
        "{\"format\":${m.format},\"schema_version\":${m.schemaVersion},\"app_version\":\"${m.appVersion.filter { it != '"' && it != '\\' }}\"," +
            "\"created_at\":${m.createdAt},\"db_size\":${m.dbSize},\"sha256\":\"${m.sha256}\",\"counts\":{" +
            m.counts.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}}"

    internal fun fromJson(s: String): Manifest? = runCatching {
        fun num(k: String) = Regex("\"$k\":(-?\\d+)").find(s)!!.groupValues[1].toLong()
        fun str(k: String) = Regex("\"$k\":\"([^\"]*)\"").find(s)!!.groupValues[1]
        val counts = Regex("\"counts\":\\{([^}]*)\\}").find(s)?.groupValues?.get(1).orEmpty()
            .split(',').filter { it.isNotBlank() }
            .associate { kv -> kv.substringBefore(':').trim('"', ' ') to kv.substringAfter(':').trim().toLong() }
        Manifest(num("format").toInt(), num("schema_version"), str("app_version"), num("created_at"), counts, str("sha256"), num("db_size"))
    }.getOrNull()
}

/** I3: keep the newest 7 daily backups, then one per week for 4 weeks, then one per month for 3 months. */
object Retention {
    fun keep(dates: Collection<LocalDate>): Set<LocalDate> {
        val sorted = dates.toSortedSet().reversed()
        val daily = sorted.take(7)
        // Older files only, in weeks (then months) not already covered, so the kept files spread out.
        val weekly = sorted.drop(7).filter { d -> daily.none { weekOf(it) == weekOf(d) } }
            .groupBy(::weekOf).values.take(4).map { it.first() }
        val covered = (daily + weekly).map(::monthOf).toSet()
        val oldest = (daily + weekly).minOrNull()
        val monthly = sorted.filter { d -> oldest == null || d < oldest }.filter { monthOf(it) !in covered }
            .groupBy(::monthOf).values.take(3).map { it.first() }
        return (daily + weekly + monthly).toSet()
    }

    private fun weekOf(d: LocalDate) = d.minus(DatePeriod(days = d.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal))
    private fun monthOf(d: LocalDate) = d.year * 12 + d.monthNumber
}
