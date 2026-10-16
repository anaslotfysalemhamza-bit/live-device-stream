package com.livedevicestream.signaling

interface SignalingListener {
    fun onConnected()
    fun onRegistered(roomId: String)
    fun onPeerStatus(peerJoined: Boolean, peerRole: String)
    fun onOffer(sdp: String)
    fun onAnswer(sdp: String)
    fun onIceCandidate(candidate: IceCandidatePayload)
    fun onError(code: String, message: String)
    fun onDisconnected()
}
