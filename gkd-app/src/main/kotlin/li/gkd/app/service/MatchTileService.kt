package li.gkd.app.service

import li.gkd.app.store.storeFlow
import li.gkd.app.store.switchStoreEnableMatch
import kotlinx.coroutines.flow.map

class MatchTileService : BaseTileService() {
    override val activeFlow = storeFlow.map { it.enableMatch }

    override fun onTileClick() = switchStoreEnableMatch()
}
