package com.livedevicestream.signaling

import com.google.gson.annotations.SerializedName

data class IceCandidatePayload(
    @SerializedName("sdpMid") val sdpMid: String?,
    @SerializedName("sdpMLineIndex") val sdpMLineIndex: Int,
    @SerializedName("candidate") val candidate: String
)

data class StreamInfoPayload(
    @SerializedName("screenTrackId") val screenTrackId: String?,
    @SerializedName("cameraTrackId") val cameraTrackId: String?,
    @SerializedName("audioTrackId") val audioTrackId: String?
)

data class SignalingMessage(
    @SerializedName("type") val type: String,
    @SerializedName("role") val role: String? = null,
    @SerializedName("roomId") val roomId: String? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("sdp") val sdp: String? = null,
    @SerializedName("candidate") val candidate: IceCandidatePayload? = null,
    @SerializedName("streamInfo") val streamInfo: StreamInfoPayload? = null,
    @SerializedName("camera") val camera: Boolean? = null,
    @SerializedName("mic") val mic: Boolean? = null,
    @SerializedName("screen") val screen: Boolean? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("peerJoined") val peerJoined: Boolean? = null,
    @SerializedName("peerRole") val peerRole: String? = null,
    @SerializedName("timestamp") val timestamp: Long? = null
) {
    companion object {
        fun registerSender(roomId: String, token: String): SignalingMessage =
            SignalingMessage(
                type = "register",
                role = "sender",
                roomId = roomId,
                token = token
            )

        fun offer(sdp: String): SignalingMessage =
            SignalingMessage(type = "offer", sdp = sdp)

        fun answer(sdp: String): SignalingMessage =
            SignalingMessage(type = "answer", sdp = sdp)

        fun iceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String): SignalingMessage =
            SignalingMessage(
                type = "ice_candidate",
                candidate = IceCandidatePayload(sdpMid, sdpMLineIndex, candidate)
            )

        fun streamState(camera: Boolean, mic: Boolean, screen: Boolean): SignalingMessage =
            SignalingMessage(
                type = "stream_state",
                camera = camera,
                mic = mic,
                screen = screen
            )

        fun streamInfo(screenTrackId: String?, cameraTrackId: String?, audioTrackId: String?): SignalingMessage =
            SignalingMessage(
                type = "stream_info",
                streamInfo = StreamInfoPayload(screenTrackId, cameraTrackId, audioTrackId)
            )

        fun ping(): SignalingMessage =
            SignalingMessage(type = "ping", timestamp = System.currentTimeMillis())
    }
}
