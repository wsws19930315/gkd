package li.gkd.app.app

import li.gkd.app.appScope
import li.gkd.app.data.settings.SettingsRepository
import li.gkd.app.data.settings.SettingsStore
import li.gkd.app.data.snapshot.SnapshotRepository
import li.gkd.app.util.AppListString
import li.gkd.app.util.FolderUtils
import li.gkd.db.Db

object AppContainer {
    val settingsRepository by lazy {
        SettingsRepository(
            storeFolder = FolderUtils.storeFolder,
            scope = appScope,
            defaultSettings = { SettingsStore() },
            defaultBlockMatchAppList = AppListString::getDefaultBlockList,
        )
    }
    val snapshotRepository by lazy {
        SnapshotRepository(
            snapshotDao = Db.snapshotDao,
            snapshotRoot = FolderUtils.snapshotFolder,
        )
    }
}
