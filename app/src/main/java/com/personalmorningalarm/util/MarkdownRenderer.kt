package com.personalmorningalarm.util

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import com.google.android.material.color.MaterialColors
import com.personalmorningalarm.R

/**
 * Turns the runs [VaultText.parse] finds into styled text, so the markers themselves
 * never reach the screen: **bold** and *italic* as weight and slant, `code` set apart
 * by colorSurface2 behind it.
 *
 * Code stays in Space Grotesk, the app's only face. A background span rather than a
 * drawn chip because a long code span has to wrap with the line it sits in.
 */
object MarkdownRenderer {

    private const val TAG = "MarkdownRenderer"

    fun render(context: Context, source: String): CharSequence {
        val segments = VaultText.parse(source)
        if (segments.none { it.bold || it.italic || it.code }) {
            return segments.joinToString("") { it.text }
        }

        val codeBackground by lazy { MaterialColors.getColor(context, R.attr.colorSurface2, TAG) }
        val out = SpannableStringBuilder()
        for (segment in segments) {
            val start = out.length
            out.append(segment.text)
            fun span(what: Any) = out.setSpan(what, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (segment.bold) span(StyleSpan(Typeface.BOLD))
            if (segment.italic) span(StyleSpan(Typeface.ITALIC))
            if (segment.code) span(BackgroundColorSpan(codeBackground))
        }
        return out
    }
}
