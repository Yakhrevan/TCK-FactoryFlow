package com.tck.factoryflow.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Consistent, validated SQLite snapshots with rollback on failed restore validation. */
object FactoryBackupManager {
    private const val DATABASE_NAME = "factoryflow.db"
    private const val CURRENT_SCHEMA = 11

    fun exportSnapshot(context: Context, destination: Uri) {
        val database = FactoryDatabase.get(context)
        val snapshot = File(context.cacheDir, "factoryflow-snapshot-" + System.currentTimeMillis() + ".db")
        try {
            val safePath = snapshot.absolutePath.replace("'", "''")
            database.openHelper.writableDatabase.execSQL("VACUUM INTO '$safePath'")
            validate(snapshot)
            context.contentResolver.openOutputStream(destination)?.use { output ->
                FileInputStream(snapshot).use { input -> input.copyTo(output) }
            } ?: error("Could not open backup destination.")
        } finally {
            snapshot.delete()
        }
    }

    fun restoreSnapshot(context: Context, source: Uri) {
        val incoming = File(context.cacheDir, "factoryflow-restore-" + System.currentTimeMillis() + ".db")
        val live = context.getDatabasePath(DATABASE_NAME)
        val rollback = File(context.cacheDir, "factoryflow-before-restore-" + System.currentTimeMillis() + ".db")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(incoming).use { output -> input.copyTo(output) }
            } ?: error("Could not read selected backup.")
            validate(incoming)
            require(incoming.length() > 0L) { "Backup file is empty." }

            FactoryDatabase.closeForRestore()
            live.parentFile?.mkdirs()
            if (live.exists()) FileInputStream(live).use { input -> FileOutputStream(rollback).use { input.copyTo(it) } }
            File(live.absolutePath + "-wal").delete()
            File(live.absolutePath + "-shm").delete()
            FileOutputStream(live).use { output -> FileInputStream(incoming).use { it.copyTo(output) } }

            try {
                val reopened = FactoryDatabase.get(context)
                reopened.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Restored database failed integrity check." }
                }
            } catch (failure: Exception) {
                FactoryDatabase.closeForRestore()
                if (rollback.exists()) {
                    File(live.absolutePath + "-wal").delete()
                    File(live.absolutePath + "-shm").delete()
                    FileOutputStream(live).use { output -> FileInputStream(rollback).use { it.copyTo(output) } }
                    FactoryDatabase.get(context)
                }
                throw IllegalStateException("Restore validation failed. The previous database was restored.", failure)
            }
        } finally {
            incoming.delete()
            rollback.delete()
        }
    }

    private fun validate(file: File) {
        require(file.exists() && file.length() > 0L) { "Selected file is not a database backup." }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst()) { "Backup integrity check returned no result." }
                cursor.getString(0)
            }
            require(integrity == "ok") { "Backup database is corrupt." }
            val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                require(cursor.moveToFirst()) { "Could not read backup schema version." }
                cursor.getInt(0)
            }
            require(version == CURRENT_SCHEMA) { "Backup schema version " + version + " is not supported by this app (expected " + CURRENT_SCHEMA + ")." }
            val hasProducts = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='products'", null).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 1
            }
            require(hasProducts) { "Backup does not contain FactoryFlow product data." }
        } finally {
            db.close()
        }
    }
}
