package li.gkd.app.feature.subscription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import li.gkd.app.a11y.launcherAppId
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.ruleconfig.RuleGroupConfigService
import li.gkd.app.data.edit
import li.gkd.app.domain.rule.toRuleGroupTarget
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.core.state.Loadable
import li.gkd.app.appInfoRepository
import li.gkd.db.Db
import li.gkd.db.SubsGlobalGroupConfig

data class SubsGlobalGroupListUiState(
    val subscription: RawSubscription,
    val subsConfigs: Loadable<List<SubsGlobalGroupConfig>>,
)

class SubsGlobalGroupListVm(
    val route: SubsGlobalGroupListRoute,
) : BaseViewModel() {
    private val subscription = requiredSubscription(route.subsItemId)

    private val subsConfigsFlow =
        Db.subsGlobalGroupConfigDao.queryBySubsId(route.subsItemId)

    val uiState = subscription.buildUiState(
        initialValue = { rawSubscription ->
            buildUiState(rawSubscription, Loadable.Loading)
        },
    ) { rawSubscription ->
        subsConfigsFlow.map { configs ->
            buildUiState(rawSubscription, Loadable.Ready(configs))
        }
    }

    private fun buildUiState(
        rawSubscription: RawSubscription,
        subsConfigs: Loadable<List<SubsGlobalGroupConfig>>,
    ) = SubsGlobalGroupListUiState(
        subscription = rawSubscription,
        subsConfigs = subsConfigs,
    )

    val focusGroupFlow: StateFlow<Triple<Long, String?, Int>?>?
        field = route.focusGroupKey?.let {
            MutableStateFlow<Triple<Long, String?, Int>?>(
                Triple(
                    route.subsItemId,
                    null,
                    route.focusGroupKey,
                )
            )
        }

    fun consumeFocusGroup() {
        focusGroupFlow?.value = null
    }

    suspend fun updateSelectedEnabled(selectedKeys: Set<Int>, enabled: Boolean?): Int {
        val rawSubscription = subscription.requireValue()
        val selectedGroups = rawSubscription.globalGroups
            .filter { it.key in selectedKeys }
            .map { it.toRuleGroupTarget(route.subsItemId) }
            .toSet()
        return RuleGroupConfigService.batchUpdateGroupEnabled(
            selectedGroups,
            enabled,
            launcherAppId,
            appInfoRepository.systemAppsFlow.value,
        ).size
    }

    suspend fun setGroupEnabled(
        group: RawSubscription.RawGlobalGroup,
        subsConfig: SubsGlobalGroupConfig?,
        enabled: Boolean,
    ) {
        val rawSubscription = subscription.requireValue()
        RuleGroupConfigService.updateGroupEnabled(
            rawSubscription,
            null,
            group,
            subsConfig,
            enabled,
        )
    }

    suspend fun deleteSelectedGroups(selectedKeys: Set<Int>) {
        subscription.update { current ->
            current.edit { removeGlobalGroups { it.key in selectedKeys } }
        }
        Db.subsGlobalGroupConfigDao.deleteGroups(route.subsItemId, selectedKeys.toList())
    }
}
