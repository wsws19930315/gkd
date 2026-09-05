package li.gkd.app.data.backup

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.gkd.app.app.AppContainer
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.data.subscription.SubscriptionFileStore
import li.gkd.app.util.FolderUtils
import li.gkd.app.util.LogUtils
import li.gkd.app.util.ZipUtils
import li.gkd.app.util.json
import java.io.File
import java.io.IOException
import li.gkd.db.Db
import li.gkd.db.SubscriptionConfigSnapshot

private data class PreparedBackup(
    val dbData: SubscriptionConfigSnapshot?,
    val storeUpdates: List<() -> Unit>,
    val subscriptions: List<RawSubscription>,
)

private data class RestoreCheckpoint(
    val dbData: SubscriptionConfigSnapshot,
    val storeEntries: Map<String, String>,
    val subscriptionFiles: Map<Long, ByteArray?>,
)

object BackupManager {
    private val mutationMutex = Mutex()

    suspend fun exportData(): File = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            val tempDir = FolderUtils.createGkdTempDir()
            try {
                tempDir.resolve("store").run {
                    mkdir()
                    AppContainer.settingsRepository.exportBackupEntries().forEach { (filename, text) ->
                        resolve(filename).writeText(text)
                    }
                }
                tempDir.resolve("db.json").writeText(
                    BackupFormat.encode(
                        BackupDatabaseData.fromSnapshot(Db.subscriptionConfigStore.capture()),
                    ),
                )
                tempDir.resolve("subscription").run {
                    mkdir()
                    SubscriptionRepository.awaitSnapshot().subscriptions.values.forEach { subs ->
                        resolve("${subs.id}.json").writeText(json.encodeToString(subs))
                    }
                }
                val file = FolderUtils.sharedDir.resolve(
                    "gkd-backup-${System.currentTimeMillis()}.zip"
                )
                if (!ZipUtils.zipFiles(tempDir.listFiles().orEmpty().toList(), file)) {
                    throw IOException("备份压缩失败")
                }
                file
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    suspend fun importData(uri: Uri) = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            val tempDir = FolderUtils.createGkdTempDir()
            try {
                val zipFile = tempDir.resolve("file.zip")
                val unzipDir = tempDir.resolve("unzip")
                try {
                    BackupArchiveReader.extract(uri, zipFile, unzipDir)
                } catch (e: SecurityException) {
                    LogUtils.d("importBackUpData.openFile", e)
                    throw IllegalArgumentException("无法读取备份文件，请重新选择文件", e)
                } catch (e: Exception) {
                    LogUtils.d("importBackUpData.unzipFile", e)
                    throw IllegalArgumentException("解压失败，非法备份文件", e)
                }
                zipFile.delete()

                val prepared = prepareBackup(unzipDir)
                val checkpoint = captureCheckpoint(prepared)
                try {
                    withContext(NonCancellable) {
                        applyPreparedBackup(prepared)
                    }
                } catch (e: Throwable) {
                    withContext(NonCancellable) {
                        rollback(checkpoint, e)
                    }
                    throw e
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private suspend fun applyPreparedBackup(prepared: PreparedBackup): Int {
        val skipped = prepared.dbData?.let { Db.subscriptionConfigStore.merge(it) } ?: 0
        prepared.subscriptions.forEach { SubscriptionRepository.save(it) }
        SubscriptionRepository.reloadFromDisk()
        prepared.storeUpdates.forEach { it() }
        AppContainer.settingsRepository.awaitPersistence()
        return skipped
    }

    private suspend fun captureCheckpoint(prepared: PreparedBackup): RestoreCheckpoint {
        val subscriptionFiles = prepared.subscriptions.associate { subscription ->
            subscription.id to SubscriptionFileStore.readBytes(subscription.id)
        }
        return RestoreCheckpoint(
            dbData = Db.subscriptionConfigStore.capture(),
            storeEntries = AppContainer.settingsRepository.exportBackupEntries(),
            subscriptionFiles = subscriptionFiles,
        )
    }

    private suspend fun rollback(checkpoint: RestoreCheckpoint, cause: Throwable) {
        runCatching { Db.subscriptionConfigStore.restore(checkpoint.dbData) }
            .exceptionOrNull()?.let(cause::addSuppressed)
        runCatching { restoreSubscriptionFiles(checkpoint.subscriptionFiles) }
            .exceptionOrNull()?.let(cause::addSuppressed)
        runCatching {
            AppContainer.settingsRepository.prepareRestore(checkpoint.storeEntries).forEach { it() }
            AppContainer.settingsRepository.awaitPersistence()
        }.exceptionOrNull()?.let(cause::addSuppressed)
        runCatching { SubscriptionRepository.reloadFromDisk() }
            .exceptionOrNull()?.let(cause::addSuppressed)
    }

    private fun restoreSubscriptionFiles(files: Map<Long, ByteArray?>) {
        files.forEach { (id, bytes) ->
            SubscriptionFileStore.restore(id, bytes)
        }
    }

    private suspend fun prepareBackup(unzipDir: File): PreparedBackup =
        withContext(Dispatchers.Default) {
            val dbFile = unzipDir.resolve("db.json")
            val dbData = if (dbFile.exists() && dbFile.isFile) {
                BackupFormat.decode(dbFile.readText()).toSnapshot()
            } else {
                null
            }
            val storeEntries = AppContainer.settingsRepository.backupFilenames.mapNotNull { filename ->
                val file = unzipDir.resolve("store/$filename")
                if (!file.exists() || !file.isFile) return@mapNotNull null
                filename to file.readText()
            }
            val storeUpdates = AppContainer.settingsRepository.prepareRestore(storeEntries.toMap())
            val subsDir = unzipDir.resolve("subscription")
            val subscriptions = if (subsDir.exists() && subsDir.isDirectory) {
                (subsDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".json")
                } ?: emptyArray()).filterNotNull().sortedBy { it.name }.map { file ->
                    val fileId = file.nameWithoutExtension.toLongOrNull()
                        ?: error("非法订阅文件名: ${file.name}")
                    json.decodeFromString<RawSubscription>(file.readText()).also { subscription ->
                        require(subscription.id == fileId) {
                            "订阅文件id不一致: $fileId != ${subscription.id}"
                        }
                    }
                }.also { list ->
                    require(list.map { it.id }.distinct().size == list.size) {
                        "备份中存在重复订阅id"
                    }
                }
            } else {
                emptyList()
            }
            PreparedBackup(
                dbData = dbData,
                storeUpdates = storeUpdates,
                subscriptions = subscriptions,
            )
        }
}
