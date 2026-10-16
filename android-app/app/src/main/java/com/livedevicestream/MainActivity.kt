package com.livedevicestream

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.livedevicestream.databinding.ActivityMainBinding
import com.livedevicestream.service.StreamForegroundService
import com.livedevicestream.signaling.IceCandidatePayload
import com.livedevicestream.signaling.SignalingClient
import com.livedevicestream.signaling.SignalingListener
import com.livedevicestream.util.PermissionHelper
import com.livedevicestream.util.SessionManager
import com.livedevicestream.webrtc.WebRtcClient

class MainActivity : AppCompatActivity(), SignalingListener {

    private lateinit var binding: ActivityMainBinding

    private var currentSession = SessionManager.getDefaultSession()
    private var signalingClient: SignalingClient? = null
    private var webRtcClient: WebRtcClient? = null
    private var isSessionActive = false
    private var screenCapturePermissionIntent: Intent? = null
    private var isNegotiating = false
    private var isUpdatingSwitchProgrammatically = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted && audioGranted) {
            Toast.makeText(this, "Permissions granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Camera/Microphone permissions required for full streaming.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val permissionIntent = result.data!!
            screenCapturePermissionIntent = permissionIntent
            Toast.makeText(this, "Screen capture consent granted.", Toast.LENGTH_SHORT).show()

            if (isSessionActive && webRtcClient != null) {
                // Update foreground service to declare mediaProjection now that user consented
                updateForegroundServiceState(hasScreen = true)

                // Android 14/15/16 requires the service to be in FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                // before getMediaProjection is invoked. A brief delay ensures the system state is updated.
                binding.root.postDelayed({
                    if (!isSessionActive || webRtcClient == null) return@postDelayed
                    val success = webRtcClient?.startScreenShare(permissionIntent) ?: false
                    if (success) {
                        broadcastStreamState()
                        renegotiatePeerConnection()
                        Toast.makeText(this, "Screen sharing started", Toast.LENGTH_SHORT).show()
                    } else {
                        isUpdatingSwitchProgrammatically = true
                        binding.switchScreen.isChecked = false
                        isUpdatingSwitchProgrammatically = false
                        screenCapturePermissionIntent = null
                        updateForegroundServiceState(hasScreen = false)
                        Toast.makeText(this, "Failed to initialize screen capturer.", Toast.LENGTH_SHORT).show()
                    }
                }, 250)
            }
        } else {
            isUpdatingSwitchProgrammatically = true
            binding.switchScreen.isChecked = false
            isUpdatingSwitchProgrammatically = false
            screenCapturePermissionIntent = null
            updateForegroundServiceState(hasScreen = false)
            Toast.makeText(this, "Screen capture consent was cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDefaultUi()
        setupListeners()
        checkPermissionsOnStartup()

        StreamForegroundService.onServiceStateChanged = { running ->
            runOnUiThread {
                if (!running && isSessionActive) {
                    stopLiveSession()
                }
            }
        }
    }

    private fun setupDefaultUi() {
        // Default to production server
        binding.etServerUrl.setText("wss://livedevicestream3-jmoa4dzy.b4a.run")
        displaySessionCredentials()
    }

    private fun displaySessionCredentials() {
        binding.tvRoomId.text = currentSession.roomId
        binding.tvSessionToken.text = currentSession.token
    }

    private fun setupListeners() {
        binding.btnRegenerate.setOnClickListener {
            if (!isSessionActive) {
                currentSession = SessionManager.createSession()
                displaySessionCredentials()
                Toast.makeText(this, "New session credentials generated.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyRoom.setOnClickListener {
            copyToClipboard("Room ID", currentSession.roomId)
        }

        binding.btnCopyToken.setOnClickListener {
            copyToClipboard("Pairing Token", currentSession.token)
        }

        binding.btnStartSession.setOnClickListener {
            startLiveSession()
        }

        binding.btnStopSession.setOnClickListener {
            stopLiveSession()
        }

        // Screen Share Switch: Android 14/15/16 requires fresh consent every time
        binding.switchScreen.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            if (isChecked) {
                if (isSessionActive) {
                    requestScreenCaptureConsent()
                }
            } else {
                if (isSessionActive) {
                    screenCapturePermissionIntent = null
                    webRtcClient?.stopScreenShare()
                    updateForegroundServiceState(hasScreen = false)
                    broadcastStreamState()
                    renegotiatePeerConnection()
                }
            }
        }

        // Camera Switch
        binding.switchCamera.setOnCheckedChangeListener { _, isChecked ->
            if (isSessionActive) {
                if (isChecked) {
                    if (PermissionHelper.hasCameraPermission(this)) {
                        webRtcClient?.startCamera(preferFront = true)
                        updateForegroundServiceState(hasCamera = true)
                        broadcastStreamState()
                        renegotiatePeerConnection()
                    } else {
                        binding.switchCamera.isChecked = false
                        permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA))
                    }
                } else {
                    webRtcClient?.stopCamera()
                    updateForegroundServiceState(hasCamera = false)
                    broadcastStreamState()
                    renegotiatePeerConnection()
                }
            }
        }

        // Flip Camera Button
        binding.btnFlipCamera.setOnClickListener {
            webRtcClient?.captureManager?.switchCamera()
            val isFront = webRtcClient?.captureManager?.isFrontCamera ?: true
            binding.tvCameraSubtext.text = if (isFront) "Front camera active" else "Back camera active"
        }

        // Microphone Switch
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isSessionActive) {
                if (isChecked) {
                    if (PermissionHelper.hasAudioPermission(this)) {
                        webRtcClient?.startAudio()
                        updateForegroundServiceState(hasAudio = true)
                        broadcastStreamState()
                        renegotiatePeerConnection()
                    } else {
                        binding.switchAudio.isChecked = false
                        permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                    }
                } else {
                    webRtcClient?.stopAudio()
                    updateForegroundServiceState(hasAudio = false)
                    broadcastStreamState()
                    renegotiatePeerConnection()
                }
            }
        }
    }

    private fun requestScreenCaptureConsent() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun checkPermissionsOnStartup() {
        val permissionsToRequest = mutableListOf<String>()
        if (!PermissionHelper.hasCameraPermission(this)) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }
        if (!PermissionHelper.hasAudioPermission(this)) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun updateForegroundServiceState(
        hasScreen: Boolean? = null,
        hasCamera: Boolean? = null,
        hasAudio: Boolean? = null
    ) {
        val currentScreen = hasScreen ?: (screenCapturePermissionIntent != null && binding.switchScreen.isChecked)
        val currentCamera = hasCamera ?: (binding.switchCamera.isChecked && PermissionHelper.hasCameraPermission(this))
        val currentAudio = hasAudio ?: (binding.switchAudio.isChecked && PermissionHelper.hasAudioPermission(this))

        StreamForegroundService.update(this, currentScreen, currentCamera, currentAudio)
    }

    private fun startLiveSession() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Please enter a valid signaling server URL", Toast.LENGTH_SHORT).show()
            return
        }

        isSessionActive = true
        updateUiState(isStarting = true)

        val hasCamera = binding.switchCamera.isChecked && PermissionHelper.hasCameraPermission(this)
        val hasAudio = binding.switchAudio.isChecked && PermissionHelper.hasAudioPermission(this)

        // 1. Start foreground service safely without mediaProjection until consent is given
        StreamForegroundService.start(
            context = this,
            hasScreen = false,
            hasCamera = hasCamera,
            hasAudio = hasAudio
        )

        // 2. Initialize WebRTC client
        webRtcClient = WebRtcClient(
            context = this,
            onLocalIceCandidate = { candidate ->
                signalingClient?.sendIceCandidate(
                    candidate.sdpMid,
                    candidate.sdpMLineIndex,
                    candidate.sdp
                )
            },
            onConnectionChange = { state ->
                runOnUiThread {
                    binding.tvSessionStatus.text = "WebRTC: $state"
                }
            }
        )
        webRtcClient?.initializePeerConnection()

        webRtcClient?.captureManager?.onScreenCaptureStopped = {
            runOnUiThread {
                binding.switchScreen.isChecked = false
                screenCapturePermissionIntent = null
                updateForegroundServiceState(hasScreen = false)
                broadcastStreamState()
                renegotiatePeerConnection()
                Toast.makeText(this, "Screen capture was stopped", Toast.LENGTH_SHORT).show()
            }
        }

        webRtcClient?.captureManager?.onCameraError = { err ->
            runOnUiThread {
                Toast.makeText(this, "Camera Error: $err", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Start active media captures based on switches
        if (hasCamera) {
            webRtcClient?.startCamera(preferFront = true)
        }

        if (hasAudio) {
            webRtcClient?.startAudio()
        }

        // For Screen Share: On Android 14/15/16, always prompt for fresh consent
        if (binding.switchScreen.isChecked) {
            requestScreenCaptureConsent()
        }

        // 4. Connect to signaling server
        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            roomId = currentSession.roomId,
            token = currentSession.token,
            listener = this
        )
        signalingClient?.connect()
    }

    private fun stopLiveSession() {
        isSessionActive = false
        isNegotiating = false
        screenCapturePermissionIntent = null
        updateUiState(isStarting = false)

        signalingClient?.disconnect()
        signalingClient = null

        webRtcClient?.close()
        webRtcClient = null

        StreamForegroundService.stop(this)
        binding.tvSessionStatus.text = getString(R.string.status_idle)
        binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
    }

    private fun broadcastStreamState() {
        val hasCam = webRtcClient?.captureManager?.cameraVideoTrack != null
        val hasScreen = webRtcClient?.captureManager?.screenVideoTrack != null
        val hasMic = webRtcClient?.captureManager?.audioTrack != null

        signalingClient?.sendStreamState(hasCam, hasMic, hasScreen)

        val streamInfo = webRtcClient?.getStreamInfo()
        if (streamInfo != null) {
            signalingClient?.sendStreamInfo(
                streamInfo.screenTrackId,
                streamInfo.cameraTrackId,
                streamInfo.audioTrackId
            )
        }
    }

    private fun renegotiatePeerConnection() {
        if (!isSessionActive || webRtcClient == null) return
        if (isNegotiating) return
        isNegotiating = true

        webRtcClient?.createOffer(
            onSuccess = { sdp ->
                signalingClient?.sendOffer(sdp.description)
                broadcastStreamState()
            },
            onError = { err ->
                isNegotiating = false
                runOnUiThread {
                    Toast.makeText(this, "Renegotiation failed: $err", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun updateUiState(isStarting: Boolean) {
        binding.btnStartSession.visibility = if (isStarting) View.GONE else View.VISIBLE
        binding.btnStopSession.visibility = if (isStarting) View.VISIBLE else View.GONE
        binding.btnRegenerate.isEnabled = !isStarting
        binding.etServerUrl.isEnabled = !isStarting

        if (isStarting) {
            binding.tvSessionStatus.text = getString(R.string.status_connecting)
            binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_warning))
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // SignalingListener callbacks
    override fun onConnected() {
        runOnUiThread {
            binding.tvSessionStatus.text = "Signaling connected. Authenticating…"
        }
    }

    override fun onRegistered(roomId: String) {
        runOnUiThread {
            binding.tvSessionStatus.text = getString(R.string.status_connected)
            binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_success))
            Toast.makeText(this, "Session active. Enter Room ID & Token in browser.", Toast.LENGTH_LONG).show()
            broadcastStreamState()
        }
    }

    override fun onPeerStatus(peerJoined: Boolean, peerRole: String) {
        runOnUiThread {
            if (peerJoined && peerRole == "receiver") {
                binding.tvSessionStatus.text = getString(R.string.status_receiver_online)
                binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.secondary))
                Toast.makeText(this, "Web receiver connected!", Toast.LENGTH_SHORT).show()

                // Create initial offer with active media tracks
                renegotiatePeerConnection()
            } else if (!peerJoined && peerRole == "receiver") {
                binding.tvSessionStatus.text = getString(R.string.status_connected)
                binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_success))
                Toast.makeText(this, "Web receiver disconnected.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOffer(sdp: String) {
        // Sender initiates offers
    }

    override fun onAnswer(sdp: String) {
        runOnUiThread {
            webRtcClient?.setRemoteAnswer(
                sdp,
                onSuccess = {
                    isNegotiating = false
                    binding.tvSessionStatus.text = "WebRTC Live & Streaming"
                    broadcastStreamState()
                },
                onError = { err ->
                    isNegotiating = false
                    Toast.makeText(this, "Failed to set remote answer: $err", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    override fun onIceCandidate(candidate: IceCandidatePayload) {
        val iceCandidate = org.webrtc.IceCandidate(
            candidate.sdpMid,
            candidate.sdpMLineIndex,
            candidate.candidate
        )
        webRtcClient?.addIceCandidate(iceCandidate)
    }

    override fun onError(code: String, message: String) {
        runOnUiThread {
            binding.tvSessionStatus.text = "Error: $code"
            binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_error))
            Toast.makeText(this, "Signaling Error: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            if (isSessionActive) {
                binding.tvSessionStatus.text = "Signaling disconnected"
                binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_error))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiveSession()
        StreamForegroundService.onServiceStateChanged = null
    }
}
