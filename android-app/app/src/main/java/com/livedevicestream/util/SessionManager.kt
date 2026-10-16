package com.livedevicestream.util

import java.security.SecureRandom

/**
 * Manages Room IDs and pairing codes / tokens.
 * Provides a fixed default session for instant 1-click connections,
 * with the ability to generate fresh random credentials when needed.
 */
object SessionManager {

    const val DEFAULT_ROOM_ID = "ROOM-LIVE"
    const val DEFAULT_TOKEN = "LIVE-1234"

    private val secureRandom = SecureRandom()
    private const val PAIRING_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    data class SessionCredentials(
        val roomId: String,
        val token: String
    )

    /**
     * Returns the persistent default session credentials
     */
    fun getDefaultSession(): SessionCredentials {
        return SessionCredentials(roomId = DEFAULT_ROOM_ID, token = DEFAULT_TOKEN)
    }

    /**
     * Creates a new unique session with an 8-character pairing code
     */
    fun createSession(): SessionCredentials {
        val randomRoomNum = 1000 + secureRandom.nextInt(9000)
        val roomId = "ROOM-$randomRoomNum"

        val sb = StringBuilder()
        for (i in 0 until 8) {
            val idx = secureRandom.nextInt(PAIRING_CHARS.length)
            sb.append(PAIRING_CHARS[idx])
            if (i == 3) {
                sb.append("-")
            }
        }
        val token = sb.toString()

        return SessionCredentials(roomId = roomId, token = token)
    }
}
