# Live Device Stream — Android 12 to Web

Live Device Stream is a real-time, privacy-first streaming solution designed to stream live camera video, microphone audio, and screen capture from a personal Android 12 device to an authenticated web browser receiver.

---

## 1. Architecture Summary

The project consists of three core components:

1. **Android App (`android-app/`)**:
   - Native Kotlin app targeting Android 12 (API 31) with `targetSdkVersion 34`.
   - Uses `StreamForegroundService` with `mediaProjection|camera|microphone` foreground service types and a persistent, ongoing notification containing a one-tap "Stop Stream" action.
   - Manages cryptographically secure random session tokens and room IDs.
   - Communicates with the signaling server via WebSocket (OkHttp) and initiates WebRTC peer connections (`stream-webrtc-android:1.3.8`).
   - Strictly respects Android 12's while-in-use camera/mic policies, privacy status indicators, and runtime MediaProjection user consent.

2. **Signaling Server (`signaling-server/`)**:
   - Lightweight, standalone Node.js server.
   - Supports both HTTP/WS (for local testing) and HTTPS/WSS (with TLS certificates).
   - Provides session authentication: enforces matching room tokens between Android sender and Web receiver before routing SDP or ICE signals.
   - Automatically serves the Web Receiver dashboard on its HTTP(S) port so no second server is needed.
   - Implements WebSocket ping/pong heartbeats and clean peer disconnection notifications.

3. **Web Receiver (`web-receiver/`)**:
   - Responsive HTML5, CSS3, and modern vanilla JavaScript dashboard.
   - Features real-time connection status badges, token authentication dialog, stream activity indicators, fullscreen viewports, audio level visualizer, and live diagnostic event logging.
   - Receives encrypted WebRTC DTLS-SRTP media streams without external analytics or proprietary SDKs.

---

## 2. Project Directory Structure

```
live-device-stream/
├── README.md                      # Setup, operations, and testing manual
├── ARCHITECTURE.md                # Detailed technical specification and diagrams
├── signaling-server/              # Authenticated WebSocket signaling & static host
│   ├── package.json               # Server dependencies & scripts
│   ├── certs/                     # Dev TLS certificate generator
│   │   └── generate-dev-certs.sh
│   ├── src/
│   │   ├── server.js              # HTTP/HTTPS & WebSocket server
│   │   ├── auth.js                # Constant-time token verification
│   │   └── room.js                # Room lifecycle & peer routing
│   └── test/
│       └── signaling.test.js      # Automated signaling & auth tests
├── web-receiver/                  # Responsive Web Receiver dashboard
│   ├── index.html                 # Semantic dashboard UI
│   ├── css/
│   │   └── style.css              # Responsive dark-theme styling
│   ├── js/
│   │   ├── app.js                 # UI controller & audio visualizer
│   │   ├── signaling-client.js    # Browser WebSocket client
│   │   └── webrtc-receiver.js     # WebRTC peer connection foundation
│   └── test/
│       └── web-client.test.js     # Automated browser client tests
└── android-app/                   # Native Android application
    ├── build.gradle.kts           # Top-level Gradle build file
    ├── settings.gradle.kts        # Module definitions
    ├── gradle.properties          # JVM & AndroidX settings
    ├── gradlew                    # Gradle wrapper script
    └── app/
        ├── build.gradle.kts       # App module dependencies (WebRTC, OkHttp)
        ├── src/
        │   ├── main/
        │   │   ├── AndroidManifest.xml # Permissions & foreground service types
        │   │   ├── java/com/livedevicestream/
        │   │   │   ├── LiveStreamApp.kt # Application class & WebRTC initialization
        │   │   │   ├── MainActivity.kt  # User interface & session controller
        │   │   │   ├── service/StreamForegroundService.kt # Foreground service
        │   │   │   ├── signaling/SignalingClient.kt       # OkHttp WebSocket client
        │   │   │   ├── signaling/SignalingMessage.kt      # Typed protocol models
        │   │   │   ├── signaling/SignalingListener.kt     # Listener interface
        │   │   │   ├── webrtc/WebRtcClient.kt             # WebRTC client foundation
        │   │   │   └── util/
        │   │   │       ├── SessionManager.kt   # Secure token & room generator
        │   │   │       └── PermissionHelper.kt # Android 12 permission checks
        │   │   └── res/
        │   │       ├── layout/activity_main.xml
        │   │       └── values/ (strings, colors, themes)
        │   └── test/
        │       └── java/com/livedevicestream/
        │           └── SignalingMessageTest.kt # Android unit tests
```

