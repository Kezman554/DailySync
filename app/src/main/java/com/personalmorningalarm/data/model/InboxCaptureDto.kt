package com.personalmorningalarm.data.model

import com.personalmorningalarm.data.entity.PendingInboxWrite
import com.personalmorningalarm.util.VaultText
import java.time.LocalDateTime
import java.time.ZoneId

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

    /**
     * The hub file a capture sent at [sentAt] became, if it's in [captures]: one not
     * in [claimed] whose text is exactly [text] (as the hub stores it: line endings
     * made LF, trimmed) and whose filename stamp is no earlier than the send, allowing
     * [CLOCK_SKEW_MILLIS] between the phone's clock and the Pi's. Null if none.
     *
     * The filename is unique per capture (the hub adds -2, -3 within a minute), so a
     * file can only be claimed once; the stamp, in the hub's own time, rules out an
     * identical capture made before this one was sent.
     */
    fun landed(
        captures: List<InboxCaptureDto>,
        text: String,
        sentAt: Long,
        claimed: Set<String> = emptySet()
    ): String? {
        val wanted = hubText(text)
        val earliest = sentAt - CLOCK_SKEW_MILLIS
        return captures.firstOrNull { capture ->
            val filename = capture.filename?.trim().orEmpty()
            val stamp = stampMillis(filename)
            filename.isNotEmpty() && filename !in claimed &&
                stamp != null && stamp >= earliest - MINUTE_MILLIS &&
                hubText(capture.content.orEmpty()) == wanted
        }?.filename?.trim()
    }

    /** What the hub keeps of a capture's text: LF line endings, trimmed both ends. */
    private fun hubText(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** The filename's YYYY-MM-DD-HHMM, in the hub's zone, as epoch millis; null if it hasn't one. */
    private fun stampMillis(filename: String): Long? {
        val parts = NAMED.matchEntire(filename.removeSuffix(".md"))?.groupValues ?: return null
        return try {
            LocalDateTime.of(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), parts[5].toInt())
                .atZone(HUB_ZONE).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /** The Pi's clock: capture filenames are stamped in its local time. */
    private val HUB_ZONE: ZoneId = ZoneId.of("Europe/London")

    /** How far apart the phone's and the Pi's clocks may be. */
    const val CLOCK_SKEW_MILLIS = 10 * 60 * 1000L

    /** A stamp is truncated to its minute. */
    private const val MINUTE_MILLIS = 60 * 1000L

    private const val PREVIEW_LINES = 3

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
}
