package com.deepsleep.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        /**
         * v2 adds a per-message "thinking was enabled" flag so old reasoning
         * traces remain viewable while new non-thinking messages hide any
         * reasoning_content returned by a provider.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN thinkingEnabled INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("UPDATE messages SET thinkingEnabled = 1 WHERE reasoning != ''")
            }
        }
    }
}