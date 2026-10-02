package io.evren.morsel.demo

import io.evren.morsel.domain.FeedJournal
import io.evren.morsel.domain.FeedOperation

/**
 * In-memory journal for the demo route. The demo never reads or writes the
 * real on-disk journal; nothing simulated can leak into real operation state.
 */
class DemoJournal : FeedJournal {
    private val ops = mutableListOf<FeedOperation>()

    override suspend fun all(): List<FeedOperation> = ops.toList()

    override suspend fun upsert(operation: FeedOperation) {
        ops.removeAll { it.id == operation.id }
        ops.add(operation)
    }
}
