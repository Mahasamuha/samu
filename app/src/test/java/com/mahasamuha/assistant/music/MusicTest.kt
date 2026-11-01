package com.mahasamuha.assistant.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTest {
    private val office = CastSpeaker("Office 1", SpeakerDirectory.UPNP, "uuid-office_MR", aliases = listOf("Office 2"))
    private val kitchen = CastSpeaker("Kitchen", SpeakerDirectory.CHROMECAST, "cast-kitchen")
    private val livingRoom = CastSpeaker("Living Room", SpeakerDirectory.UPNP, "uuid-living_MR")
    private val speakers = listOf(office, kitchen, livingRoom)

    private fun match(
        name: String,
        list: List<CastSpeaker> = speakers,
    ) = SpeakerDirectory.match(name, list)

    // --- matching a spoken speaker name

    @Test
    fun anExactNameMatchesInAnyCaseAndIgnoresFillerWords() {
        assertEquals(kitchen, match("kitchen"))
        assertEquals(kitchen, match("the Kitchen speaker"))
        assertEquals(livingRoom, match("living room"))
        assertEquals(livingRoom, match("the living room speaker"))
    }

    @Test
    fun spokenNumbersMatchDigitsInNames() {
        assertEquals(office, match("office one"))
        assertEquals(office, match("Office 1"))
    }

    @Test
    fun aGroupMemberNameSelectsTheGroup() {
        assertEquals(office, match("office two"))
    }

    @Test
    fun aUniquePartialNameMatches() {
        assertEquals(livingRoom, match("living"))
    }

    @Test
    fun theLabelTheAssistantReadOutIsUnderstoodWhenTheUserRepeatsIt() {
        assertEquals(office, match("Office 1 (with Office 2)"))
    }

    @Test
    fun anAmbiguousNameListsTheCandidatesAsLabels() {
        val twin = CastSpeaker("Office 2 Annex", SpeakerDirectory.CHROMECAST, "cast-annex")
        val e = assertThrows(IllegalArgumentException::class.java) { match("office", speakers + twin) }
        assertEquals("Which speaker: Office 1 (with Office 2), or Office 2 Annex?", e.message)
    }

    @Test
    fun anUnknownNameListsTheSpeakers() {
        val e = assertThrows(IllegalArgumentException::class.java) { match("garage") }
        assertEquals("No speaker called garage. Speakers: Office 1 (with Office 2), Kitchen, Living Room", e.message)
    }

    @Test
    fun anEmptyOrFillerOnlyNameMatchesNothing() {
        assertThrows(IllegalArgumentException::class.java) { match("") }
        assertThrows(IllegalArgumentException::class.java) { match("the speaker") }
    }

    @Test
    fun noSpeakersAtAllIsAnError() {
        val e = assertThrows(IllegalArgumentException::class.java) { match("kitchen", emptyList()) }
        assertTrue(e.message!!.startsWith("No speaker called kitchen"))
    }

    @Test
    fun exactBeatsPartialWhenOneNameContainsAnother() {
        val island = CastSpeaker("Kitchen Island", SpeakerDirectory.CHROMECAST, "cast-island")
        assertEquals(kitchen, match("kitchen", listOf(island, kitchen)))
        assertEquals(island, match("kitchen island", listOf(island, kitchen)))
    }

    // --- renderer wording

    @Test
    fun theRendererNamesWhereMusicGoes() {
        val phone = Renderer(0, null, null)
        assertEquals("on the phone", phone.spoken)
        assertEquals("to the phone", phone.destination)
        val speaker = Renderer(1, "id", "office")
        assertEquals("in the office", speaker.spoken)
        assertEquals("to the office", speaker.destination)
    }

    // --- Sonos zone groups

    private fun soap(zoneGroupState: String): String {
        val escaped =
            zoneGroupState
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        return "<s:Envelope><s:Body><u:Resp><ZoneGroupState>$escaped</ZoneGroupState></u:Resp></s:Body></s:Envelope>"
    }

    @Test
    fun oneSpeakerPerGroupNamedByTheCoordinatorWithOtherMembersAsAliases() {
        val state = """<ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="RINCON_A01" ID="RINCON_A01:1">
              <ZoneGroupMember UUID="RINCON_A02" ZoneName="Office 2"/>
              <ZoneGroupMember UUID="RINCON_A01" ZoneName="Office 1"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_B01" ID="RINCON_B01:2">
              <ZoneGroupMember UUID="RINCON_B01" ZoneName="Kitchen"/>
            </ZoneGroup>
          </ZoneGroups></ZoneGroupState>"""
        val groups = SpeakerDirectory.parseZoneGroups(soap(state))

        assertEquals(
            listOf(
                CastSpeaker("Office 1", SpeakerDirectory.UPNP, "RINCON_A01_MR", listOf("Office 2")),
                CastSpeaker("Kitchen", SpeakerDirectory.UPNP, "RINCON_B01_MR"),
            ),
            groups,
        )
    }

    @Test
    fun invisibleAndUnnamedMembersAreSkipped() {
        val state = """<ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="RINCON_A01" ID="x">
              <ZoneGroupMember UUID="RINCON_A01" ZoneName="Living Room"/>
              <ZoneGroupMember UUID="RINCON_A03" ZoneName="Living Room" Invisible="1"/>
              <ZoneGroupMember UUID="RINCON_A04" ZoneName=""/>
            </ZoneGroup></ZoneGroups></ZoneGroupState>"""
        val group = SpeakerDirectory.parseZoneGroups(soap(state)).single()
        assertEquals("Living Room", group.name)
        assertTrue(group.aliases.isEmpty())
    }

    @Test
    fun aHiddenCoordinatorFallsBackToTheFirstVisibleMember() {
        val state = """<ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="RINCON_HIDDEN" ID="x">
              <ZoneGroupMember UUID="RINCON_HIDDEN" ZoneName="Sub" Invisible="1"/>
              <ZoneGroupMember UUID="RINCON_V1" ZoneName="Den"/>
            </ZoneGroup></ZoneGroups></ZoneGroupState>"""
        val group = SpeakerDirectory.parseZoneGroups(soap(state)).single()
        assertEquals("Den", group.name)
        assertEquals("RINCON_HIDDEN_MR", group.identifier)
    }

    @Test
    fun aGroupWithNoVisibleMembersIsLeftOut() {
        val state = """<ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="RINCON_A" ID="x"><ZoneGroupMember UUID="RINCON_A" ZoneName="Sub" Invisible="1"/></ZoneGroup>
            </ZoneGroups></ZoneGroupState>"""
        assertTrue(SpeakerDirectory.parseZoneGroups(soap(state)).isEmpty())
    }

    @Test
    fun namesWithEscapedCharactersAreUnescaped() {
        val state = """<ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="R1" ID="x"><ZoneGroupMember UUID="R1" ZoneName="Tom &amp; Jo's"/></ZoneGroup>
            </ZoneGroups></ZoneGroupState>"""
        assertEquals("Tom & Jo's", SpeakerDirectory.parseZoneGroups(soap(state)).single().name)
    }

    @Test
    fun aResponseWithoutZoneGroupsFails() {
        assertThrows(IllegalStateException::class.java) { SpeakerDirectory.parseZoneGroups("<s:Envelope/>") }
    }

    // --- raw HTTP

    private fun http(
        headers: String,
        body: ByteArray,
    ) = "HTTP/1.1 $headers\r\n\r\n".toByteArray(Charsets.ISO_8859_1) + body

    @Test
    fun readsAPlainBody() {
        assertEquals("hello", SpeakerDirectory.httpBody(http("200 OK\r\nContent-Length: 5", "hello".toByteArray())))
    }

    @Test
    fun dechunksAChunkedBody() {
        val chunked = "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n".toByteArray()
        assertEquals("hello world", SpeakerDirectory.httpBody(http("200 OK\r\nTransfer-Encoding: chunked", chunked)))
    }

    @Test
    fun aNon200StatusFails() {
        val e = assertThrows(IllegalStateException::class.java) { SpeakerDirectory.httpBody(http("500 Server Error", ByteArray(0))) }
        assertTrue(e.message!!.contains("500"))
    }

    @Test
    fun aResponseWithoutHeadersEndFails() {
        assertThrows(IllegalStateException::class.java) { SpeakerDirectory.httpBody("HTTP/1.1 200 OK".toByteArray()) }
    }
}
