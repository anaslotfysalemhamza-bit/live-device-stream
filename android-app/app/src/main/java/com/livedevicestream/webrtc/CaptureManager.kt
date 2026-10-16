package com.livedevicestream.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class CaptureManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    private val TAG = "CaptureManager"

    // Screen Capture
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenSurfaceHelper: SurfaceTextureHelper? = null
    private var screenVideoSource: VideoSource? = null
    var screenVideoTrack: VideoTrack? = null
        private set

    // Camera Capture
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraSurfaceHelper: SurfaceTextureHelper? = null
    private var cameraVideoSource: VideoSource? = null
    var cameraVideoTrack: VideoTrack? = null
        private set
    var isFrontCamera: Boolean = true
        private set

    // Audio Capture
    private var audioSource: AudioSource? = null
    var audioTrack: AudioTrack? = null
        private set
    var isAudioMuted: Boolean = false
        private set

    var onScreenCaptureStopped: (() -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null

    companion object {
        const val SCREEN_TRACK_ID = "live_screen_track"
        const val CAMERA_TRACK_ID = "live_camera_track"
        const val AUDIO_TRACK_ID = "live_audio_track"

        private const val DEFAULT_FPS = 30
        private const val TARGET_SCREEN_WIDTH = 1280
        private const val TARGET_CAMERA_WIDTH = 1280
        private const val TARGET_CAMERA_HEIGHT = 720
    }

    /**
     * Start capturing the device screen using MediaProjection permission data
     */
    fun startScreenCapture(permissionData: Intent): Boolean {
        stopScreenCapture()

        return try {
            val metrics: DisplayMetrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            // Scale to hardware-encoder friendly resolutions (720p base, even dimensions)
            val (captureWidth, captureHeight) = if (screenHeight >= screenWidth) {
                // Portrait mode (e.g. 1080x2340 -> 720x1560)
                val targetW = 720.coerceAtMost(screenWidth)
                val scale = targetW.toFloat() / screenWidth.coerceAtLeast(1)
                val w = targetW and 1.inv()
                val h = (screenHeight * scale).toInt() and 1.inv()
                Pair(w, h)
            } else {
                // Landscape mode (e.g. 2340x1080 -> 1560x720)
                val targetH = 720.coerceAtMost(screenHeight)
                val scale = targetH.toFloat() / screenHeight.coerceAtLeast(1)
                val w = (screenWidth * scale).toInt() and 1.inv()
                val h = targetH and 1.inv()
                Pair(w, h)
            }

            screenCapturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection revoked or stopped by system")
                    onScreenCaptureStopped?.invoke()
                }
            })

            screenSurfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
            screenVideoSource = peerConnectionFactory.createVideoSource(screenCapturer!!.isScreencast)

            screenCapturer!!.initialize(
                screenSurfaceHelper,
                context,
                screenVideoSource!!.capturerObserver
            )

            screenCapturer!!.startCapture(captureWidth, captureHeight, DEFAULT_FPS)
            screenVideoTrack = peerConnectionFactory.createVideoTrack(SCREEN_TRACK_ID, screenVideoSource)
            screenVideoTrack?.setEnabled(true)

            Log.d(TAG, "Screen capture started at ${captureWidth}x${captureHeight}@${DEFAULT_FPS}fps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            stopScreenCapture()
            false
        }
    }

    /**
     * Stop and release screen capture resources
     */
    fun stopScreenCapture() {
        try {
            screenVideoTrack?.setEnabled(false)
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping screen capturer: ${e.message}")
        }

        try {
            screenCapturer?.dispose()
            screenVideoSource?.dispose()
            screenSurfaceHelper?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing screen resources: ${e.message}")
        }

        screenCapturer = null
        screenVideoSource = null
        screenSurfaceHelper = null
        screenVideoTrack = null
    }

    /**
     * Start camera capture (front camera by default)
     */
    fun startCameraCapture(preferFront: Boolean = true): Boolean {
        stopCameraCapture()

        return try {
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames

            val targetDevice = deviceNames.firstOrNull { name ->
                if (preferFront) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
            } ?: deviceNames.firstOrNull()

            if (targetDevice == null) {
                Log.w(TAG, "No suitable camera device found")
                onCameraError?.invoke("No camera found on device")
                return false
            }

            isFrontCamera = enumerator.isFrontFacing(targetDevice)

            cameraCapturer = enumerator.createCapturer(targetDevice, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(errMsg: String?) {
                    Log.e(TAG, "Camera error: $errMsg")
                    onCameraError?.invoke(errMsg ?: "Camera device error")
                }

                override fun onCameraDisconnected() {
                    Log.w(TAG, "Camera disconnected")
                    onCameraError?.invoke("Camera disconnected")
                }

                override fun onCameraFreezed(errMsg: String?) {
                    Log.w(TAG, "Camera frozen: $errMsg")
                }

                override fun onCameraOpening(cameraName: String?) {
                    Log.d(TAG, "Opening camera: $cameraName")
                }

                override fun onFirstFrameAvailable() {
                    Log.d(TAG, "First camera frame available")
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "Camera closed")
                }
            })

            cameraSurfaceHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglBase.eglBaseContext)
            cameraVideoSource = peerConnectionFactory.createVideoSource(false)

            cameraCapturer!!.initialize(
                cameraSurfaceHelper,
                context,
                cameraVideoSource!!.capturerObserver
            )

            cameraCapturer!!.startCapture(TARGET_CAMERA_WIDTH, TARGET_CAMERA_HEIGHT, DEFAULT_FPS)
            cameraVideoTrack = peerConnectionFactory.createVideoTrack(CAMERA_TRACK_ID, cameraVideoSource)
            cameraVideoTrack?.setEnabled(true)

            Log.d(TAG, "Camera capture started (front=$isFrontCamera)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera capture", e)
            stopCameraCapture()
            onCameraError?.invoke(e.message ?: "Failed to open camera")
            false
        }
    }

    /**
     * Switch between front and back camera
     */
    fun switchCamera() {
        cameraCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                Log.d(TAG, "Switched camera, isFront=$isFront")
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "Camera switch error: $errorDescription")
                onCameraError?.invoke(errorDescription ?: "Switch camera failed")
            }
        })
    }

    /**
     * Stop and release camera capture resources
     */
    fun stopCameraCapture() {
        try {
            cameraVideoTrack?.setEnabled(false)
            cameraCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping camera capturer: ${e.message}")
        }

        try {
            cameraCapturer?.dispose()
            cameraVideoSource?.dispose()
            cameraSurfaceHelper?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing camera resources: ${e.message}")
        }

        cameraCapturer = null
        cameraVideoSource = null
        cameraSurfaceHelper = null
        cameraVideoTrack = null
    }

    /**
     * Start capturing microphone audio
     */
    fun startAudioCapture(): Boolean {
        stopAudioCapture()

        return try {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }

            audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
            audioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
            audioTrack?.setEnabled(true)
            isAudioMuted = false

            Log.d(TAG, "Microphone audio capture started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stopAudioCapture()
            false
        }
    }

    /**
     * Set microphone mute state
     */
    fun setAudioMuted(muted: Boolean) {
        isAudioMuted = muted
        audioTrack?.setEnabled(!muted)
    }

    /**
     * Stop and release audio capture resources
     */
    fun stopAudioCapture() {
        try {
            audioTrack?.setEnabled(false)
            audioTrack?.dispose()
            audioSource?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio capture: ${e.message}")
        }

        audioTrack = null
        audioSource = null
    }

    /**
     * Release all capture resources
     */
    fun disposeAll() {
        stopScreenCapture()
        stopCameraCapture()
        stopAudioCapture()
    }
}
