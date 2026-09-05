package li.gkd.app.ui.home

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.gkd.app.service.TrackService
import li.gkd.app.service.fixRestartAutomatorService
import li.gkd.app.store.storeFlow
import li.gkd.app.store.settingsRepository
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.backupManager
import li.gkd.app.util.ToastUtils.toast
import java.io.File

class SettingsVm : BaseViewModel() {

    fun saveActionToast(value: String): Boolean {
        if (value == storeFlow.value.actionToast) return false
        settingsRepository.updateSettings { it.copy(actionToast = value) }
        return true
    }

    fun saveNotificationText(title: String, text: String): Boolean {
        val store = storeFlow.value
        if (store.customNotifTitle == title && store.customNotifText == text) return false
        settingsRepository.updateSettings {
            it.copy(
                customNotifTitle = title,
                customNotifText = text,
            )
        }
        return true
    }

    fun setToastWhenClick(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(toastWhenClick = enabled) }
    }

    fun setUseSystemToast(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(useSystemToast = enabled) }
    }

    fun setTrackServiceEnabled(enabled: Boolean) {
        if (enabled) TrackService.start() else TrackService.stop()
    }

    fun setUseCustomNotificationText(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(useCustomNotifText = enabled) }
    }

    fun setExcludeFromRecents(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(excludeFromRecents = enabled) }
    }

    fun setBlockA11yAppListEnabled(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(enableBlockA11yAppList = enabled) }
        if (!enabled) {
            fixRestartAutomatorService()
        }
    }

    fun setDarkTheme(value: Boolean?) {
        settingsRepository.updateSettings { it.copy(enableDarkTheme = value) }
    }

    fun setDynamicColor(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(enableDynamicColor = enabled) }
    }

    suspend fun importBackup(uri: Uri) {
        toast("导入备份中...")
        val skipped = withContext(Dispatchers.IO) {
            backupManager.importData(uri)
        }
        toast(if (skipped > 0) "导入成功，已跳过 $skipped 条所属订阅已不存在的配置" else "导入成功")
    }

    suspend fun exportBackup(): File = withContext(Dispatchers.IO) {
        backupManager.exportData()
    }
}
