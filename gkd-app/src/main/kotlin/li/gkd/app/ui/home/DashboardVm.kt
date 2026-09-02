package li.gkd.app.ui.home

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import li.gkd.app.service.StatusService
import li.gkd.app.store.actionCountFlow
import li.gkd.app.store.storeFlow
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.util.SubsState

class DashboardVm : BaseViewModel() {

    val subsStatusFlow = combine(SubsState.ruleSummaryFlow, actionCountFlow) { ruleSummary, count ->
        SubsState.getSubsStatus(ruleSummary, count)
    }.stateInit(SubsState.getSubsStatus(SubsState.ruleSummaryFlow.value, actionCountFlow.value))

    fun stopStatusService() {
        StatusService.stop()
        storeFlow.update { it.copy(enableStatusService = false) }
    }
}