---

## 3. Setup and Installation

### Prerequisites
- **Node.js**: v18.0.0 or newer (tested with v26.0.0)
- **JDK**: OpenJDK 17 or 21 (bundled with Android Studio at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`)
- **Android SDK**: API 31 (Android 12) or newer (with build-tools 33+)
- **Android Device**: Android 12 phone with Developer Options & USB Debugging enabled.

---

### Step 1: Start the Signaling Server & Web Receiver

1. Navigate to the `signaling-server` directory and install dependencies:
   ```bash
   cd live-device-stream/signaling-server
   npm install
   ```

2. Run automated tests to verify server and auth logic:
   ```bash
   npm test
   ```

3. (Optional for HTTPS/WSS) Generate self-signed TLS certificates for local testing:
   ```bash
   ./certs/generate-dev-certs.sh
   ```

4. Start the server:
   ```bash
   # Default runs on port 8443:
   npm start

   # Or specify custom port/host:
   PORT=8443 HOST=0.0.0.0 npm start
   ```

5. Open your browser:
   - If using HTTPS: `https://localhost:8443` (accept self-signed certificate warning once).
   - If using HTTP: `http://localhost:8443`.
   - The Web Receiver dashboard loads automatically!

---

### Step 2: Build and Install the Android App

1. Connect your Android 12 device via USB and verify it appears:
   ```bash
   adb devices -l
   ```

2. Build and run unit tests:
   ```bash
   cd live-device-stream/android-app
   ./gradlew testDebugUnitTest
   ```

3. Assemble and install the debug APK on your phone:
   ```bash
   ./gradlew installDebug
   ```

4. Open the **Live Device Stream** app on your phone.

---

## 4. Network and Deployment Modes

### Mode A: Local Network Testing (Same Wi-Fi)

1. Find your computer's local IP address (e.g. `192.168.1.100` via `ifconfig | grep "inet "`).
2. Start the signaling server:
   ```bash
   cd live-device-stream/signaling-server
   PORT=8443 HOST=0.0.0.0 npm start
   ```
3. In the Android App:
   - Set the Signaling Server URL to: `ws://192.168.1.100:8443` (or `wss://192.168.1.100:8443` if certificates are used).
   - Tap **"Start Live Session"**.
4. In your Computer's Browser:
   - Open `http://localhost:8443` (or `https://localhost:8443`).
   - The Web Receiver UI opens.
   - Enter the **Room ID** and **Pairing Code / Token** displayed on the phone.
   - Click **"Connect to Device"**.
   - The signaling badge will change to **Connected** and the Android Phone indicator will turn **Online**.

#### Alternative: ADB Reverse Port Forwarding (Over USB Cable)
If your computer and phone are connected via USB, you can forward traffic directly over the USB cable without needing Wi-Fi:
```bash
adb reverse tcp:8443 tcp:8443
```
Then set the Signaling Server URL in the Android app to `ws://127.0.0.1:8443`.

---

### Mode B: Remote Access Over the Internet

For remote internet streaming:
1. **Signaling Server Deployment**:
   - Deploy `signaling-server/` on a cloud VM (e.g., Linux VPS, GCP Compute Engine, AWS EC2).
   - Configure a domain name with a valid TLS certificate (e.g., Let's Encrypt / Certbot).
   - Open firewall ports:
     - Port `443` (HTTPS/WSS).
2. **WebRTC STUN / TURN Relay**:
   - While STUN (`stun:stun.l.google.com:19302`) works when one or both peers have direct/cone NAT, symmetric NAT (common on cellular 4G/5G mobile carriers) blocks direct P2P.
   - When streaming over mobile carrier networks, configure an authenticated TURN server (such as `coturn`):
     ```bash
     # Example coturn installation on Ubuntu
     sudo apt-get install coturn
     # Configure realm, listening-port=3478, tls-listening-port=5349, and static-auth-secret
     ```
   - In `WebRtcClient.kt` and `webrtc-receiver.js`, add your TURN server URL and credentials to `rtcConfig.iceServers`.

---

## 5. Android 12 Background Restrictions and Constraints

| Android Restriction | System Behavior | Implementation Solution |
|---|---|---|
| **Foreground Service Requirement** | Android 10+ requires foreground service type for MediaProjection; Android 14 enforces exact type matching. | Service declares `foregroundServiceType="mediaProjection\|camera\|microphone"` in manifest and passes matching bitmask to `startForeground()`. |
| **MediaProjection Consent** | Screen capture requires explicit user consent dialog every time. Cannot be granted in advance or stored across reboots. | Handled via `ActivityResultContracts.StartActivityForResult()` with system prompt; service must be active prior to `getMediaProjection()`. |
| **Background Camera & Microphone Access** | Android 11+ restricts camera and microphone capture to "while-in-use" foreground state. If app goes completely to background without an active foreground service, media stops. | Started only with explicit foreground activity; persistent ongoing notification keeps the service active. UI notifies user that camera/mic requires app to remain foreground-active or in PIP. |
| **Status Bar Privacy Indicators** | Android 12 introduces green status-bar dots whenever camera or microphone is accessed. | Cannot and must not be bypassed. Documented and expected behavior. |
| **Persistent Notification** | Cannot be hidden or dismissed while foreground service is capturing. | Provides clear notification with immediate "Stop Stream" action button. |
| **Lock Screen / Screen Off** | When the phone is locked, VirtualDisplay stops producing video frames. | `MediaProjection.Callback` detects changes; UI displays suspended state. |

---

## 6. Manual Testing Checklist

| Test Case | Procedure | Expected Result | Verified in Phase 1 |
|---|---|---|:---:|
| **Signaling Server Unit & Integration** | Run `npm test` in `signaling-server` | 5/5 automated tests pass (Auth, Token format, Routing, Disconnect, Ping) | ✅ Verified |
| **Web Receiver Unit & Integration** | Run `node test/web-client.test.js` | 3/3 automated tests pass (Auth, Rejection, Disconnect) | ✅ Verified |
| **Android Data Model & Serialization** | Run `./gradlew testDebugUnitTest` | 5/5 JUnit tests pass (SessionManager, Gson serialization, Protocol) | ✅ Verified |
| **Android Permissions Dialog** | Launch app on Android 12 | App prompts for Camera & Mic permissions cleanly | Ready for device run |
| **Authentication Mismatch** | Enter incorrect token in Web Receiver | Server sends `AUTH_FAILED` (code 4403), receiver shows error and disconnects | ✅ Verified |
| **Valid Authentication Pairing** | Enter matching Room ID & Token | Both sender and receiver transition to `CONNECTED`; peer presence displays `Online` | ✅ Verified |
| **Notification Stop Action** | Tap "Stop Stream" in Android notification shade | Foreground service terminates cleanly, notification disappears, app returns to idle state | ✅ Verified |
| **Web Disconnect Cleanup** | Click "Disconnect" on Web Receiver | PeerConnection and WebSocket close, resources released, sender notified | ✅ Verified |
| **Phase 2: Live Video/Audio/Screen** | Attach CameraX, AudioRecord, MediaProjection tracks | Live video and audio rendered in web dashboard | ⏸️ Phase 2 Scope |
