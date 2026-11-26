package com.abdllh.aura.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for AMRI 2.0-beta.6's Apple Music lyrics engine ([LyricsEngine], [Words], [Lyrics.parseLrc]).
 */
class LyricsEngineTest {

    @Test fun lyricsPlusMergesSyllablesAndPreservesHeldNotes() {
        val json = """
            {
              "metadata": { "source": "Apple Music" },
              "lyrics": [
                {
                  "time": 10000,
                  "duration": 3800,
                  "text": "I'm gonna fly",
                  "syllabus": [
                    { "time": 10000, "duration": 400, "text": "I'm " },
                    { "time": 10400, "duration": 200, "text": "gon" },
                    { "time": 10600, "duration": 1800, "text": "na " },
                    { "time": 12400, "duration": 1400, "text": "fly" },
                    { "time": 13500, "duration": 300, "text": "(oh)", "isBackground": true }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = LyricsEngine.parseLyricsPlus(json)
        assertNotNull(parsed)
        assertTrue(parsed!!.wordSynced)
        assertEquals(Lyrics.SOURCE_APPLE, parsed.source)

        val lines = LyricsEngine.toLines(parsed)
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals("I'm gonna fly", line.text)
        assertNotNull(line.words)
        assertEquals(4, line.words!!.size) // 3 words + end sentinel
        assertEquals(10000L, line.words!![0])
        assertEquals(10400L, line.words!![1])
        assertEquals(12400L, line.words!![2])
        assertEquals(13800L, line.words!![3])

        // "gonna" keeps its 2 sub-word syllables ("gon" 200ms + held "na" 1800ms)
        val syls = line.syllables?.get(1)
        assertNotNull(syls)
        assertEquals(2, syls!!.size)
        assertEquals("gon", syls[0].text)
        assertEquals("na", syls[1].text)

        // Halfway through the held syllable "na" (11500ms): word 1 ("gonna") is still sweeping, word 2 ("fly") is at 0.0!
        val pGonna = LyricsEngine.wordProgress(11500L, line.words!![1], 12400L, syls)
        val pFly = LyricsEngine.wordProgress(11500L, line.words!![2], 13800L, line.syllables?.get(2))
        assertTrue("Expected gonna around 0.8 (got $pGonna)", pGonna in 0.72f..0.88f)
        assertEquals(0f, pFly, 0.0001f)
        assertTrue(LyricsEngine.isWordSustaining(11500L, line.words!![1], 12400L, syls))
    }

    @Test fun lyricsPlusKeepsArabicWordsIntactForCursiveShaping() {
        val json = """
            {
              "metadata": { "source": "Apple Music" },
              "lyrics": [
                {
                  "time": 5000,
                  "duration": 3000,
                  "text": "يا حبيبي",
                  "syllabus": [
                    { "time": 5000, "duration": 900, "text": "يا " },
                    { "time": 5900, "duration": 600, "text": "حبي" },
                    { "time": 6500, "duration": 1500, "text": "بي" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = LyricsEngine.parseLyricsPlus(json)!!
        val lines = LyricsEngine.toLines(parsed)
        assertEquals("يا حبيبي", lines[0].text)
        assertEquals(2, Words.count(lines[0].text))
        assertEquals(3, lines[0].words!!.size)
        assertNotNull(lines[0].syllables!![1])
        assertEquals(2, lines[0].syllables!![1]!!.size)
        assertTrue(LyricsEngine.dominantRtl(lines))
    }

    @Test fun betterLyricsTtmlParsesSyllablesAndSkipsBackgroundVocals() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div>
                <p begin="00:12.000" end="00:16.500">
                  <span begin="00:12.000" end="00:12.400">Chan</span><span begin="00:12.400" end="00:14.200">de</span><span begin="00:14.200" end="00:15.400">lier</span>
                  <span begin="00:15.500" end="00:16.500">tonight</span>
                  <span ttm:role="x-bg"><span begin="00:15.000" end="00:16.000">(ooh)</span></span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val parsed = LyricsEngine.parseTtml(ttml)
        assertNotNull(parsed)
        assertTrue(parsed!!.wordSynced)
        val lines = LyricsEngine.toLines(parsed)
        assertEquals(1, lines.size)
        assertEquals("Chandelier tonight", lines[0].text)
        assertEquals(12000L, lines[0].words!![0])
        assertEquals(15500L, lines[0].words!![1])
        assertEquals(16500L, lines[0].words!![2])
        assertEquals(3, lines[0].syllables!![0]!!.size)
        // Held middle syllable "de" (1800ms) is sustaining at 13300ms while "tonight" has not started
        assertTrue(LyricsEngine.isWordSustaining(13300L, 12000L, 15400L, lines[0].syllables!![0]))
        assertEquals(0f, LyricsEngine.wordProgress(13300L, 15500L, 16500L, null), 0.0001f)
    }

    @Test fun lineOnlyLrcHasNoFakeWordTimingsAndEnhancedLrcWorks() {
        val lrc = """
            [00:10.00]Hello world
            [00:14.50]Second line here
        """.trimIndent()
        val lines = LyricsEngine.parseLrc(lrc)
        assertEquals(2, lines.size)
        assertNull(lines[0].words)
        assertNull(lines[1].words)
        assertNull(Words.times(lines, 0))

        val enhanced = "[00:20.00]<00:20.00>Hold <00:21.50><00:22.00>on <00:23.00>"
        val eLines = LyricsEngine.parseLrc(enhanced)
        assertEquals(1, eLines.size)
        assertEquals("Hold on", eLines[0].text)
        assertEquals(20000L, eLines[0].words!![0])
        assertEquals(22000L, eLines[0].words!![1])
        assertEquals(23000L, eLines[0].words!![2])
        assertEquals(21500L, eLines[0].ends!![0])
    }

    @Test fun phoneLyricsSupportsLegacyAndRichWordFormats() {
        val legacy = """
            {"type":"lyrics","id":"abc","title":"Song","synced":true,"lines":[
              {"time":1.5,"text":"First line"},
              {"time":5.0,"text":"Second line"}
            ]}
        """.trimIndent()
        val rLegacy = LyricsEngine.parsePhoneLyricsJson(legacy, 180)
        assertNotNull(rLegacy)
        assertTrue(rLegacy!!.synced)
        assertEquals(0f, rLegacy.wordsCovered, 0.001f)
        assertEquals(1500L, rLegacy.lines[0].ms)

        val rich = """
            {"type":"lyrics","id":"abc","title":"Song","synced":true,"lines":[
              {"time":1.5,"end":4.0,"text":"Hold on","words":[
                {"t":1.5,"e":3.2,"w":"Hold","s":[[1.5,3.2,"Hold"]]},
                {"t":3.2,"e":4.0,"w":"on"}
              ]}
            ]}
        """.trimIndent()
        val rRich = LyricsEngine.parsePhoneLyricsJson(rich, 180)
        assertNotNull(rRich)
        assertEquals(1f, rRich!!.wordsCovered, 0.001f)
        assertEquals(1500L, rRich.lines[0].words!![0])
        assertEquals(3200L, rRich.lines[0].words!![1])
        assertEquals(4000L, rRich.lines[0].words!![2])
    }

    @Test fun displayRowsInsertIntroAndBreakInterludesWithoutMutatingSource() {
        val src = listOf(
            Lyrics.Line(8000L, "First verse", endMs = 11000L),
            Lyrics.Line(22000L, "After solo", endMs = 26000L)
        )
        val rows = LyricsEngine.buildDisplayRows(src, synced = true)
        assertEquals(4, rows.size)
        assertTrue(rows[0].isInterlude)
        assertEquals(-1, rows[0].sourceIndex)
        assertEquals(0L, rows[0].ms)
        assertEquals(8000L, rows[0].endMs)

        assertFalse(rows[1].isInterlude)
        assertEquals(0, rows[1].sourceIndex)

        assertTrue(rows[2].isInterlude)
        assertEquals(-1, rows[2].sourceIndex)
        assertEquals(22000L, rows[2].endMs)

        assertFalse(rows[3].isInterlude)
        assertEquals(1, rows[3].sourceIndex)
    }

    @Test fun metadataCleaningAndArabicNormalization() {
        assertEquals("Chandelier", LyricsEngine.cleanTitle("Chandelier (Official Music Video) [4K]"))
        assertEquals("الأماكن", LyricsEngine.cleanTitle("الأماكن (حصرياً) | فيديو كليب"))
        assertEquals("Mohammed Abdu", LyricsEngine.cleanArtist("Mohammed Abdu - Topic"))
        val (t, a) = LyricsEngine.splitArtistTitle("Sia - Chandelier (Official Video)", "SiaVEVO")
        assertEquals("Chandelier", t)
        assertEquals("Sia", a)
        assertEquals(LyricsEngine.normText("إبراهيمُ الأماكنَ ة ى"), LyricsEngine.normText("ابراهيم الاماكن ه ي"))
    }
}
