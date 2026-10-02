package io.evren.morsel.domain

/** Thrown when the journal cannot be made durable. Callers must NOT send. */
class JournalPersistenceException(cause: Throwable? = null) : Exception("journal write failed", cause)

/**
 * Thrown when the journal cannot be read. Unknown prior operation state must
 * block sending; it must never be silently replaced with an empty journal.
 */
class JournalReadException(cause: Throwable? = null) : Exception("journal read failed", cause)

/**
 * Durable operation journal. At most one unresolved operation drives the UI;
 * resolved entries are retained (bounded) and are never silently erased.
 */
interface FeedJournal {
    suspend fun all(): List<FeedOperation>

    /** Atomic durably-observed write. Throws [JournalPersistenceException] on failure. */
    suspend fun upsert(operation: FeedOperation)
}
