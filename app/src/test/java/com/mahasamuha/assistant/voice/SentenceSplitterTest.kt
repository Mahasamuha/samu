package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceSplitterTest {
    @Test
    fun splitsCompleteSentencesAndHoldsBackTheRest() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("Hello there."), splitter.add("Hello there. How are"))
        assertEquals(listOf("How are you?"), splitter.add(" you? Fine"))
        assertEquals("Fine", splitter.flush())
    }

    @Test
    fun aTerminatorAtTheEndOfAChunkWaitsForTheNextCharacter() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.add("Done."))
        assertEquals(listOf("Done."), splitter.add(" Next"))
    }

    @Test
    fun decimalNumbersDoNotSplit() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("It is 3.5 degrees."), splitter.add("It is 3.5 degrees. Cold"))
    }

    @Test
    fun splitsWhenAnAnswerIsGluedToThePreambleByACapital() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("Let me check for you."), splitter.add("Let me check for you.Based on the forecast"))
    }

    @Test
    fun aLowercaseLetterAfterAPeriodDoesNotSplit() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.add("Visit example.invalid for more"))
    }

    @Test
    fun ellipsesAndMixedTerminatorsSplitOnlyAtTheEnd() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("Wait...", "what?!"), splitter.add("Wait... what?! ok"))
    }

    @Test
    fun deltasArrivingOneCharacterAtATimeGiveTheSameSentences() {
        val splitter = SentenceSplitter()
        val sentences = "One. Two! Three?".flatMap { splitter.add(it.toString()) } + listOfNotNull(splitter.flush())
        assertEquals(listOf("One.", "Two!", "Three?"), sentences)
    }

    @Test
    fun flushReturnsNullWhenNothingIsLeftAndClearsTheBuffer() {
        val splitter = SentenceSplitter()
        assertNull(splitter.flush())
        splitter.add("Tail")
        assertEquals("Tail", splitter.flush())
        assertNull(splitter.flush())
    }

    @Test
    fun whitespaceOnlyLeftoverIsNotASentence() {
        val splitter = SentenceSplitter()
        splitter.add("Hi.  ")
        assertNull(splitter.flush())
    }
}
