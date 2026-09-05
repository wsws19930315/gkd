package li.gkd.app.data

import li.gkd.app.snapshotRepository
import li.gkd.app.util.format
import li.gkd.db.Snapshot

val Snapshot.screenshotFile
    get() = snapshotRepository.screenshotFile(id)

val Snapshot.date: String
    get() = id.format("MM-dd HH:mm:ss")
