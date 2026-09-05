package li.gkd.app.feature.snapshot

import li.gkd.app.store.storeFlow
import li.gkd.app.store.settingsRepository
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.appInfoRepository
import li.gkd.app.util.ToastUtils.toast
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult

class SnapshotSettingsVm : BaseViewModel() {
    fun saveCaptureScreenshotConfig(
        appId: String,
        eventSelector: String,
    ): Boolean {
        val store = storeFlow.value
        if (
            appId == store.screenshotTargetAppId &&
            eventSelector == store.screenshotEventSelector
        ) {
            return true
        }
        if (appId.isNotEmpty() && !appInfoRepository.appInfoMapFlow.value.contains(appId)) {
            toast("无效应用ID")
            return false
        }
        if (
            eventSelector.isNotEmpty() &&
            Selector.compile(eventSelector) is SelectorCompileResult.Failure
        ) {
            toast("无效事件选择器")
            return false
        }
        settingsRepository.updateSettings {
            it.copy(
                screenshotTargetAppId = appId,
                screenshotEventSelector = eventSelector,
            )
        }
        toast("更新成功")
        return true
    }

    fun setCaptureVolumeChange(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(captureVolumeChange = enabled) }
    }

    fun setCaptureScreenshot(enabled: Boolean) {
        val store = storeFlow.value
        settingsRepository.updateSettings { it.copy(captureScreenshot = enabled) }
        if (
            enabled && (
                store.screenshotTargetAppId.isEmpty() ||
                    store.screenshotEventSelector.isEmpty()
            )
        ) {
            toast("请配置目标应用和特征事件选择器")
        }
    }

    fun setHideSnapshotStatusBar(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(hideSnapshotStatusBar = enabled) }
    }

    fun setAutoSaveSnapshotToDownloads(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(autoSaveSnapshotToDownloads = enabled) }
    }
}
