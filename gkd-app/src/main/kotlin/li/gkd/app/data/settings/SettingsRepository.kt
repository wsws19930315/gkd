package li.gkd.app.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.gkd.app.util.AppListString
import li.gkd.app.util.LogUtils
import li.gkd.app.util.json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private class PersistedValue<T>(
    val filename: String,
    file: File,
    private val decode: (String?) -> T,
    private val encode: (T) -> String,
    private val onWriteResult: (Throwable?) -> Unit,
    scope: CoroutineScope,
) {
    private data class WriteRequest<T>(
        val version: Long,
        val value: T,
    )

    private data class WriteResult(
        val version: Long,
        val error: Throwable?,
    )

    private val mutableState = MutableStateFlow(
        decode(file.takeIf { it.exists() }?.readText())
    )
    val state: StateFlow<T>
        field = mutableState

    private val writeRequests = Channel<WriteRequest<T>>(Channel.CONFLATED)
    private val writeResult = MutableStateFlow(WriteResult(version = 0, error = null))
    private var currentVersion = 0L

    init {
        scope.launch(Dispatchers.IO) {
            for (request in writeRequests) {
                val tempFile = File("${file.absolutePath}.tmp")
                val result = try {
                    tempFile.outputStream().use {
                        it.write(encode(request.value).toByteArray(Charsets.UTF_8))
                        it.fd.sync()
                    }
                    Files.move(
                        tempFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    WriteResult(request.version, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WriteResult(request.version, e)
                } finally {
                    tempFile.delete()
                }
                writeResult.value = result
                runCatching { onWriteResult(result.error) }
                result.error?.let { error ->
                    runCatching { LogUtils.d("设置写入失败: $filename", error) }
                }
            }
        }
    }

    fun encodeCurrent(): String = encode(state.value)

    fun prepareRestore(text: String): () -> Unit {
        val value = decode(text)
        return { replace(value) }
    }

    @Synchronized
    fun replace(value: T) {
        mutableState.value = value
        enqueue(value)
    }

    @Synchronized
    fun update(transform: (T) -> T): T {
        val value = transform(mutableState.value)
        mutableState.value = value
        enqueue(value)
        return value
    }

    suspend fun awaitPersistence() {
        val targetVersion = synchronized(this) { currentVersion }
        val result = writeResult.first { it.version >= targetVersion }
        result.error?.let { throw IOException("设置写入失败: $filename", it) }
    }

    private fun enqueue(value: T) {
        currentVersion += 1
        val request = WriteRequest(currentVersion, value)
        check(writeRequests.trySend(request).isSuccess) { "设置写入队列已关闭: $filename" }
    }
}

class SettingsRepository(
    storeFolder: File,
    scope: CoroutineScope,
    defaultSettings: () -> SettingsStore,
    defaultBlockMatchAppList: () -> Set<String>,
) {
    val persistenceFailures: StateFlow<Map<String, Throwable>>
        field = MutableStateFlow(emptyMap())

    private fun persistedValueWriteResult(filename: String, error: Throwable?) {
        persistenceFailures.update { failures ->
            if (error == null) {
                failures - filename
            } else {
                failures + (filename to error)
            }
        }
    }

    private val settingsValue = PersistedValue(
        filename = "store.json",
        file = storeFolder.resolve("store.json"),
        decode = { text ->
            text?.let { runCatching { json.decodeFromString<SettingsStore>(it) }.getOrNull() }
                ?: defaultSettings()
        },
        encode = { json.encodeToString(it) },
        onWriteResult = { persistedValueWriteResult("store.json", it) },
        scope = scope,
    )
    private val actionCountValue = PersistedValue(
        filename = "action_count.txt",
        file = storeFolder.resolve("action_count.txt"),
        decode = { it?.toLongOrNull() ?: 0L },
        encode = { it.toString() },
        onWriteResult = { persistedValueWriteResult("action_count.txt", it) },
        scope = scope,
    )
    private val blockMatchAppListValue = PersistedValue(
        filename = "block_match_app_list.txt",
        file = storeFolder.resolve("block_match_app_list.txt"),
        decode = { it?.let(AppListString::decode) ?: defaultBlockMatchAppList() },
        encode = AppListString::encode,
        onWriteResult = { persistedValueWriteResult("block_match_app_list.txt", it) },
        scope = scope,
    )
    private val blockA11yAppListValue = PersistedValue(
        filename = "block_a11y_app_list.txt",
        file = storeFolder.resolve("block_a11y_app_list.txt"),
        decode = { it?.let(AppListString::decode) ?: emptySet() },
        encode = AppListString::encode,
        onWriteResult = { persistedValueWriteResult("block_a11y_app_list.txt", it) },
        scope = scope,
    )
    private val a11yScopeAppListValue = PersistedValue(
        filename = "a11y_scope_app_list.txt",
        file = storeFolder.resolve("a11y_scope_app_list.txt"),
        decode = { it?.let(AppListString::decode) ?: setOf("com.tencent.mm") },
        encode = AppListString::encode,
        onWriteResult = { persistedValueWriteResult("a11y_scope_app_list.txt", it) },
        scope = scope,
    )

    val settings: StateFlow<SettingsStore> = settingsValue.state
    val actionCount: StateFlow<Long> = actionCountValue.state
    val blockMatchAppList: StateFlow<Set<String>> = blockMatchAppListValue.state
    val blockA11yAppList: StateFlow<Set<String>> = blockA11yAppListValue.state
    val a11yScopeAppList: StateFlow<Set<String>> = a11yScopeAppListValue.state

    val backupFilenames: Set<String> = setOf(
        settingsValue.filename,
        actionCountValue.filename,
        blockMatchAppListValue.filename,
        blockA11yAppListValue.filename,
        a11yScopeAppListValue.filename,
    )

    fun updateSettings(transform: (SettingsStore) -> SettingsStore): SettingsStore =
        settingsValue.update(transform)

    fun incrementActionCount(): Long = actionCountValue.update { it + 1 }

    fun updateBlockMatchAppList(transform: (Set<String>) -> Set<String>): Set<String> =
        blockMatchAppListValue.update(transform)

    fun replaceBlockMatchAppList(value: Set<String>) = blockMatchAppListValue.replace(value)

    fun updateBlockA11yAppList(transform: (Set<String>) -> Set<String>): Set<String> =
        blockA11yAppListValue.update(transform)

    fun replaceBlockA11yAppList(value: Set<String>) = blockA11yAppListValue.replace(value)

    fun updateA11yScopeAppList(transform: (Set<String>) -> Set<String>): Set<String> =
        a11yScopeAppListValue.update(transform)

    fun replaceA11yScopeAppList(value: Set<String>) = a11yScopeAppListValue.replace(value)

    fun exportBackupEntries(): Map<String, String> = mapOf(
        settingsValue.filename to settingsValue.encodeCurrent(),
        actionCountValue.filename to actionCountValue.encodeCurrent(),
        blockMatchAppListValue.filename to blockMatchAppListValue.encodeCurrent(),
        blockA11yAppListValue.filename to blockA11yAppListValue.encodeCurrent(),
        a11yScopeAppListValue.filename to a11yScopeAppListValue.encodeCurrent(),
    )

    fun prepareRestore(entries: Map<String, String>): List<() -> Unit> = buildList {
        entries[settingsValue.filename]?.let { add(settingsValue.prepareRestore(it)) }
        entries[actionCountValue.filename]?.let { add(actionCountValue.prepareRestore(it)) }
        entries[blockMatchAppListValue.filename]?.let {
            add(blockMatchAppListValue.prepareRestore(it))
        }
        entries[blockA11yAppListValue.filename]?.let {
            add(blockA11yAppListValue.prepareRestore(it))
        }
        entries[a11yScopeAppListValue.filename]?.let {
            add(a11yScopeAppListValue.prepareRestore(it))
        }
    }

    suspend fun awaitPersistence() = coroutineScope {
        listOf(
            settingsValue,
            actionCountValue,
            blockMatchAppListValue,
            blockA11yAppListValue,
            a11yScopeAppListValue,
        ).map { value -> async { value.awaitPersistence() } }.awaitAll()
        Unit
    }
}
