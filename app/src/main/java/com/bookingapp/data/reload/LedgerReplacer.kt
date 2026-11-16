package com.bookingapp.data.reload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The open ledger, which a reload closes and reopens around the file swap. */
interface LedgerHandle {
    fun close()

    /** Opens the ledger file again and runs a smoke query; throws if it can't be read. */
    fun reopen()
}

class FileTooLargeException(val limit: Long) : IOException("The file is larger than $limit bytes")

/**
 * Replaces the phone ledger with a checked backend database file, keeping the replaced
 * ledger as a single backup (design decision 15). Every step either completes or is rolled
 * back so that the ledger is exactly as it was.
 */
class LedgerReplacer(
    private val databaseFile: File,
    private val cacheDir: File,
    private val validator: LedgerFileValidator,
    /** Sets `PRAGMA user_version` on a file, so SQLDelight treats it as an existing ledger. */
    private val stampVersion: (File, Long) -> Unit,
    private val schemaVersion: Long,
    private val ledger: LedgerHandle,
    /** Test hook: throw from a step to simulate a failure there. */
    private val beforeStep: (Step) -> Unit = {},
) {
    enum class Step { STAMP, BACKUP, MOVE_IN, REOPEN }

    val backupFile = File(databaseFile.path + ".bak")
    private val previousBackup = File(databaseFile.path + ".bak.old")
    private val discarded = File(databaseFile.path + ".discard")
    val incomingFile = File(cacheDir, "incoming.db")

    private val _hasBackup = MutableStateFlow(backupFile.exists())
    val hasBackup: StateFlow<Boolean> = _hasBackup.asStateFlow()

    /** Streams a picked document into the cache, stopping after [LedgerFileValidator.MAX_BYTES]. */
    fun copyIn(input: InputStream): File {
        cacheDir.mkdirs()
        val limit = LedgerFileValidator.MAX_BYTES
        try {
            input.use { source ->
                incomingFile.outputStream().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) throw FileTooLargeException(limit)
                        sink.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            incomingFile.delete()
            throw e
        }
        return incomingFile
    }

    fun check(file: File = incomingFile): LedgerFileValidator.Result = validator.validate(file)

    fun discardIncoming() {
        incomingFile.delete()
        journalsOf(incomingFile).forEach(File::delete)
    }

    /**
     * Swaps [incoming] in as the ledger. The current ledger becomes the backup, replacing any
     * older one. On failure the ledger and the backup are put back as they were.
     */
    @Synchronized
    fun replace(incoming: File = incomingFile) {
        beforeStep(Step.STAMP)
        stampVersion(incoming, schemaVersion)
        journalsOf(incoming).forEach(File::delete)

        ledger.close()
        var movedOldBackup = false
        var backedUp = false
        var movedIn = false
        try {
            beforeStep(Step.BACKUP)
            if (backupFile.exists()) {
                rename(backupFile, previousBackup)
                movedOldBackup = true
            }
            if (databaseFile.exists()) {
                rename(databaseFile, backupFile)
                backedUp = true
            }
            journalsOf(databaseFile).forEach(File::delete)

            beforeStep(Step.MOVE_IN)
            move(incoming, databaseFile)
            movedIn = true

            beforeStep(Step.REOPEN)
            ledger.reopen()
        } catch (e: Exception) {
            runCatching { ledger.close() }
            if (movedIn) {
                databaseFile.delete()
                journalsOf(databaseFile).forEach(File::delete)
            }
            if (backedUp) rename(backupFile, databaseFile)
            if (movedOldBackup) rename(previousBackup, backupFile)
            ledger.reopen()
            _hasBackup.value = backupFile.exists()
            throw e
        }
        previousBackup.delete()
        _hasBackup.value = true
    }

    /** Puts the backup back as the ledger and deletes the backup; the current ledger is discarded. */
    @Synchronized
    fun restorePrevious() {
        check(backupFile.exists()) { "There is no previous ledger to restore" }
        ledger.close()
        var setAside = false
        var restored = false
        try {
            beforeStep(Step.BACKUP)
            if (databaseFile.exists()) {
                rename(databaseFile, discarded)
                setAside = true
            }
            journalsOf(databaseFile).forEach(File::delete)

            beforeStep(Step.MOVE_IN)
            rename(backupFile, databaseFile)
            restored = true

            beforeStep(Step.REOPEN)
            ledger.reopen()
        } catch (e: Exception) {
            runCatching { ledger.close() }
            if (restored) rename(databaseFile, backupFile)
            if (setAside) rename(discarded, databaseFile)
            ledger.reopen()
            throw e
        }
        discarded.delete()
        _hasBackup.value = false
    }

    companion object {
        /**
         * Finishes or undoes a swap that was interrupted, for example by the app being killed
         * between renames. Call before opening the ledger.
         */
        fun recover(databaseFile: File) {
            val backup = File(databaseFile.path + ".bak")
            val previousBackup = File(databaseFile.path + ".bak.old")
            val discarded = File(databaseFile.path + ".discard")
            if (!databaseFile.exists()) {
                when {
                    discarded.exists() -> discarded.renameTo(databaseFile)
                    backup.exists() -> backup.renameTo(databaseFile)
                }
            }
            if (!backup.exists() && previousBackup.exists()) previousBackup.renameTo(backup)
            previousBackup.delete()
            discarded.delete()
        }

        private fun journalsOf(file: File) = listOf("-journal", "-wal", "-shm").map { File(file.path + it) }

        private fun rename(from: File, to: File) {
            to.delete()
            if (!from.renameTo(to)) throw IOException("Could not rename ${from.name} to ${to.name}")
        }

        /** Rename, or copy and delete when the files are on different file systems. */
        private fun move(from: File, to: File) {
            to.delete()
            if (from.renameTo(to)) return
            try {
                from.copyTo(to, overwrite = true)
            } catch (e: Exception) {
                to.delete()
                throw e
            }
            from.delete()
        }
    }
}
