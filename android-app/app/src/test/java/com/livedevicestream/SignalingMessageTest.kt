package com.livedevicestream

import com.google.gson.Gson
import com.livedevicestream.signaling.SignalingMessage
import com.livedevicestream.util.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingMessageTest {

    private val gson = Gson()

    @Test
    fun testSessionManagerGeneratesValidCredentials() {
        val session = SessionManager.createSession()
        assertNotNull(session.roomId)
        assertNotNull(session.token)

        assertTrue(session.roomId.startsWith("ROOM-"))
        assertEquals(9, session.roomId.length) // ROOM-XXXX

        // Pairing code format: XXXX-XXXX (9 chars)
        assertEquals(9, session.token.length)
        assertTrue(session.token.contains("-"))
    }

    @Test
    fun testRegisterSenderSerialization() {
        val msg = SignalingMessage.registerSender("ROOM-1234", "TEST-TOKEN")
        val json = gson.toJson(msg)

        assertTrue(json.contains("\"type\":\"register\""))
        assertTrue(json.contains("\"role\":\"sender\""))
        assertTrue(json.contains("\"roomId\":\"ROOM-1234\""))
        assertTrue(json.contains("\"token\":\"TEST-TOKEN\""))

        val parsed = gson.fromJson(json, SignalingMessage::class.java)
        assertEquals("register", parsed.type)
        assertEquals("sender", parsed.role)
        assertEquals("ROOM-1234", parsed.roomId)
        assertEquals("TEST-TOKEN", parsed.token)
    }

    @Test
    fun testOfferSerialization() {
        val sdpString = "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0"
        val msg = SignalingMessage.offer(sdpString)
        val json = gson.toJson(msg)

        val parsed = gson.fromJson(json, SignalingMessage::class.java)
        assertEquals("offer", parsed.type)
        assertEquals(sdpString, parsed.sdp)
    }

    @Test
    fun testIceCandidateSerialization() {
        val msg = SignalingMessage.iceCandidate("video", 0, "candidate:1 1 UDP 2122260223 192.168.1.50 50000 typ host")
        val json = gson.toJson(msg)

        val parsed = gson.fromJson(json, SignalingMessage::class.java)
        assertEquals("ice_candidate", parsed.type)
        assertNotNull(parsed.candidate)
        assertEquals("video", parsed.candidate?.sdpMid)
        assertEquals(0, parsed.candidate?.sdpMLineIndex)
        assertEquals("candidate:1 1 UDP 2122260223 192.168.1.50 50000 typ host", parsed.candidate?.candidate)
    }

    @Test
    fun testStreamStateSerialization() {
        val msg = SignalingMessage.streamState(camera = true, mic = false, screen = true)
        val json = gson.toJson(msg)

        val parsed = gson.fromJson(json, SignalingMessage::class.java)
        assertEquals("stream_state", parsed.type)
        assertEquals(true, parsed.camera)
        assertEquals(false, parsed.mic)
        assertEquals(true, parsed.screen)
    }

    @Test
    fun testPeerStatusParsing() {
        val rawJson = """{"type":"peer_status","peerJoined":true,"peerRole":"receiver"}"""
        val parsed = gson.fromJson(rawJson, SignalingMessage::class.java)

        assertEquals("peer_status", parsed.type)
        assertEquals(true, parsed.peerJoined)
        assertEquals("receiver", parsed.peerRole)
    }

    @Test
    fun testStreamInfoSerialization() {
        val msg = SignalingMessage.streamInfo(
            screenTrackId = "live_screen_track",
            cameraTrackId = "live_camera_track",
            audioTrackId = "live_audio_track"
        )
        val json = gson.toJson(msg)

        assertTrue(json.contains("\"type\":\"stream_info\""))
        assertTrue(json.contains("\"screenTrackId\":\"live_screen_track\""))
        assertTrue(json.contains("\"cameraTrackId\":\"live_camera_track\""))
        assertTrue(json.contains("\"audioTrackId\":\"live_audio_track\""))

        val parsed = gson.fromJson(json, SignalingMessage::class.java)
        assertEquals("stream_info", parsed.type)
        assertEquals("live_screen_track", parsed.streamInfo?.screenTrackId)
        assertEquals("live_camera_track", parsed.streamInfo?.cameraTrackId)
        assertEquals("live_audio_track", parsed.streamInfo?.audioTrackId)
    }
}
