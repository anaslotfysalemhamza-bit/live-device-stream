package com.livedevicestream.webrtc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.livedevicestream.signaling.StreamInfoPayload
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class WebRtcClient(
    private val context: Context,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit
) {
    private val TAG = "WebRtcClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private var peerConnection: PeerConnection? = null
    val captureManager: CaptureManager by lazy {
        CaptureManager(context, eglBase, peerConnectionFactory)
    }

    private var screenSender: RtpSender? = null
    private var cameraSender: RtpSender? = null
    private var audioSender: RtpSender? = null

    fun initializePeerConnection() {
        close()

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { cand ->
                        mainHandler.post {
                            try {
                                onLocalIceCandidate(cand)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in onLocalIceCandidate", e)
                            }
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    newState?.let { state ->
                        Log.d(TAG, "PeerConnection state changed: $state")
                        mainHandler.post {
                            try {
                                onConnectionChange(state)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in onConnectionChange", e)
                            }
                        }
                    }
                }
            }
        )
    }

    /**
     * Attach all currently active media tracks (Screen, Camera, Audio) to the PeerConnection
     */
    fun attachActiveTracks() {
        val pc = peerConnection ?: return
        val streamList = listOf("live_stream_media")

        // Screen Track
        val screenTrack = captureManager.screenVideoTrack
        if (screenTrack != null) {
            try {
                if (screenSender == null) {
                    screenSender = pc.addTrack(screenTrack, streamList)
                } else {
                    screenSender?.setTrack(screenTrack, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recreating screenSender due to: ${e.message}")
                try { screenSender?.let { pc.removeTrack(it) } } catch (_: Exception) {}
                try { screenSender = pc.addTrack(screenTrack, streamList) } catch (_: Exception) {}
            }
        } else if (screenSender != null) {
            try { screenSender?.setTrack(null, true) } catch (_: Exception) {}
            try { pc.removeTrack(screenSender) } catch (_: Exception) {}
            screenSender = null
        }

        // Camera Track
        val cameraTrack = captureManager.cameraVideoTrack
        if (cameraTrack != null) {
            try {
                if (cameraSender == null) {
                    cameraSender = pc.addTrack(cameraTrack, streamList)
                } else {
                    cameraSender?.setTrack(cameraTrack, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recreating cameraSender due to: ${e.message}")
                try { cameraSender?.let { pc.removeTrack(it) } } catch (_: Exception) {}
                try { cameraSender = pc.addTrack(cameraTrack, streamList) } catch (_: Exception) {}
            }
        } else if (cameraSender != null) {
            try { cameraSender?.setTrack(null, true) } catch (_: Exception) {}
            try { pc.removeTrack(cameraSender) } catch (_: Exception) {}
            cameraSender = null
        }

        // Audio Track
        val audioTrack = captureManager.audioTrack
        if (audioTrack != null) {
            try {
                if (audioSender == null) {
                    audioSender = pc.addTrack(audioTrack, streamList)
                } else {
                    audioSender?.setTrack(audioTrack, true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recreating audioSender due to: ${e.message}")
                try { audioSender?.let { pc.removeTrack(it) } } catch (_: Exception) {}
                try { audioSender = pc.addTrack(audioTrack, streamList) } catch (_: Exception) {}
            }
        } else if (audioSender != null) {
            try { audioSender?.setTrack(null, true) } catch (_: Exception) {}
            try { pc.removeTrack(audioSender) } catch (_: Exception) {}
            audioSender = null
        }
    }

    fun startScreenShare(permissionData: Intent): Boolean {
        val success = captureManager.startScreenCapture(permissionData)
        if (success) {
            captureManager.screenVideoTrack?.let { track ->
                val pc = peerConnection ?: return false
                try {
                    if (screenSender == null) {
                        screenSender = pc.addTrack(track, listOf("live_stream_media"))
                    } else {
                        screenSender?.setTrack(track, true)
                    }
                } catch (e: Exception) {
                    try { screenSender?.let { pc.removeTrack(it) } } catch (_: Exception) {}
                    screenSender = pc.addTrack(track, listOf("live_stream_media"))
                }
            }
        }
        return success
    }

    fun stopScreenShare() {
        val pc = peerConnection
        screenSender?.let { sender ->
            try { sender.setTrack(null, true) } catch (_: Exception) {}
            try { pc?.removeTrack(sender) } catch (_: Exception) {}
            screenSender = null
        }
        captureManager.stopScreenCapture()
    }

    fun startCamera(preferFront: Boolean = true): Boolean {
        if (captureManager.cameraVideoTrack != null && cameraSender != null) {
            return true
        }
        val success = captureManager.startCameraCapture(preferFront)
        if (success) {
            captureManager.cameraVideoTrack?.let { track ->
                val pc = peerConnection ?: return false
                try {
                    if (cameraSender == null) {
                        cameraSender = pc.addTrack(track, listOf("live_stream_media"))
                    } else {
                        cameraSender?.setTrack(track, true)
                    }
                } catch (e: Exception) {
                    try { cameraSender?.let { pc.removeTrack(it) } } catch (_: Exception) {}
                    cameraSender = pc.addTrack(track, listOf("live_stream_media"))
                }
            }
        }
        return success
    }

    fun stopCamera() {
        val pc = peerConnection
        cameraSender?.let { sender ->
            try { sender.setTrack(null, true) } catch (_: Exception) {}
            try { pc?.removeTrack(sender) } catch (_: Exception) {}
            cameraSender = null
        }
        captureManager.stopCameraCapture()
    }

    fun startAudio(): Boolean {
        if (captureManager.audioTrack != null && audioSender != null) {
            return true
        }
        val success = captureManager.startAudioCapture()
        if (success) {
            captureManager.audioTrack?.let { track ->
                val pc = peerConnection ?: return false
                try {
                    if (audioSender == null) {
                        audioSender = pc.addTrack(track, listOf("live_stream_media"))
                    } else {
                        audioSender?.setTrack(track, true)
                    }
                } catch (e: Exception) {
                    try { audioSender?.let { pc.removeTrack(it) } } catch (_: Exception) {}
                    audioSender = pc.addTrack(track, listOf("live_stream_media"))
                }
            }
        }
        return success
    }

    fun stopAudio() {
        val pc = peerConnection
        audioSender?.let { sender ->
            try { sender.setTrack(null, true) } catch (_: Exception) {}
            try { pc?.removeTrack(sender) } catch (_: Exception) {}
            audioSender = null
        }
        captureManager.stopAudioCapture()
    }

    fun getStreamInfo(): StreamInfoPayload {
        return StreamInfoPayload(
            screenTrackId = if (captureManager.screenVideoTrack != null) CaptureManager.SCREEN_TRACK_ID else null,
            cameraTrackId = if (captureManager.cameraVideoTrack != null) CaptureManager.CAMERA_TRACK_ID else null,
            audioTrackId = if (captureManager.audioTrack != null) CaptureManager.AUDIO_TRACK_ID else null
        )
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        attachActiveTracks()

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let { sessionDesc ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            mainHandler.post {
                                try {
                                    onSuccess(sessionDesc)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in createOffer onSuccess", e)
                                }
                            }
                        }
                        override fun onCreateFailure(err: String?) {
                            val msg = err ?: "SetLocalDescription failure"
                            mainHandler.post { onError(msg) }
                        }
                        override fun onSetFailure(err: String?) {
                            val msg = err ?: "SetLocalDescription failure"
                            mainHandler.post { onError(msg) }
                        }
                    }, sessionDesc)
                } ?: run {
                    mainHandler.post { onError("Created offer description was null") }
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                val msg = err ?: "CreateOffer failure"
                mainHandler.post { onError(msg) }
            }
            override fun onSetFailure(err: String?) {
                val msg = err ?: "CreateOffer failure"
                mainHandler.post { onError(msg) }
            }
        }, constraints)
    }

    fun setRemoteAnswer(sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                mainHandler.post {
                    try {
                        onSuccess()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in setRemoteAnswer onSuccess", e)
                    }
                }
            }
            override fun onCreateFailure(err: String?) {
                val msg = err ?: "SetRemoteDescription failure"
                mainHandler.post { onError(msg) }
            }
            override fun onSetFailure(err: String?) {
                val msg = err ?: "SetRemoteDescription failure"
                mainHandler.post { onError(msg) }
            }
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        screenSender = null
        cameraSender = null
        audioSender = null

        captureManager.disposeAll()

        peerConnection?.dispose()
        peerConnection = null
    }

    fun getFactory(): PeerConnectionFactory = peerConnectionFactory
}
