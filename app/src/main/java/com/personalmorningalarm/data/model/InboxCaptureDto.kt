package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.PendingInboxWrite
import com.personalmorningalarm.util.VaultText

/**
 * One `0-inbox/` file as Alfred serves it from GET /inbox. Both fields are nullable
 * for the same reason every other Alfred DTO's are: a cached response written by an
 * older build, or a field the API stops sending, must degrade rather than crash.
 */
data class InboxCaptureDto(
    val filename: String? = null,
    val content: String? = null
)

/** One capture, ready to render. */
data class InboxCapture(
    /** Derived from the filename's slug — the vault's own name for the capture. */
    val title: String,
    /** "17 Jul, 13:56" from the filename's timestamp prefix, or null if it hasn't got one. */
    val captured: String?,
    /** First few lines of the body, wikilinks resolved. Empty for a body-less capture. */
    val preview: String,
    /** Captured on the phone, still waiting in the offline queue. */
    val pending: Boolean = false
)

/**
 * Turns GET /inbox into display rows, and layers the offline queue on top. Read-only
 * by design: inbox items are resolved in a vault triage session, never from the phone,
 * so there's no tick/drop merge to do here — queued captures simply appear as extra
 * rows, newest first, exactly where they'll land once they sync.
 */
object Inbox {

    /** Alfred serves newest first; the queue is FIFO, so its newest is last. */
    fun merged(captures: List<InboxCaptureDto>, pending: List<PendingInboxWrite>): List<InboxCapture> =
        pending.filterNot { it.failed }.reversed().map {
            InboxCapture(
                title = firstLine(it.text),
                captured = null,
                preview = preview(it.text),
                pending = true
            )
        } + captures(captures)

    fun captures(captures: List<InboxCaptureDto>): List<InboxCapture> =
        captures
            .filterNot { it.filename.isNullOrBlank() }
            .map {
                val filename = it.filename!!.trim()
                InboxCapture(
                    title = titleOf(filename),
                    captured = capturedAt(filename),
                    preview = preview(it.content.orEmpty())
                )
            }

    /**
     * Vault capture filenames are `YYYY-MM-DD-HHMM-some-slug.md`. Anything that
     * doesn't match — a hand-named file dropped into `0-inbox/` — keeps its own name
     * rather than being mangled into one.
     */
    private val NAMED = Regex("""^(\d{4})-(\d{2})-(\d{2})-(\d{2})(\d{2})-(.+)$""")

    private fun titleOf(filename: String): String {
        val stem = filename.removeSuffix(".md")
        val slug = NAMED.matchEntire(stem)?.groupValues?.get(6) ?: return stem
        return slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun capturedAt(filename: String): String? {
        val parts = NAMED.matchEntire(filename.removeSuffix(".md"))?.groupValues ?: return null
        val month = MONTHS.getOrNull(parts[2].toInt() - 1) ?: return null
        return "${parts[3].trimStart('0')} $month, ${parts[4]}:${parts[5]}"
    }

    private fun preview(body: String): String =
        VaultText.resolveLinks(body)
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(PREVIEW_LINES)
            .joinToString("\n") { it.trim() }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private const val PREVIEW_LINES = 3

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
}
