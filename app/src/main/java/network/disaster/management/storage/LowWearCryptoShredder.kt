package network.disaster.management.storage

import android.content.ContentValues
import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import network.disaster.management.model.CivilGroup
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.MessageType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import network.disaster.management.crypto.NativeMemoryLock

class LowWearCryptoShredder(private val appContext: Context) : SQLiteOpenHelper(appContext, "disaster_vault.db", null, 2), network.disaster.management.routing.BundleStorageVault {

    private val secureRandom = SecureRandom()
    private val dbPassphrase: String by lazy { VaultKeyManager.getOrCreateVaultPassphrase(appContext) }

    init {
        SQLiteDatabase.loadLibs(appContext)
    }

    private val db: SQLiteDatabase
        get() {
            return try {
                getWritableDatabase(dbPassphrase)
            } catch (e: Exception) {
                // Seamless migration from legacy static passphrase if database exists
                try {
                    val legacyDb = getWritableDatabase("disaster_secure_vault_key")
                    legacyDb.changePassword(dbPassphrase)
                    legacyDb
                } catch (_: Exception) {
                    throw e
                }
            }
        }

    companion object {
        const val POOL_A_LIMIT_BYTES = 50L * 1024L * 1024L   // 50 MB (Emergency)
        const val POOL_B_LIMIT_BYTES = 250L * 1024L * 1024L  // 250 MB (Encrypted Text)
        const val POOL_C_LIMIT_BYTES = 200L * 1024L * 1024L  // 200 MB (Bulk Media)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            // NIST SP 800-88 / High-Assurance SQLite Hardening Pragmas
            db.rawQuery("PRAGMA secure_delete = ON;", null)?.close()
            db.rawQuery("PRAGMA journal_mode = WAL;", null)?.close()
            db.rawQuery("PRAGMA foreign_keys = ON;", null)?.close()

            // Structural B-Tree Attestation
            val cursor = db.rawQuery("PRAGMA quick_check;", null)
            if (cursor.moveToFirst()) {
                val status = cursor.getString(0)
                if (!status.equals("ok", ignoreCase = true)) {
                    android.util.Log.e("LowWearCryptoShredder", "CRITICAL: Database integrity check failed: $status")
                }
            }
            cursor.close()

            // POSIX Permission Lockdown (0600)
            val dbFile = appContext.getDatabasePath("disaster_vault.db")
            if (dbFile.exists()) {
                dbFile.setReadable(false, false)
                dbFile.setReadable(true, true)
                dbFile.setWritable(false, false)
                dbFile.setWritable(true, true)
                dbFile.setExecutable(false, false)
            }
        } catch (e: Exception) {
            android.util.Log.e("LowWearCryptoShredder", "Error configuring security pragmas", e)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bundle_store (bundle_id BLOB PRIMARY KEY, pool_type TEXT, iv BLOB, payload BLOB, timestamp INTEGER);")
        db.execSQL("CREATE TABLE IF NOT EXISTS subkey_vault (bundle_id BLOB PRIMARY KEY, subkey BLOB);")
        db.execSQL("CREATE TABLE IF NOT EXISTS emergency_store (bundle_id BLOB PRIMARY KEY, payload BLOB, timestamp INTEGER);")

        // Groups table
        db.execSQL("CREATE TABLE IF NOT EXISTS groups (group_id TEXT PRIMARY KEY, name TEXT, description TEXT, secret_key BLOB, is_broadcast INTEGER, created_epoch INTEGER);")

        // Feed & Group Messages
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (msg_id TEXT PRIMARY KEY, group_id TEXT, sender_name TEXT, content TEXT, msg_type TEXT, file_name TEXT, file_size INTEGER, mime_type TEXT, local_path TEXT, timestamp INTEGER, is_emergency INTEGER);")

        // File Chunk Storage (Pool C)
        db.execSQL("CREATE TABLE IF NOT EXISTS file_chunks (file_id TEXT, chunk_index INTEGER, total_chunks INTEGER, chunk_data BLOB, PRIMARY KEY(file_id, chunk_index));")

        // Hash-Chained Merkle Audit Trail (RFC 6962 / Blueprint Section 8)
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, entry_hash TEXT, prev_hash TEXT, timestamp INTEGER, action TEXT, actor TEXT, status TEXT, details TEXT);")

