package com.personalmorningalarm.data.remote

/** Outcome of a note save or delete. Mirrors [InboxWriteResult] — nothing here throws. */
sealed interface NoteWriteResult {

    /** The write landed. A note already holding that text, or already gone, counts. */
    data object Done : NoteWriteResult

    /**
     * Alfred read the write and refused it with a 422, which no retry will change.
     * Only a 422: a 404 or a 5xx may be a Pi mid-update, so those stay queued.
     */
    data object Rejected : NoteWriteResult

    /** Alfred didn't take the write — unreachable, or an unexpected error. */
    data object Unreachable : NoteWriteResult
}
