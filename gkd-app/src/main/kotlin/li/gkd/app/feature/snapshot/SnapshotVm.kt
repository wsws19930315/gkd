package li.gkd.app.feature.snapshot

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import li.gkd.app.data.screenshotFile
import li.gkd.app.snapshotRepository
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.util.ImageUtils
import li.gkd.app.appInfoRepository
import li.gkd.db.Snapshot
import java.io.File

data class SnapshotUiState(
    val snapshots: List<Snapshot>,
    val appNames: Map<String, String>,
)

class SnapshotVm : BaseViewModel() {
    private val snapshotsFlow = snapshotRepository.snapshots()

    val uiState = combine(
        snapshotsFlow,
        appInfoRepository.appInfoMapFlow,
    ) { snapshots, appInfoMap ->
        SnapshotUiState(
            snapshots = snapshots,
            appNames = appInfoMap.mapValues { it.value.name },
        )
    }.stateLoadable()

    suspend fun deleteAllSnapshots() = snapshotRepository.deleteAll()

    suspend fun deleteSnapshot(snapshot: Snapshot) = snapshotRepository.delete(snapshot)

    suspend fun buildShareArchive(snapshot: Snapshot): File {
        return snapshotRepository.createArchive(snapshot.id, snapshot.appId, snapshot.activityId)
    }

    suspend fun buildUploadArchive(snapshot: Snapshot): File =
        snapshotRepository.createArchive(snapshot.id)

    suspend fun saveScreenshotToAlbum(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        ImageUtils.save2Album(BitmapFactory.decodeFile(snapshot.screenshotFile.absolutePath))
    }

    suspend fun markUploaded(snapshot: Snapshot, githubAssetId: Int) =
        snapshotRepository.markUploaded(snapshot, githubAssetId)

    suspend fun replaceScreenshot(snapshot: Snapshot, newBytes: ByteArray): Boolean {
        return snapshotRepository.replaceScreenshot(snapshot, newBytes)
    }
}