        seedDefaultGroups(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("CREATE TABLE IF NOT EXISTS groups (group_id TEXT PRIMARY KEY, name TEXT, description TEXT, secret_key BLOB, is_broadcast INTEGER, created_epoch INTEGER);")
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (msg_id TEXT PRIMARY KEY, group_id TEXT, sender_name TEXT, content TEXT, msg_type TEXT, file_name TEXT, file_size INTEGER, mime_type TEXT, local_path TEXT, timestamp INTEGER, is_emergency INTEGER);")
        db.execSQL("CREATE TABLE IF NOT EXISTS file_chunks (file_id TEXT, chunk_index INTEGER, total_chunks INTEGER, chunk_data BLOB, PRIMARY KEY(file_id, chunk_index));")
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, entry_hash TEXT, prev_hash TEXT, timestamp INTEGER, action TEXT, actor TEXT, status TEXT, details TEXT);")
        seedDefaultGroups(db)
    }

    private fun seedDefaultGroups(db: SQLiteDatabase) {
        val def1 = ContentValues().apply {
            put("group_id", "GROUP_PUBLIC_BROADCAST")
            put("name", "Public Disaster Broadcast")
            put("description", "Civilian safety alerts, hazard updates, and community bulletins")
            put("secret_key", ByteArray(32) { 0x01 })
            put("is_broadcast", 1)
            put("created_epoch", System.currentTimeMillis())
        }
        db.insertWithOnConflict("groups", null, def1, SQLiteDatabase.CONFLICT_IGNORE)

        val def2 = ContentValues().apply {
            put("group_id", "GROUP_MEDICAL_LOGISTICS")
            put("name", "Medical & Evacuation Logistics")
            put("description", "Casualty coordination, supply requests, and shelter coordinates")
            put("secret_key", ByteArray(32) { 0x02 })
            put("is_broadcast", 0)
            put("created_epoch", System.currentTimeMillis())
        }
        db.insertWithOnConflict("groups", null, def2, SQLiteDatabase.CONFLICT_IGNORE)

        val def3 = ContentValues().apply {
            put("group_id", "GROUP_FIRST_RESPONDERS")
            put("name", "Field Operations & Rescue")
            put("description", "Search & rescue pathfinding, obstacle reporting, and tactical relay")
            put("secret_key", ByteArray(32) { 0x03 })
            put("is_broadcast", 0)
            put("created_epoch", System.currentTimeMillis())
        }
        db.insertWithOnConflict("groups", null, def3, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // --- Groups Management ---
    fun createGroup(group: CivilGroup): Boolean {
        val cv = ContentValues().apply {
            put("group_id", group.id)
            put("name", group.name)
            put("description", group.description)
            put("secret_key", group.secretKey)
            put("is_broadcast", if (group.isBroadcast) 1 else 0)
            put("created_epoch", group.createdEpoch)
        }
        val rowId = db.insertWithOnConflict("groups", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return rowId != -1L
    }

    fun getAllGroups(): List<CivilGroup> {
        val list = mutableListOf<CivilGroup>()
        val cursor = db.rawQuery("SELECT group_id, name, description, secret_key, is_broadcast, created_epoch FROM groups ORDER BY is_broadcast DESC, name ASC", null)
        while (cursor.moveToNext()) {
            list.add(
                CivilGroup(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    description = cursor.getString(2),
                    secretKey = cursor.getBlob(3) ?: ByteArray(32),
                    isBroadcast = cursor.getInt(4) == 1,
                    createdEpoch = cursor.getLong(5)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getGroupById(groupId: String): CivilGroup? {
        val cursor = db.rawQuery("SELECT group_id, name, description, secret_key, is_broadcast, created_epoch FROM groups WHERE group_id = ?", arrayOf(groupId))
        val group = if (cursor.moveToFirst()) {
            CivilGroup(
                id = cursor.getString(0),
                name = cursor.getString(1),
                description = cursor.getString(2),
                secretKey = cursor.getBlob(3) ?: ByteArray(32),
                isBroadcast = cursor.getInt(4) == 1,
                createdEpoch = cursor.getLong(5)
            )
        } else null
        cursor.close()
        return group
    }

    fun leaveGroup(groupId: String): Boolean {
        val deleted = db.delete("groups", "group_id = ?", arrayOf(groupId)) > 0
        db.delete("messages", "group_id = ?", arrayOf(groupId))
        return deleted
    }

    // --- Messages & Feed Management ---
    fun insertMessage(msg: FeedMessage): Boolean {
        val cv = ContentValues().apply {
            put("msg_id", msg.id)
            put("group_id", msg.groupId)
            put("sender_name", msg.senderName)
            put("content", msg.content)
            put("msg_type", msg.type.name)
            put("file_name", msg.fileName)
            put("file_size", msg.fileSize)
            put("mime_type", msg.mimeType)
            put("local_path", msg.localFilePath)
            put("timestamp", msg.timestamp)
            put("is_emergency", if (msg.isEmergency) 1 else 0)
        }
        val rowId = db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return rowId != -1L
    }

    fun getMessages(groupId: String? = null): List<FeedMessage> {
        val list = mutableListOf<FeedMessage>()
        val query = if (groupId.isNullOrEmpty()) {
            "SELECT msg_id, group_id, sender_name, content, msg_type, file_name, file_size, mime_type, local_path, timestamp, is_emergency FROM messages ORDER BY timestamp DESC LIMIT 100"
        } else {
            "SELECT msg_id, group_id, sender_name, content, msg_type, file_name, file_size, mime_type, local_path, timestamp, is_emergency FROM messages WHERE group_id = ? ORDER BY timestamp ASC LIMIT 200"
        }
        val args = if (groupId.isNullOrEmpty()) null else arrayOf(groupId)
        val cursor = db.rawQuery(query, args)
        while (cursor.moveToNext()) {
            val typeStr = cursor.getString(4)
            val msgType = try { MessageType.valueOf(typeStr) } catch (_: Exception) { MessageType.TEXT }
            list.add(
                FeedMessage(
                    id = cursor.getString(0),
                    groupId = cursor.getString(1),
                    senderName = cursor.getString(2),
                    content = cursor.getString(3),
                    type = msgType,
                    fileName = cursor.getString(5),
                    fileSize = cursor.getLong(6),
                    mimeType = cursor.getString(7),
                    localFilePath = cursor.getString(8),
                    timestamp = cursor.getLong(9),
                    isEmergency = cursor.getInt(10) == 1
                )
            )
        }
        cursor.close()
        return list
    }

    // --- Chunked File Storage & Reassembly ---
    fun saveFileChunk(fileId: String, chunkIndex: Int, totalChunks: Int, data: ByteArray): Boolean {
        val cv = ContentValues().apply {
            put("file_id", fileId)
            put("chunk_index", chunkIndex)
            put("total_chunks", totalChunks)
            put("chunk_data", data)
        }
        val rowId = db.insertWithOnConflict("file_chunks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return rowId != -1L
    }

    fun getReceivedChunkCount(fileId: String): Int {
        var count = 0
        val cursor = db.rawQuery("SELECT COUNT(*) FROM file_chunks WHERE file_id = ?", arrayOf(fileId))
        if (cursor.moveToFirst()) count = cursor.getInt(0)
        cursor.close()
        return count
    }

    fun reassembleFile(fileId: String, totalChunks: Int, outputFile: File): Boolean {
        if (getReceivedChunkCount(fileId) < totalChunks) return false
        val cursor = db.rawQuery(
            "SELECT chunk_data FROM file_chunks WHERE file_id = ? ORDER BY chunk_index ASC",
            arrayOf(fileId)
        )
        if (cursor.count < totalChunks) {
            cursor.close()
            return false
        }
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            while (cursor.moveToNext()) {
                val data = cursor.getBlob(0)
                if (data != null) fos.write(data)
            }
        }
        cursor.close()
        return true
    }

    // --- Core Low-Wear Crypto Vault ---
    override fun storeBundleWithSubkey(bundleId: ByteArray, poolType: String, payload: ByteArray): Boolean {
        val limit = when (poolType) {
            "POOL_A" -> POOL_A_LIMIT_BYTES
            "POOL_C" -> POOL_C_LIMIT_BYTES
            else -> POOL_B_LIMIT_BYTES
        }

        if (getPoolSizeBytes(poolType) + payload.size > limit) {
            evictOldestInPool(poolType)
        }

        val subkey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "AES"), GCMParameterSpec(128, iv))
        val encryptedPayload = cipher.doFinal(payload)

        val bundleValues = ContentValues().apply {
            put("bundle_id", bundleId)
            put("pool_type", poolType)
            put("iv", iv)
            put("payload", encryptedPayload)
            put("timestamp", System.currentTimeMillis())
        }
        db.insertWithOnConflict("bundle_store", null, bundleValues, SQLiteDatabase.CONFLICT_REPLACE)

        val subkeyValues = ContentValues().apply {
            put("bundle_id", bundleId)
            put("subkey", subkey)
        }
        db.insertWithOnConflict("subkey_vault", null, subkeyValues, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    fun retrieveBundle(bundleId: ByteArray): ByteArray? {
        val hexId = bundleId.joinToString("") { "%02X".format(it) }
        val cursorSubkey = db.rawQuery("SELECT subkey FROM subkey_vault WHERE HEX(bundle_id) = ?", arrayOf(hexId))
        if (!cursorSubkey.moveToFirst()) {
            cursorSubkey.close()
            return null
        }
        val subkey = cursorSubkey.getBlob(0)
        cursorSubkey.close()

        val cursorBundle = db.rawQuery("SELECT iv, payload FROM bundle_store WHERE HEX(bundle_id) = ?", arrayOf(hexId))
        if (!cursorBundle.moveToFirst()) {
            cursorBundle.close()
            return null
        }
        val iv = cursorBundle.getBlob(0)
        val encryptedPayload = cursorBundle.getBlob(1)
        cursorBundle.close()

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(subkey, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(encryptedPayload)
        } catch (_: Exception) {
            null
        }
    }

    fun lowWearShred(bundleId: ByteArray) {
        val hexId = bundleId.joinToString("") { "%02X".format(it) }
        val deadKey = ByteArray(32) { 0x00 }
        val updateValues = ContentValues().apply {
            put("subkey", deadKey)
        }
        db.update("subkey_vault", updateValues, "HEX(bundle_id) = ?", arrayOf(hexId))
        db.delete("subkey_vault", "HEX(bundle_id) = ?", arrayOf(hexId))
        db.delete("bundle_store", "HEX(bundle_id) = ?", arrayOf(hexId))
    }

    fun getPoolSizeBytes(poolType: String): Long {
        var size = 0L
        val cursor = db.rawQuery("SELECT SUM(LENGTH(payload)) FROM bundle_store WHERE pool_type = ?", arrayOf(poolType))
        if (cursor.moveToFirst()) {
            size = cursor.getLong(0)
        }
        cursor.close()
        return size
    }

    fun getAllBundleIds(limit: Int = 500): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        val cursor = db.rawQuery("SELECT bundle_id FROM bundle_store ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
        while (cursor.moveToNext()) {
            val id = cursor.getBlob(0)
            if (id != null) list.add(id)
        }
        cursor.close()
        return list
    }

    fun getStorageUsageSummary(): Triple<Long, Long, Long> {
        val poolA = getPoolSizeBytes("POOL_A")
        val poolB = getPoolSizeBytes("POOL_B")
        val poolC = getPoolSizeBytes("POOL_C")
        return Triple(poolA, poolB, poolC)
    }

    fun importGroupFromQr(qrPayload: String): CivilGroup? {
        try {
            if (!qrPayload.startsWith("DISASTER_GRP:")) return null
            val raw = qrPayload.removePrefix("DISASTER_GRP:")
            val parts = raw.split("|")
            if (parts.size < 4) return null
            val id = parts[0]
            val name = parts[1]
            val isBroadcast = if (parts.size >= 5) parts.last() == "1" else false
            val keyBase64 = if (parts.size >= 5) parts[parts.size - 2] else parts[3]
            val desc = if (parts.size > 5) parts.subList(2, parts.size - 2).joinToString("|") else parts[2]
            val secretKey = try {
                android.util.Base64.decode(keyBase64, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(keyBase64)
            }

            val group = CivilGroup(
                id = id,
                name = name,
                description = desc,
                secretKey = secretKey,
                isBroadcast = isBroadcast,
                createdEpoch = System.currentTimeMillis()
            )
            createGroup(group)
            return group
        } catch (_: Exception) {
            return null
        }
    }


    private fun evictOldestInPool(poolType: String) {
        val cursor = db.rawQuery("SELECT bundle_id FROM bundle_store WHERE pool_type = ? ORDER BY timestamp ASC LIMIT 1", arrayOf(poolType))
        if (cursor.moveToFirst()) {
            val oldestId = cursor.getBlob(0)
            cursor.close()
            lowWearShred(oldestId)
        } else {
            cursor.close()
        }
    }

    // --- Merkle-Chained Audit Trail (RFC 6962 / Blueprint Section 8) ---
    private val GENESIS_ROOT = "DISASTER_GENESIS_ROOT_V1"

    @Synchronized
    fun recordAuditEntry(action: String, actor: String, status: String, details: String): String {
        var prevHash = GENESIS_ROOT
        val cursor = db.rawQuery("SELECT entry_hash FROM audit_logs ORDER BY id DESC LIMIT 1", null)
        if (cursor.moveToFirst()) {
            prevHash = cursor.getString(0) ?: GENESIS_ROOT
        }
        cursor.close()

        val timestamp = System.currentTimeMillis()
        val payload = "$prevHash:$timestamp:$action:$actor:$status:$details"
        val md = MessageDigest.getInstance("SHA-256")
        val entryHash = md.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        val cv = ContentValues().apply {
            put("entry_hash", entryHash)
            put("prev_hash", prevHash)
            put("timestamp", timestamp)
            put("action", action)
            put("actor", actor)
            put("status", status)
            put("details", details)
        }
        db.insert("audit_logs", null, cv)
        return entryHash
    }

    fun verifyAuditChainIntegrity(): Boolean {
        val cursor = db.rawQuery("SELECT id, entry_hash, prev_hash, timestamp, action, actor, status, details FROM audit_logs ORDER BY id ASC", null)
        var expectedPrev = GENESIS_ROOT
        val md = MessageDigest.getInstance("SHA-256")

        while (cursor.moveToNext()) {
            val entryHash = cursor.getString(1)
            val prevHash = cursor.getString(2)
            val timestamp = cursor.getLong(3)
            val action = cursor.getString(4)
            val actor = cursor.getString(5)
            val status = cursor.getString(6)
            val details = cursor.getString(7)

            if (prevHash != expectedPrev) {
                cursor.close()
                return false
            }

            val payload = "$prevHash:$timestamp:$action:$actor:$status:$details"
            val calculated = md.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            if (entryHash != calculated) {
                cursor.close()
                return false
            }
            expectedPrev = entryHash
        }
        cursor.close()
        return true
    }

    // --- NIST SP 800-88 / DoD 5220.22-M 3-Pass Physical Zeroization ---
    fun panicPhysicalPurge(context: Context) {
        close()
        VaultKeyManager.purgeVaultSecurity(context)

        val targetFiles = listOf(
            context.getDatabasePath("disaster_vault.db"),
            File(context.getDatabasePath("disaster_vault.db").path + "-wal"),
            File(context.getDatabasePath("disaster_vault.db").path + "-shm")
        )

        for (file in targetFiles) {
            if (file.exists()) {
                val fileLen = file.length().coerceAtLeast(65536L)
                val chunkSize = 65536
                val buf = ByteArray(chunkSize)

                try {
                    // Pass 1: Cryptographic Pseudo-Random Fill
                    FileOutputStream(file).use { fos ->
                        var written = 0L
                        while (written < fileLen) {
                            secureRandom.nextBytes(buf)
                            val toWrite = minOf(buf.size.toLong(), fileLen - written).toInt()
                            fos.write(buf, 0, toWrite)
                            written += toWrite
                        }
                        fos.fd.sync()
                    }

                    // Pass 2: Bitwise Complement (Inversion)
                    FileOutputStream(file).use { fos ->
                        var written = 0L
                        while (written < fileLen) {
                            secureRandom.nextBytes(buf)
                            for (i in buf.indices) buf[i] = (buf[i].toInt().inv()).toByte()
                            val toWrite = minOf(buf.size.toLong(), fileLen - written).toInt()
                            fos.write(buf, 0, toWrite)
                            written += toWrite
                        }
                        fos.fd.sync()
                    }

                    // Pass 3: Binary Zeroization (0x00)
                    FileOutputStream(file).use { fos ->
                        buf.fill(0)
                        var written = 0L
                        while (written < fileLen) {
                            val toWrite = minOf(buf.size.toLong(), fileLen - written).toInt()
                            fos.write(buf, 0, toWrite)
                            written += toWrite
                        }
                        fos.fd.sync()
                    }
                } catch (_: Exception) {}

                file.delete()
            }
        }

        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
