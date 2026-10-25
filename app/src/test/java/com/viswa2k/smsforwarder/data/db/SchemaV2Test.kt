package com.viswa2k.smsforwarder.data.db

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exported Room schema v2 (what MIGRATION_1_2 must produce) and the untouched v1 file. */
class SchemaV2Test {
    private fun schema(version: Int): JSONObject {
        val dir = listOf("schemas", "app/schemas").map { File(it, "com.viswa2k.smsforwarder.data.db.ForwarderDatabase") }
            .first { it.isDirectory }
        return JSONObject(File(dir, "$version.json").readText()).getJSONObject("database")
    }

    private fun logsFields(db: JSONObject) = db.getJSONArray("entities").let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("tableName") == "logs" }.getJSONArray("fields")
    }.let { f -> (0 until f.length()).map { f.getJSONObject(it) } }

    @Test fun `v2 logs table has origin not null default 0`() {
        val db = schema(2)
        assertEquals(2, db.getInt("version"))
        val origin = logsFields(db).firstOrNull { it.getString("columnName") == "origin" }
        assertNotNull("origin column", origin)
        assertEquals("INTEGER", origin!!.getString("affinity"))
        assertTrue(origin.getBoolean("notNull"))
        assertEquals("0", origin.getString("defaultValue"))
    }

    @Test fun `v1 schema stays without origin`() {
        val db = schema(1)
        assertEquals(1, db.getInt("version"))
        assertTrue(logsFields(db).none { it.getString("columnName") == "origin" })
    }

    @Test fun `migration statement matches the v2 column`() {
        assertEquals("ALTER TABLE logs ADD COLUMN origin INTEGER NOT NULL DEFAULT 0", MIGRATION_1_2_SQL)
    }
}
