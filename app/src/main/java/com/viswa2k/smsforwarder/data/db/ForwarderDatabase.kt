package com.viswa2k.smsforwarder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RuleEntity::class, DestinationEntity::class, LogEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ForwarderDatabase : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun destinations(): DestinationDao
    abstract fun logs(): LogDao
    abstract fun outbox(): OutboxDao

    companion object {
        fun create(context: Context): ForwarderDatabase =
            Room.databaseBuilder(context.applicationContext, ForwarderDatabase::class.java, "forwarder.db").build()
    }
}
