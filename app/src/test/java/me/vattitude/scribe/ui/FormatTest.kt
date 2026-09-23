package me.vattitude.scribe.ui

import me.vattitude.scribe.asr.ModelDownloadWorker
import me.vattitude.scribe.store.Line
import me.vattitude.scribe.store.Mark
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test fun clockShortAndLong() {
        assertEquals("0:00", Format.clock(0))
        assertEquals("12:34", Format.clock(754_000))
        assertEquals("1:02:03", Format.clock(3_723_000))
        assertEquals("0:00", Format.clock(-5))
    }

    @Test fun mbNeverReadsAsNothing() {
        assertEquals("44 MB", Format.mb(44_300_000))
        assertEquals("0.4 MB", Format.mb(400_000))
    }

    @Test fun deleteSummary() {
        assertEquals(
            "47 min of audio · 44 MB · 418 transcript lines",
            Format.deleteSummary(47 * 60_000L + 12_000, 44_000_000, 418)
        )
        assertEquals("30 s of audio · 0.9 MB · no transcript yet", Format.deleteSummary(30_000, 900_000, 0))
        assertEquals(
            "5 min meeting, audio already removed · 1 transcript line",
            Format.deleteSummary(300_000, 0, 1)
        )
    }
}

class HighlightTest {

    @Test fun matchesIsCaseInsensitiveAndNonOverlapping() {
        assertEquals(listOf(0, 10), Highlight.matches("Budget is budget", "budget").let { listOf(it[0], it[1]) })
        assertEquals(listOf(0, 2), Highlight.matches("aaaa", "aa"))
        assertEquals(emptyList<Int>(), Highlight.matches("anything", "  "))
    }

    @Test fun aroundCutsToTheMatch() {
        val text = "one two three four five six seven eight nine ten eleven twelve budget"
        val cut = Highlight.around(text, "budget")
        assert(cut.startsWith("…")) { cut }
        assert(cut.endsWith("budget")) { cut }
        assertEquals("short budget", Highlight.around("short budget", "budget"))
    }
}

class TranscriptItemTest {

    private fun line(id: Long, t: Long) = Line(id, 1, id.toInt(), t, t + 1000, "l$id", 1.0)

    @Test fun marksLandBeforeTheLineTheyPrecede() {
        val items = TranscriptItem.merge(
            listOf(line(1, 0), line(2, 5_000), line(3, 10_000)),
            listOf(Mark(9, 1, 20_000), Mark(8, 1, 5_000))
        )
        assertEquals(
            listOf("L1", "M8", "L2", "L3", "M9"),
            items.map {
                when (it) {
                    is TranscriptItem.Spoken -> "L${it.line.id}"
                    is TranscriptItem.Marked -> "M${it.mark.id}"
                }
            }
        )
    }
}

class ProgressLineTest {

    @Test fun progressLine() {
        assertEquals("0 of 640 MB", ModelDownloadWorker.progressLine(0, 640_000_000, 0))
        assertEquals(
            "320 of 640 MB · 2.0 MB/s · about 3 min left",
            ModelDownloadWorker.progressLine(320_000_000, 640_000_000, 2_000_000)
        )
        assertEquals(
            "630 of 640 MB · 1.0 MB/s · under a minute left",
            ModelDownloadWorker.progressLine(630_000_000, 640_000_000, 1_000_000)
        )
    }
}
