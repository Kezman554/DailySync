package com.personalmorningalarm.ui

import android.content.res.ColorStateList
import android.content.res.Resources
import android.os.SystemClock
import android.util.TypedValue
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MaterialR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R
import com.personalmorningalarm.data.remote.AlfredReachability
import com.personalmorningalarm.databinding.ViewFreshnessBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Paints a [Freshness.Age] into a view_freshness include. Every Alfred-backed
 * surface uses this one, so "how old is this" looks the same wherever it's read.
 *
 * [compact] is for the Home tiles, where a boxed notice won't fit half a phone's
 * width: the earlier-day case outlines the tile's [card] in the error colour
 * instead, and keeps its text to the headline and the reason.
 */
object FreshnessView {

    fun render(
        binding: ViewFreshnessBinding,
        age: Freshness.Age?,
        reachability: AlfredReachability.State,
        compact: Boolean = false,
        card: MaterialCardView? = null
    ) {
        val root = binding.root
        val context = root.context
        val level = age?.level ?: Freshness.Level.NONE
        card?.let { outline(it, loud = level == Freshness.Level.LOUD) }
        root.isVisible = level != Freshness.Level.NONE
        if (age == null || level == Freshness.Level.NONE) return

        val loudBox = level == Freshness.Level.LOUD && !compact
        root.background = if (loudBox) context.getDrawable(R.drawable.bg_notice_stale) else null
        root.setPadding(if (loudBox) context.resources.getDimensionPixelSize(R.dimen.freshness_notice_padding) else 0)

        val colourAttr = when (level) {
            Freshness.Level.LOUD -> MaterialR.attr.colorError
            Freshness.Level.PROMINENT -> MaterialR.attr.colorOnSurface
            else -> MaterialR.attr.colorOnSurfaceVariant
        }
        val colour = MaterialColors.getColor(root, colourAttr)
        binding.ivFreshnessIcon.setImageResource(
            if (level == Freshness.Level.LOUD) R.drawable.ic_age_warning else R.drawable.ic_age
        )
        binding.ivFreshnessIcon.imageTintList = ColorStateList.valueOf(colour)

        val headline = binding.tvFreshnessHeadline
        headline.setTextColor(colour)
        headline.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                loudBox -> 16f
                level >= Freshness.Level.PROMINENT && !compact -> 14f
                else -> 13f
            }
        )
        headline.text = headlineText(context.resources, age, reachability)

        val detail = detailText(context.resources, age, reachability, compact)
        binding.tvFreshnessDetail.isVisible = detail != null
        binding.tvFreshnessDetail.text = detail
        binding.tvFreshnessDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
    }

    private fun headlineText(res: Resources, age: Freshness.Age, reachability: AlfredReachability.State): String {
        val span = spanText(res, Freshness.span(age))
        val line = when (age.level) {
            Freshness.Level.LOUD ->
                if (age.daysAgo == 1L) res.getString(R.string.freshness_loud_yesterday, time(age.fetchedAtMillis))
                else res.getString(R.string.freshness_loud_days, date(age.fetchedAtMillis), span)
            Freshness.Level.PROMINENT -> res.getString(
                if (age.saved) R.string.freshness_saved_prominent else R.string.freshness_live_prominent,
                time(age.fetchedAtMillis),
                span
            )
            else -> res.getString(
                if (age.saved) R.string.freshness_saved_quiet else R.string.freshness_live_quiet,
                span
            )
        }
        // The loud notice gives the reason a line of its own; the lighter levels inline it.
        if (age.level == Freshness.Level.LOUD || !age.saved) return line
        return res.getString(R.string.freshness_with_reason, line, reasonText(res, reachability))
    }

    private fun detailText(
        res: Resources,
        age: Freshness.Age,
        reachability: AlfredReachability.State,
        compact: Boolean
    ): String? {
        if (age.level != Freshness.Level.LOUD) return null
        return when {
            // A tile has no room for the full sentence: the reason alone.
            compact && age.saved -> reasonText(res, reachability)
            age.saved && reachability == AlfredReachability.State.REACHABLE ->
                res.getString(R.string.freshness_reason_back)
            age.saved -> res.getString(R.string.freshness_loud_saved_detail)
            else -> res.getString(R.string.freshness_loud_live_detail)
        }
    }

    /**
     * Why it's a saved copy. Alfred having answered some other call since means a
     * retry is on its way (FreshnessWatch), so the line says that instead.
     */
    private fun reasonText(res: Resources, reachability: AlfredReachability.State): String = res.getString(
        if (reachability == AlfredReachability.State.REACHABLE) R.string.freshness_reason_back
        else R.string.freshness_reason_unreachable
    )

    private fun spanText(res: Resources, span: Freshness.Span): String = when (span) {
        Freshness.Span.UnderAMinute -> res.getString(R.string.freshness_under_minute)
        is Freshness.Span.Minutes -> res.getQuantityString(R.plurals.freshness_minutes, span.count, span.count)
        is Freshness.Span.Hours -> res.getQuantityString(R.plurals.freshness_hours, span.count, span.count)
        is Freshness.Span.Days -> res.getQuantityString(R.plurals.freshness_days, span.count, span.count)
    }

    private fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun date(millis: Long): String =
        DATE.withLocale(Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun outline(card: MaterialCardView, loud: Boolean) {
        if (loud) {
            card.strokeWidth = card.resources.getDimensionPixelSize(R.dimen.freshness_card_stroke)
            card.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(card, MaterialR.attr.colorError)))
        } else {
            card.strokeWidth = 0
        }
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
}

/**
 * Keeps an open Alfred surface honest for as long as it's on screen: re-renders
 * the age every [TICK_MS] so "12 min old" doesn't freeze, and — while the
 * surface wants it ([Freshness.wantsRetry]: a saved copy, or live data an hour
 * old) — asks Alfred again, so the warnings clear themselves once he's back.
 *
 * Foreground-only and stale-only: nothing runs when the screen isn't started,
 * and a surface showing live data never polls. Retries are at most every
 * [RETRY_MS]; when [AlfredReachability] flips to reachable (a queue flush on
 * reconnect, another screen's fetch) the retry comes at once, unless one went
 * out in the last [FLIP_RETRY_GAP_MS] — that gap is what stops a half-working
 * Alfred (one endpoint failing, another answering) flipping the switch back and
 * forth into a retry loop.
 */
object FreshnessWatch {

    const val TICK_MS = 30_000L
    const val RETRY_MS = 60_000L
    const val FLIP_RETRY_GAP_MS = 10_000L

    fun start(
        owner: LifecycleOwner,
        render: () -> Unit,
        wantsRetry: () -> Boolean,
        retry: () -> Unit
    ) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Every surface already fetches as it starts — count that as a retry.
                var lastRetry = SystemClock.elapsedRealtime()
                fun retryIfDue(minGap: Long) {
                    val now = SystemClock.elapsedRealtime()
                    if (!wantsRetry() || now - lastRetry < minGap) return
                    lastRetry = now
                    retry()
                }
                launch {
                    AlfredReachability.state.collect { state ->
                        render()
                        if (state == AlfredReachability.State.REACHABLE) retryIfDue(FLIP_RETRY_GAP_MS)
                    }
                }
                while (isActive) {
                    delay(TICK_MS)
                    render()
                    retryIfDue(RETRY_MS)
                }
            }
        }
    }
}
