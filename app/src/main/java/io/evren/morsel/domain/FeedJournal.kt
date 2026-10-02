package io.evren.morsel.domain

/** Thrown when the journal cannot be made durable. Callers must NOT send. */
class JournalPersistenceException(cause: Throwable? = null) : Exception("journal write failed", cause)

/**
 * Durable operation journal. At most one unresolved operation drives the UI;
 * resolved entries are retained (bounded) and are never silently erased.
 */
interface FeedJournal {
    suspend fun all(): List<FeedOperation>

    /** Atomic durably-observed write. Throws [JournalPersistenceException] on failure. */
    suspend fun upsert(operation: FeedOperation)
}
