package li.gkd.app.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class SettingsRepositoryTest {
    @Test
    fun explicitUpdateChangesStateAndSurvivesRepositoryRecreation() = runBlocking {
        val directory = Files.createTempDirectory("gkd-settings-test").toFile()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = SettingsRepository(
                directory,
                writeScope,
                ::testDefaults,
                ::emptySet,
            )

            repository.updateSettings {
                it.copy(enableMatch = false, httpServerPort = 9123)
            }

            assertFalse(repository.settings.value.enableMatch)
            assertEquals(9123, repository.settings.value.httpServerPort)
            withTimeout(5_000) {
                while (!directory.resolve("store.json").isFile) delay(10)
            }

            val recreated = SettingsRepository(
                directory,
                readScope,
                ::testDefaults,
                ::emptySet,
            )
            assertFalse(recreated.settings.value.enableMatch)
            assertEquals(9123, recreated.settings.value.httpServerPort)
        } finally {
            writeScope.cancel()
            readScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentUpdatesPersistTheLatestState() = runBlocking {
        val directory = Files.createTempDirectory("gkd-settings-concurrent-test").toFile()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = SettingsRepository(
                directory,
                writeScope,
                ::testDefaults,
                ::emptySet,
            )

            coroutineScope {
                List(200) {
                    async(Dispatchers.Default) { repository.incrementActionCount() }
                }.awaitAll()
            }

            assertEquals(200L, repository.actionCount.value)
            withTimeout(5_000) {
                val file = directory.resolve("action_count.txt")
                while (file.takeIf { it.isFile }?.readText() != "200") delay(10)
            }
        } finally {
            writeScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun writerContinuesAfterOnePersistenceFailure() = runBlocking {
        val parent = Files.createTempDirectory("gkd-settings-recovery-test").toFile()
        val directory = parent.resolve("missing")
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = SettingsRepository(
                directory,
                writeScope,
                ::testDefaults,
                ::emptySet,
            )

            repository.incrementActionCount()
            withTimeout(5_000) {
                repository.persistenceFailures.first { it.isNotEmpty() }
            }

            directory.mkdirs()
            repository.incrementActionCount()
            withTimeout(5_000) { repository.awaitPersistence() }

            assertEquals("2", directory.resolve("action_count.txt").readText())
            assertEquals(emptyMap<String, Throwable>(), repository.persistenceFailures.value)
        } finally {
            writeScope.cancel()
            parent.deleteRecursively()
        }
    }

    private fun testDefaults() = SettingsStore(
        actionToast = "GKD",
        customNotifTitle = "GKD",
        updateChannel = 0,
    )
}
