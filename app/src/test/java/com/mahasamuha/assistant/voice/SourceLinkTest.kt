package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLinkTest {
    @Test
    fun readsTitleAndUrlFromSearchResults() {
        val result =
            """{"results":[{"title":"Page One","url":"https://example.invalid/one","snippet":"x"},""" +
                """{"title":"Two","url":"http://example.invalid/two"}]}"""
        assertEquals(
            listOf(SourceLink("Page One", "https://example.invalid/one"), SourceLink("Two", "http://example.invalid/two")),
            SourceLink.fromSearchResult(result),
        )
    }

    @Test
    fun aMissingTitleFallsBackToTheUrl() {
        val links =
            SourceLink.fromSearchResult(
                """{"results":[{"url":"https://example.invalid/a"},{"title":" ","url":"https://example.invalid/b"}]}""",
            )
        assertEquals(listOf("https://example.invalid/a", "https://example.invalid/b"), links.map { it.title })
    }

    @Test
    fun onlyWebUrlsAreKept() {
        val links =
            SourceLink.fromSearchResult(
                """{"results":[{"title":"a","url":"javascript:alert(1)"},{"title":"b","url":"intent://x"},{"title":"c"},{"title":"d","url":"https://example.invalid/d"}]}""",
            )
        assertEquals(listOf("d"), links.map { it.title })
    }

    @Test
    fun otherResultsGiveNoLinks() {
        assertTrue(SourceLink.fromSearchResult("""{"status":"sent"}""").isEmpty())
        assertTrue(SourceLink.fromSearchResult("not json").isEmpty())
        assertTrue(SourceLink.fromSearchResult("").isEmpty())
        assertTrue(SourceLink.fromSearchResult("""{"results":"nope"}""").isEmpty())
        assertTrue(SourceLink.fromSearchResult("""{"results":["str", 3]}""").isEmpty())
    }
}
