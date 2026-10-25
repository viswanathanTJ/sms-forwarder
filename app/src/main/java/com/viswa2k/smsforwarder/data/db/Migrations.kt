package com.viswa2k.smsforwarder.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema v2 adds the message origin (0 SMS, 1 WhatsApp); existing rows are SMS. */
const val MIGRATION_1_2_SQL = "ALTER TABLE logs ADD COLUMN origin INTEGER NOT NULL DEFAULT 0"

/**
 * v2.0.0 (schema v1) is installed on real phones, so this must never be destructive. Idempotent: a
 * column that already exists (an interrupted earlier attempt) is left alone.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hasOrigin = db.query("PRAGMA table_info(logs)").use { c ->
            val name = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == "origin" }
        }
        if (!hasOrigin) db.execSQL(MIGRATION_1_2_SQL)
    }
}
