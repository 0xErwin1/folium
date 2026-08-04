package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OutlineNormalizationTest {

    @Test fun flat_numbered_chapters_become_roots_with_their_following_siblings_as_children() {
        val llmChildren = listOf(OutlineEntry("Parameters", 8), OutlineEntry("Tokens", 10))
        val outline = listOf(
            OutlineEntry("1", 0),
            OutlineEntry("Atomic Units of LLM Systems", 1),
            OutlineEntry("The path to language models", 3),
            OutlineEntry("LLMs analyzed", 7, llmChildren),
            OutlineEntry("2", 20),
            OutlineEntry("Building an LLM System", 21)
        )

        val normalized = normalizeFlatNumberedChapters(outline)

        assertEquals(
            listOf(
                OutlineEntry(
                    "1",
                    0,
                    listOf(
                        OutlineEntry("Atomic Units of LLM Systems", 1),
                        OutlineEntry("The path to language models", 3),
                        OutlineEntry("LLMs analyzed", 7, llmChildren)
                    )
                ),
                OutlineEntry("2", 20, listOf(OutlineEntry("Building an LLM System", 21)))
            ),
            normalized
        )
        assertEquals(
            listOf(
                OutlineRow("1", 0, 0),
                OutlineRow("Atomic Units of LLM Systems", 1, 1),
                OutlineRow("The path to language models", 3, 1),
                OutlineRow("LLMs analyzed", 7, 1),
                OutlineRow("Parameters", 8, 2),
                OutlineRow("Tokens", 10, 2),
                OutlineRow("2", 20, 0),
                OutlineRow("Building an LLM System", 21, 1)
            ),
            flattenOutline(normalized)
        )
    }

    @Test fun entries_before_the_first_marker_remain_roots_with_their_metadata_and_children() {
        val preface = OutlineEntry("Preface", null, listOf(OutlineEntry("About", 0)))
        val outline = listOf(
            preface,
            OutlineEntry("1", 4),
            OutlineEntry("First", 5),
            OutlineEntry("2", 12),
            OutlineEntry("Second", 13)
        )

        val normalized = normalizeFlatNumberedChapters(outline)

        assertSame(preface, normalized.first())
        assertEquals(listOf(preface, OutlineEntry("1", 4, listOf(outline[2])), OutlineEntry("2", 12, listOf(outline[4]))), normalized)
    }

    @Test fun surrounding_marker_whitespace_is_accepted_without_changing_the_original_titles() {
        val outline = listOf(
            OutlineEntry(" 1 ", 0),
            OutlineEntry("First", 1),
            OutlineEntry("\t2\n", 4),
            OutlineEntry("Second", 5)
        )

        val normalized = normalizeFlatNumberedChapters(outline)

        assertEquals(" 1 ", normalized[0].title)
        assertEquals("\t2\n", normalized[1].title)
        assertEquals(listOf(outline[1]), normalized[0].children)
        assertEquals(listOf(outline[3]), normalized[1].children)
    }

    @Test fun marker_sequences_without_a_section_for_every_chapter_are_unchanged() {
        val cases = listOf(
            listOf(OutlineEntry("1", 0), OutlineEntry("2", 1)),
            listOf(OutlineEntry("1", 0), OutlineEntry("2", 1), OutlineEntry("Section", 2)),
            listOf(OutlineEntry("1", 0), OutlineEntry("Section", 1), OutlineEntry("2", 2))
        )

        cases.forEach { outline ->
            assertSame(outline, normalizeFlatNumberedChapters(outline))
        }
    }

    @Test fun outlines_without_a_safe_complete_marker_sequence_are_unchanged() {
        val validNested = listOf(
            OutlineEntry("Part", 0, listOf(OutlineEntry("1", 1), OutlineEntry("2", 2)))
        )
        val cases = listOf(
            emptyList(),
            listOf(OutlineEntry("Chapter", 0)),
            listOf(OutlineEntry("1", 0), OutlineEntry("Only chapter", 1)),
            listOf(OutlineEntry("1", 0), OutlineEntry("First", 1), OutlineEntry("3", 3)),
            listOf(OutlineEntry("2", 0), OutlineEntry("First", 1), OutlineEntry("3", 3)),
            listOf(OutlineEntry("1", 0), OutlineEntry("First", 1), OutlineEntry("1", 3)),
            listOf(OutlineEntry("01", 0), OutlineEntry("First", 1), OutlineEntry("2", 3), OutlineEntry("Second", 4)),
            listOf(OutlineEntry("+1", 0), OutlineEntry("First", 1), OutlineEntry("2", 3), OutlineEntry("Second", 4)),
            listOf(OutlineEntry("1.0", 0), OutlineEntry("First", 1), OutlineEntry("2", 3), OutlineEntry("Second", 4)),
            listOf(OutlineEntry("1  ", 0), OutlineEntry("First", 1), OutlineEntry(" 02 ", 3), OutlineEntry("Second", 4)),
            listOf(OutlineEntry("１", 0), OutlineEntry("First", 1), OutlineEntry("2", 3), OutlineEntry("Second", 4)),
            listOf(OutlineEntry("1.", 0), OutlineEntry("First", 1), OutlineEntry("2", 3)),
            validNested,
            listOf(
                OutlineEntry("1", 0, listOf(OutlineEntry("Already nested", 1))),
                OutlineEntry("2", 2)
            )
        )

        cases.forEach { outline ->
            assertSame(outline, normalizeFlatNumberedChapters(outline))
        }
    }

    @Test fun normalization_handles_a_large_flat_outline_without_recursion() {
        val chapterCount = 10_000
        val outline = buildList {
            repeat(chapterCount) { chapter ->
                add(OutlineEntry((chapter + 1).toString(), chapter * 2))
                add(OutlineEntry("Section ${chapter + 1}", chapter * 2 + 1))
            }
        }

        val normalized = normalizeFlatNumberedChapters(outline)

        assertEquals(chapterCount, normalized.size)
        assertEquals(OutlineEntry("Section 10000", 19_999), normalized.last().children.single())
    }
}
