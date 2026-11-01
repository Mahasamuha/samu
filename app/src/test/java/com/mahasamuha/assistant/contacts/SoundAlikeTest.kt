package com.mahasamuha.assistant.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundAlikeTest {
    private val names = listOf("Siobhan Kelly", "Sean Kelly", "Sam Lee", "Priya Nair", "Zed Quimby")

    // --- Jaro-Winkler

    @Test
    fun identicalStringsScoreOneAndDisjointOnesZero() {
        assertEquals(1.0, jaroWinkler("anna", "anna"), 0.0)
        assertEquals(0.0, jaroWinkler("abc", "xyz"), 0.0)
        assertEquals(0.0, jaroWinkler("", "abc"), 0.0)
        assertEquals(0.0, jaroWinkler("abc", ""), 0.0)
    }

    @Test
    fun matchesTheTextbookValues() {
        assertEquals(0.961, jaroWinkler("martha", "marhta"), 0.001)
        assertEquals(0.840, jaroWinkler("dwayne", "duane"), 0.001)
        assertEquals(0.813, jaroWinkler("dixon", "dicksonx"), 0.001)
    }

    @Test
    fun isSymmetricForTheseInputsAndRewardsASharedPrefix() {
        assertEquals(jaroWinkler("shackleford", "shackelford"), jaroWinkler("shackelford", "shackleford"), 1e-9)
        assertTrue(jaroWinkler("johnson", "johnsen") > jaroWinkler("johnson", "xohnsen"))
    }

    // --- phonetic key

    @Test
    fun spellingsThatSoundAlikeShareAKey() {
        assertEquals(phoneticKey("catherine"), phoneticKey("katherine"))
        assertEquals(phoneticKey("philip"), phoneticKey("filip"))
        assertEquals(phoneticKey("stephen"), phoneticKey("steven"))
    }

    @Test
    fun keysDropVowelsExceptALeadingOneAndCollapseRepeats() {
        assertEquals("an", phoneticKey("anna"))
        assertEquals("sm", phoneticKey("summ"))
    }

    @Test
    fun softAndHardCAndGDiffer() {
        assertEquals("s", phoneticKey("cy").take(1))
        assertEquals("k", phoneticKey("ca").take(1))
        assertEquals("j", phoneticKey("ge").take(1))
        assertEquals("g", phoneticKey("ga").take(1))
    }

    @Test
    fun ignoresNonLettersAndHandlesEmptyInput() {
        assertEquals("", phoneticKey(""))
        assertEquals("", phoneticKey("-'"))
        assertEquals(phoneticKey("obrien"), phoneticKey("O'Brien"))
    }

    // --- scoring

    @Test
    fun anExactNameScoresOneAndAnUnrelatedOneLow() {
        assertEquals(1.0, soundAlikeScore("Sam Lee", "Sam Lee"), 0.0)
        assertTrue(soundAlikeScore("Sam Lee", "Priya Nair") < 0.6)
    }

    @Test
    fun aSingleHeardWordIsComparedWithTheClosestWordOfTheName() {
        assertEquals(1.0, soundAlikeScore("Kelly", "Sean Kelly"), 0.0)
    }

    @Test
    fun emptyInputScoresZero() {
        assertEquals(0.0, soundAlikeScore("", "Sam Lee"), 0.0)
        assertEquals(0.0, soundAlikeScore("Sam", "  "), 0.0)
    }

    @Test
    fun aMisspellingThatSoundsTheSameScoresAboveTheThreshold() {
        assertTrue(soundAlikeScore("Katherine Lee", "Catherine Lee") >= SOUND_ALIKE_MIN)
        assertTrue(soundAlikeScore("Philip Nair", "Filip Nair") >= SOUND_ALIKE_MIN)
    }

    // --- candidate selection

    @Test
    fun thresholdsAreTheDocumentedOnes() {
        assertEquals(0.80, SOUND_ALIKE_MIN, 0.0)
        assertEquals(0.06, SOUND_ALIKE_MARGIN, 0.0)
    }

    @Test
    fun noCandidatesWhenNothingReachesTheMinimum() {
        assertEquals(emptyList<String>(), soundAlikeNames("Xavier Orlov", names, limit = 3))
    }

    @Test
    fun noCandidatesFromAnEmptyList() {
        assertEquals(emptyList<String>(), soundAlikeNames("Sam", emptyList(), limit = 3))
    }

    @Test
    fun anExactNameComesFirstAndBelowMinimumNamesAreExcluded() {
        val result = soundAlikeNames("Sam Lee", names, limit = 3)
        assertEquals("Sam Lee", result.first())
        assertTrue("Priya Nair" !in result)
    }

    @Test
    fun onlyNamesWithinTheMarginOfTheBestAreKept() {
        val candidates = listOf("Katherine Lee", "Catherine Lee", "Kathleen Ng")
        val result = soundAlikeNames("Catherine Lee", candidates, limit = 3)
        // The two spellings tie at 1.0 (same phonetic key), so either may lead.
        assertEquals(setOf("Catherine Lee", "Katherine Lee"), result.take(2).toSet())
        val scores = result.map { soundAlikeScore("Catherine Lee", it) }
        assertTrue(scores.all { it >= SOUND_ALIKE_MIN && it >= scores.first() - SOUND_ALIKE_MARGIN })
    }

    @Test
    fun atMostLimitCandidatesAreReturnedBestFirst() {
        val many = listOf("Anna Lee", "Anna Lea", "Anna Leigh", "Anna Lee Jr", "Annah Lee")
        val result = soundAlikeNames("Anna Lee", many, limit = 3)
        assertEquals(3, result.size)
        assertEquals("Anna Lee", result.first())
        val scores = result.map { soundAlikeScore("Anna Lee", it) }
        assertEquals(scores.sortedDescending(), scores)
    }
}
