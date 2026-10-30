package io.github.sp3wro.hamradiotoolkit.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.sp3wro.hamradiotoolkit.data.dxcc.CtyDatabase
import io.github.sp3wro.hamradiotoolkit.data.model.QsoEntry
import io.github.sp3wro.hamradiotoolkit.utils.Callsigns.baseCall
import kotlinx.serialization.json.Json
import java.io.File

enum class UploadService { QRZ, EQSL }

private class LogbookOpenHelper(context: Context) :
    SQLiteOpenHelper(context, "logbook.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE qso (
                id TEXT PRIMARY KEY,
                callsign TEXT NOT NULL,
                datetime_utc TEXT NOT NULL,
                frequency TEXT NOT NULL DEFAULT '',
                band TEXT NOT NULL DEFAULT '',
                mode TEXT NOT NULL DEFAULT '',
                rst_sent TEXT NOT NULL DEFAULT '',
                rst_rcvd TEXT NOT NULL DEFAULT '',
                comment TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL DEFAULT '',
                qth TEXT NOT NULL DEFAULT '',
                gridsquare TEXT NOT NULL DEFAULT '',
                dxcc INTEGER,
                country TEXT NOT NULL DEFAULT '',
                hunted_ref TEXT NOT NULL DEFAULT '',
                qrz_uploaded INTEGER NOT NULL DEFAULT 0,
                eqsl_uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_qso_callsign ON qso(callsign)")
        db.execSQL("CREATE INDEX idx_qso_datetime ON qso(datetime_utc)")
        db.execSQL("CREATE INDEX idx_qso_dxcc ON qso(dxcc)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Kolejne wersje schematu dodawać jako migracje, nigdy przez kasowanie tabeli z logiem
    }
}

/**
 * Dziennik łączności w SQLite. Operacje są blokujące – wywoływać poza wątkiem UI.
 * Przy pierwszym użyciu przenosi stary dziennik z qso_log.json.
 */
class LogbookRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val helper = LogbookOpenHelper(appContext)
    @Volatile private var migrated = false

    private val db: SQLiteDatabase
        get() {
            val database = helper.writableDatabase
            if (!migrated) migrateFromJson(database)
            return database
        }

    fun getLogs(): List<QsoEntry> =
        db.query("qso", null, null, null, null, null, "datetime_utc DESC, rowid DESC").use { it.toEntries() }

    fun addQso(entry: QsoEntry) {
        db.insertWithOnConflict("qso", null, entry.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateQso(entry: QsoEntry) {
        db.update("qso", entry.toValues(), "id = ?", arrayOf(entry.id))
    }

    fun deleteQso(id: String) {
        db.delete("qso", "id = ?", arrayOf(id))
    }

    /** Import wielu QSO w jednej transakcji; pomija duble (ten sam znak, czas, pasmo i emisja). */
    fun importAll(entries: List<QsoEntry>): Int {
        val database = db
        var added = 0
        database.beginTransaction()
        try {
            for (entry in entries) {
                if (isDuplicate(database, entry)) continue
                database.insert("qso", null, entry.toValues())
                added++
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return added
    }

    /** Wcześniejsze łączności z tym znakiem (bez dopisków /P, /M), najnowsze najpierw. */
    fun previousContacts(callsign: String): List<QsoEntry> {
        val base = baseCall(callsign)
        if (base.isEmpty()) return emptyList()
        return db.query(
            "qso", null, "callsign = ? OR callsign LIKE ? OR callsign LIKE ?",
            arrayOf(base, "$base/%", "%/$base"), null, null, "datetime_utc DESC"
        ).use { it.toEntries() }.filter { baseCall(it.callsign) == base }
    }

    fun workedDxcc(): Set<Int> =
        db.rawQuery("SELECT DISTINCT dxcc FROM qso WHERE dxcc IS NOT NULL", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getInt(0)) }
        }

    fun markUploaded(ids: Collection<String>, service: UploadService) {
        if (ids.isEmpty()) return
        val column = if (service == UploadService.QRZ) "qrz_uploaded" else "eqsl_uploaded"
        val database = db
        database.beginTransaction()
        try {
            val values = ContentValues().apply { put(column, 1) }
            for (id in ids) database.update("qso", values, "id = ?", arrayOf(id))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Uzupełnia kraj DXCC w QSO, które go nie mają (np. stare wpisy sprzed bazy cty). */
    fun backfillDxcc(cty: CtyDatabase): Int {
        val database = db
        val missing = database.query("qso", arrayOf("id", "callsign"), "dxcc IS NULL", null, null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        var updated = 0
        database.beginTransaction()
        try {
            // Nazwę kraju wpisujemy tylko tam, gdzie jej brak – nie nadpisujemy danych z importu
            val statement = database.compileStatement(
                "UPDATE qso SET dxcc = ?, country = CASE WHEN country = '' THEN ? ELSE country END WHERE id = ?"
            )
            for ((id, call) in missing) {
                val entity = cty.resolve(call) ?: continue
                statement.clearBindings()
                statement.bindLong(1, entity.dxcc.toLong())
                statement.bindString(2, entity.name)
                statement.bindString(3, id)
                updated += statement.executeUpdateDelete()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return updated
    }

    private fun isDuplicate(database: SQLiteDatabase, entry: QsoEntry): Boolean =
        database.query(
            "qso", arrayOf("id"), "callsign = ? AND datetime_utc = ? AND band = ? AND mode = ?",
            arrayOf(entry.callsign, entry.datetimeUtc, entry.band, entry.mode), null, null, null, "1"
        ).use { it.moveToFirst() }

    @Synchronized
    private fun migrateFromJson(database: SQLiteDatabase) {
        if (migrated) return
        val json = File(appContext.filesDir, "qso_log.json")
        if (json.exists()) {
            try {
                val entries = Json { ignoreUnknownKeys = true }.decodeFromString<List<QsoEntry>>(json.readText())
                database.beginTransaction()
                try {
                    for (entry in entries) database.insertWithOnConflict("qso", null, entry.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                // Stary plik zostaje jako kopia – nic nie jest kasowane
                json.renameTo(File(appContext.filesDir, "qso_log.migrated.json"))
                Log.i(TAG, "Przeniesiono ${entries.size} QSO z qso_log.json do bazy")
            } catch (e: Exception) {
                Log.e(TAG, "Nie udało się przenieść qso_log.json – plik zostaje bez zmian", e)
            }
        }
        migrated = true
    }

    private fun QsoEntry.toValues() = ContentValues().apply {
        put("id", id)
        put("callsign", callsign)
        put("datetime_utc", datetimeUtc)
        put("frequency", frequency)
        put("band", band)
        put("mode", mode)
        put("rst_sent", rstSent)
        put("rst_rcvd", rstRcvd)
        put("comment", comment)
        put("name", name)
        put("qth", qth)
        put("gridsquare", gridSquare)
        if (dxcc != null) put("dxcc", dxcc) else putNull("dxcc")
        put("country", country)
        put("hunted_ref", huntedRef)
        put("qrz_uploaded", if (qrzUploaded) 1 else 0)
        put("eqsl_uploaded", if (eqslUploaded) 1 else 0)
    }

    private fun Cursor.toEntries(): List<QsoEntry> {
        val list = ArrayList<QsoEntry>(count)
        fun str(name: String) = getString(getColumnIndexOrThrow(name)).orEmpty()
        val dxccIndex = getColumnIndexOrThrow("dxcc")
        while (moveToNext()) {
            list.add(
                QsoEntry(
                    id = str("id"),
                    callsign = str("callsign"),
                    datetimeUtc = str("datetime_utc"),
                    frequency = str("frequency"),
                    band = str("band"),
                    mode = str("mode"),
                    rstSent = str("rst_sent"),
                    rstRcvd = str("rst_rcvd"),
                    comment = str("comment"),
                    name = str("name"),
                    qth = str("qth"),
                    gridSquare = str("gridsquare"),
                    dxcc = if (isNull(dxccIndex)) null else getInt(dxccIndex),
                    country = str("country"),
                    huntedRef = str("hunted_ref"),
                    qrzUploaded = getInt(getColumnIndexOrThrow("qrz_uploaded")) != 0,
                    eqslUploaded = getInt(getColumnIndexOrThrow("eqsl_uploaded")) != 0
                )
            )
        }
        return list
    }

    companion object {
        private const val TAG = "LogbookRepository"
        @Volatile private var instance: LogbookRepository? = null

        fun get(context: Context): LogbookRepository =
            instance ?: synchronized(this) {
                instance ?: LogbookRepository(context).also { instance = it }
            }
    }
}
