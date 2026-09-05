package li.gkd.app.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import li.gkd.app.appScope
import li.gkd.app.app.AppContainer
import li.gkd.app.data.settings.SettingsRepository
import li.gkd.app.data.settings.SettingsStore
import li.gkd.app.service.ExposeService
import li.gkd.app.util.launchLogged
import li.gkd.app.util.ToastUtils.toast

val settingsRepository: SettingsRepository
    get() = AppContainer.settingsRepository

val storeFlow: StateFlow<SettingsStore>
    get() = settingsRepository.settings

val actionCountFlow: StateFlow<Long>
    get() = settingsRepository.actionCount

val blockMatchAppListFlow: StateFlow<Set<String>>
    get() = settingsRepository.blockMatchAppList

val blockA11yAppListFlow: StateFlow<Set<String>>
    get() = settingsRepository.blockA11yAppList

val actualBlockA11yAppList: Set<String>
    get() = if (storeFlow.value.blockA11yAppListFollowMatch) {
        blockMatchAppListFlow.value
    } else {
        blockA11yAppListFlow.value
    }

val a11yScopeAppListFlow: StateFlow<Set<String>>
    get() = settingsRepository.a11yScopeAppList

val actualA11yScopeAppList: Set<String>
    get() = if (storeFlow.value.useAutomation) {
        a11yScopeAppListFlow.value
    } else {
        emptySet()
    }

fun checkAppBlockMatch(appId: String): Boolean {
    if (blockMatchAppListFlow.value.contains(appId)) {
        return true
    }
    if (storeFlow.value.enableBlockA11yAppList) {
        return actualBlockA11yAppList.contains(appId)
    }
    return false
}

fun initStore() = appScope.launchLogged(Dispatchers.IO) {
    // preload
    settingsRepository.settings.value
    ExposeService.initCommandFile()
}

fun switchStoreEnableMatch() {
    if (storeFlow.value.enableMatch) {
        toast("暂停规则匹配")
    } else {
        toast("开启规则匹配")
    }
    settingsRepository.updateSettings { it.copy(enableMatch = !it.enableMatch) }
}

fun updateEnableAutomator(value: Boolean) {
    if (value == storeFlow.value.enableAutomator) return
    settingsRepository.updateSettings { it.copy(enableAutomator = value) }
}
