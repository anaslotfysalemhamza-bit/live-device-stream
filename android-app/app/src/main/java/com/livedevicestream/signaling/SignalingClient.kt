package com.livedevicestream.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val roomId: String,
    private val token: String,
    private val listener: SignalingListener
) {
    private val TAG = "SignalingClient"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var isConnected = false

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                sendMessage(SignalingMessage.ping())
                mainHandler.postDelayed(this, 25000)
            }
        }
    }

    fun connect() {
        disconnect()

        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                Log.d(TAG, "WebSocket connected to $serverUrl")
                mainHandler.post {
                    listener.onConnected()
                    // Send registration immediately
                    sendMessage(SignalingMessage.registerSender(roomId, token))
                    mainHandler.postDelayed(pingRunnable, 25000)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = gson.fromJson(text, SignalingMessage::class.java)
                    mainHandler.post { handleMessage(msg) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse signaling message: $text", e)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d(TAG, "WebSocket closed: $code / $reason")
                mainHandler.removeCallbacks(pingRunnable)
                mainHandler.post { listener.onDisconnected() }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                mainHandler.removeCallbacks(pingRunnable)
                mainHandler.post {
                    listener.onError("SOCKET_FAILURE", t.message ?: "Connection failure")
                    listener.onDisconnected()
                }
            }
        })
    }

    private fun handleMessage(msg: SignalingMessage) {
        when (msg.type) {
            "registered" -> {
                msg.roomId?.let { listener.onRegistered(it) }
            }
            "peer_status" -> {
                val joined = msg.peerJoined ?: false
                val role = msg.peerRole ?: "receiver"
                listener.onPeerStatus(joined, role)
            }
            "offer" -> {
                msg.sdp?.let { listener.onOffer(it) }
            }
            "answer" -> {
                msg.sdp?.let { listener.onAnswer(it) }
            }
            "ice_candidate" -> {
                msg.candidate?.let { listener.onIceCandidate(it) }
            }
            "error" -> {
                listener.onError(msg.code ?: "ERROR", msg.message ?: "Signaling error")
            }
            "pong" -> {
                // Heartbeat response
            }
        }
    }

    fun sendMessage(msg: SignalingMessage) {
        val json = gson.toJson(msg)
        webSocket?.send(json)
    }

    fun sendOffer(sdp: String) {
        sendMessage(SignalingMessage.offer(sdp))
    }

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        sendMessage(SignalingMessage.iceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun sendStreamState(camera: Boolean, mic: Boolean, screen: Boolean) {
        sendMessage(SignalingMessage.streamState(camera, mic, screen))
    }

    fun sendStreamInfo(screenTrackId: String?, cameraTrackId: String?, audioTrackId: String?) {
        sendMessage(SignalingMessage.streamInfo(screenTrackId, cameraTrackId, audioTrackId))
    }

    fun disconnect() {
        isConnected = false
        mainHandler.removeCallbacks(pingRunnable)
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient = null
    }
}
