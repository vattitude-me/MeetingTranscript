package me.vattitude.scribe.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

/** Marks search matches in text, shared by the list's snippets and the transcript's find bar. */
object Highlight {

    /** Start offsets of every case-insensitive occurrence of [query] in [text]. */
    fun matches(text: String, query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            out += i
            from = i + q.length
        }
        return out
    }

    /**
     * Cuts a long line down to the part around the first match, so a match in
     * the middle of a paragraph is visible in a two-line snippet.
     */
    fun around(text: String, query: String, lead: Int = 36): String {
        val i = matches(text, query).firstOrNull() ?: return text
        if (i <= lead) return text
        val start = text.lastIndexOf(' ', i - lead).let { if (it < 0) i - lead else it + 1 }
        return "…" + text.substring(start)
    }

    /** [text] with every match of [query] coloured, and optionally backed. */
    fun spans(text: CharSequence, query: String, fg: Int, bg: Int? = null): CharSequence {
        val q = query.trim()
        if (q.isEmpty()) return text
        val s = SpannableString(text)
        for (i in matches(text.toString(), q)) {
            s.setSpan(ForegroundColorSpan(fg), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bg != null) {
                s.setSpan(BackgroundColorSpan(bg), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return s
    }
}
