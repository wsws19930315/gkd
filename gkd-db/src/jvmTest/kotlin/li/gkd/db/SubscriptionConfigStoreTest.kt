package li.gkd.db

import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubscriptionConfigStoreTest {
    private fun withDatabase(block: suspend (AppDb, SubscriptionConfigStore) -> Unit) = runBlocking {
        val directory = createTempDirectory("config-store-test")
        val database = Room.databaseBuilder<AppDb>(directory.resolve("test.db").toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            block(database, SubscriptionConfigStore(database))
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun sample(subsId: Long = 7) = SubscriptionConfigSnapshot(
        subsItems = listOf(SubsItem(subsId, ctime = 1, mtime = 2, enable = true, order = 0)),
        appConfigs = listOf(SubsAppConfig(false, subsId, "app.one")),
        categoryConfigs = listOf(SubsCategoryConfig(null, subsId, 3)),
        appGroupConfigs = listOf(SubsAppGroupConfig(subsId, "app.one", 4, false, "app.Activity")),
        globalGroupConfigs = listOf(SubsGlobalGroupConfig(subsId, 4, true, "app.two")),
    )

    @Test
    fun importingTwiceKeepsLocalBusinessKeysAndAddsOnlyMissingOverrides() = withDatabase { _, store ->
        val local = sample()
        store.merge(local)
        val imported = local.copy(
            appConfigs = listOf(SubsAppConfig(true, 7, "app.one"), SubsAppConfig(true, 99, "orphan")),
            categoryConfigs = listOf(SubsCategoryConfig(false, 7, 3)),
            appGroupConfigs = listOf(SubsAppGroupConfig(7, "app.one", 4, true, "changed")),
            globalGroupConfigs = listOf(SubsGlobalGroupConfig(7, 4, false), SubsGlobalGroupConfig(7, 5, false)),
        )
        assertEquals(1, store.merge(imported))
        assertEquals(1, store.merge(imported))
        assertEquals(local.copy(globalGroupConfigs = local.globalGroupConfigs + SubsGlobalGroupConfig(7, 5, false)), store.capture())
    }

    @Test
    fun subscriptionUpsertPreservesOverridesAndDeletionCascadesToAllFourTables() = withDatabase { db, store ->
        val original = sample()
        store.merge(original)
        val updatedItem = original.subsItems.single().copy(mtime = 3, enable = false)
        db.subsItemDao().upsert(updatedItem)
        assertEquals(original.copy(subsItems = listOf(updatedItem)), store.capture())
        db.subsItemDao().deleteById(7)
        assertEquals(SubscriptionConfigSnapshot(), store.capture())
    }

    @Test
    fun checkpointRestoresDeletedAndChangedOverridesAndRemovesImportedRows() = withDatabase { db, store ->
        val original = sample()
        store.merge(original)
        val checkpoint = store.capture()
        store.merge(sample(8))
        db.subsCategoryConfigDao().upsert(SubsCategoryConfig(false, 7, 3))
        db.subsAppGroupConfigDao().delete(*original.appGroupConfigs.toTypedArray())
        store.restore(checkpoint)
        assertEquals(original, store.capture())

        db.subsItemDao().deleteById(7)
        store.restore(checkpoint)
        assertEquals(original, store.capture())
    }

    @Test
    fun observedSnapshotsContainCompleteTransactionsAcrossBothGroupTables() = withDatabase { db, store ->
        coroutineScope {
            val snapshots = Channel<SubscriptionConfigSnapshot>(Channel.UNLIMITED)
            val collector = launch { store.observe().collect { snapshots.send(it) } }
            try {
                withTimeout(5_000) {
                    assertEquals(SubscriptionConfigSnapshot(), snapshots.receive())
                    val original = sample()
                    store.merge(original)
                    assertEquals(original, snapshots.receive())
                    val app = original.appGroupConfigs.single().copy(enable = true)
                    val global = original.globalGroupConfigs.single().copy(enable = false)
                    db.withWriteTransaction {
                        db.subsAppGroupConfigDao().upsert(app)
                        yield()
                        db.subsGlobalGroupConfigDao().upsert(global)
                    }
                    assertEquals(
                        original.copy(appGroupConfigs = listOf(app), globalGroupConfigs = listOf(global)),
                        snapshots.receive(),
                    )
                }
            } finally {
                collector.cancelAndJoin()
                snapshots.close()
            }
        }
    }

    @Test
    fun failedImportTransactionRollsBackAllConfigurationTables() = withDatabase { db, store ->
        store.merge(sample())
        val before = store.capture()
        assertFailsWith<IllegalStateException> {
            db.withWriteTransaction {
                store.merge(sample(8))
                error("import failed")
            }
        }
        assertEquals(before, store.capture())
    }
}
