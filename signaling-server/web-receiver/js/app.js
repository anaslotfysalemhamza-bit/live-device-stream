/**
 * Main Application Controller for Web Receiver Dashboard
 */

document.addEventListener('DOMContentLoaded', () => {
  // Elements
  const serverUrlInput = document.getElementById('serverUrl');
  const roomIdInput = document.getElementById('roomId');
  const sessionTokenInput = document.getElementById('sessionToken');
  const btnToggleToken = document.getElementById('btnToggleToken');
  const btnConnect = document.getElementById('btnConnect');
  const btnDisconnect = document.getElementById('btnDisconnect');
  
  const connectionBadge = document.getElementById('connectionBadge');
  const connectionBadgeText = document.getElementById('connectionBadgeText');
  
  const indCamera = document.getElementById('indCamera');
  const indCameraState = document.getElementById('indCameraState');
  const indScreen = document.getElementById('indScreen');
  const indScreenState = document.getElementById('indScreenState');
  const indAudio = document.getElementById('indAudio');
  const indAudioState = document.getElementById('indAudioState');
  const indPeerState = document.getElementById('indPeerState');

  const screenVideo = document.getElementById('screenVideo');
  const screenPlaceholder = document.getElementById('screenPlaceholder');
  const cameraVideo = document.getElementById('cameraVideo');
  const cameraPlaceholder = document.getElementById('cameraPlaceholder');
  const remoteAudio = document.getElementById('remoteAudio');
  const btnMuteAudio = document.getElementById('btnMuteAudio');
  const audioLevelMeter = document.getElementById('audioLevelMeter');

  const btnFullscreenScreen = document.getElementById('btnFullscreenScreen');
  const btnFullscreenCamera = document.getElementById('btnFullscreenCamera');
  const btnClearLogs = document.getElementById('btnClearLogs');
  const logOutput = document.getElementById('logOutput');

  // Pre-fill sensible default credentials
  const isHttps = window.location.protocol === 'https:';
  const defaultWsProtocol = isHttps ? 'wss:' : 'ws:';
  const defaultHost = window.location.host || 'localhost:8443';
  serverUrlInput.value = `${defaultWsProtocol}//${defaultHost}`;
  if (!roomIdInput.value) roomIdInput.value = 'ROOM-LIVE';
  if (!sessionTokenInput.value) sessionTokenInput.value = 'LIVE-1234';

  // Log Helper
  function log(message, level = 'info') {
    const time = new Date().toTimeString().split(' ')[0];
    const entry = document.createElement('div');
    entry.className = `log-entry ${level}`;
    entry.innerHTML = `<span class="timestamp">[${time}]</span> ${escapeHtml(message)}`;
    logOutput.appendChild(entry);
    logOutput.scrollTop = logOutput.scrollHeight;
  }

  function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // Toggle token visibility
  btnToggleToken.addEventListener('click', () => {
    if (sessionTokenInput.type === 'password') {
      sessionTokenInput.type = 'text';
      btnToggleToken.textContent = '🔒';
    } else {
      sessionTokenInput.type = 'password';
      btnToggleToken.textContent = '👁';
    }
  });

  // Fullscreen handlers
  btnFullscreenScreen.addEventListener('click', () => {
    const el = document.getElementById('screenContainer');
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      el.requestFullscreen().catch((e) => log(`Fullscreen error: ${e.message}`, 'warn'));
    }
  });

  btnFullscreenCamera.addEventListener('click', () => {
    const el = document.getElementById('cameraContainer');
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      el.requestFullscreen().catch((e) => log(`Fullscreen error: ${e.message}`, 'warn'));
    }
  });

  btnClearLogs.addEventListener('click', () => {
    logOutput.innerHTML = '';
  });

  let lanHostIp = window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1'
    ? window.location.hostname
    : '192.168.1.122';

  let streamInfoMapping = {
    screenTrackId: 'live_screen_track',
    cameraTrackId: 'live_camera_track',
    audioTrackId: 'live_audio_track'
  };

  // Instances
  const signaling = new BrowserSignalingClient();
  const webrtc = new WebRtcReceiver({
    onIceCandidate: (candidate) => {
      let candObj = candidate.toJSON ? candidate.toJSON() : {
        candidate: candidate.candidate,
        sdpMid: candidate.sdpMid,
        sdpMLineIndex: candidate.sdpMLineIndex
      };

      if (candObj && candObj.candidate && candObj.candidate.includes('.local')) {
        candObj.candidate = candObj.candidate.replace(/[a-zA-Z0-9-]+\.local/g, lanHostIp);
      }

      signaling.sendIceCandidate(candObj);
    },
    onTrack: (track, stream) => {
      log(`Received media track: kind=${track.kind}, id=${track.id}`, 'success');

      if (track.kind === 'video') {
        const isCamera = (track.id === streamInfoMapping.cameraTrackId) ||
          track.id.toLowerCase().includes('camera') ||
          track.label.toLowerCase().includes('camera');

        if (isCamera) {
          cameraVideo.srcObject = new MediaStream([track]);
          cameraVideo.play().catch(() => {});
          cameraPlaceholder.style.display = 'none';
          indCamera.classList.add('active');
          indCameraState.textContent = 'Streaming';
          log('Bound video track to Camera viewport.', 'info');
        } else {
          screenVideo.srcObject = new MediaStream([track]);
          screenVideo.play().catch(() => {});
          screenPlaceholder.style.display = 'none';
          indScreen.classList.add('active');
          indScreenState.textContent = 'Streaming';
          log('Bound video track to Screen Share viewport.', 'info');
        }
      } else if (track.kind === 'audio') {
        remoteAudio.srcObject = new MediaStream([track]);
        btnMuteAudio.disabled = false;
        indAudio.classList.add('active');
        indAudioState.textContent = 'Streaming';

        webrtc.startAudioMeter(track, (level) => {
          audioLevelMeter.style.width = `${level}%`;
        });
        log('Bound audio track to playback and level meter.', 'info');
      }
    },
    onConnectionStateChange: (state) => {
      log(`WebRTC Connection State: ${state}`, state === 'connected' ? 'success' : 'info');
      if (state === 'failed' || state === 'disconnected') {
        log('WebRTC connection dropped. Waiting for reconnection...', 'warn');
      }
    }
  });

  signaling.onStreamInfo = (info) => {
    if (info) {
      Object.assign(streamInfoMapping, info);
      log(`Stream mappings updated: Screen=${info.screenTrackId || 'None'}, Camera=${info.cameraTrackId || 'None'}`, 'info');
    }
  };

  // Signaling Callbacks
  signaling.onStateChange = (state) => {
    connectionBadge.className = `status-badge ${state.toLowerCase()}`;
    connectionBadgeText.textContent = state;

    if (state === 'CONNECTED') {
      btnConnect.disabled = true;
      btnDisconnect.disabled = false;
      serverUrlInput.disabled = true;
      roomIdInput.disabled = true;
      sessionTokenInput.disabled = true;
      log('Authenticated successfully with signaling server.', 'success');
    } else if (state === 'DISCONNECTED') {
      btnConnect.disabled = false;
      btnDisconnect.disabled = true;
      serverUrlInput.disabled = false;
      roomIdInput.disabled = false;
      sessionTokenInput.disabled = false;
      indPeerState.textContent = 'Offline';
      resetStreamState();
      webrtc.close();
      log('Disconnected from signaling server.', 'info');
    } else if (state === 'AUTHENTICATING') {
      log('Authenticating session credentials with room...', 'info');
    } else if (state === 'CONNECTING') {
      log(`Connecting to ${signaling.serverUrl}...`, 'info');
    }
  };

  signaling.onRegistered = (msg) => {
    log(`Registered as receiver in room: ${msg.roomId}`, 'info');
    if (msg.lanIp) {
      lanHostIp = msg.lanIp;
      log(`Detected host LAN IP: ${lanHostIp}`, 'info');
    }
    if (msg.senderActive) {
      indPeerState.textContent = 'Online';
      log('Android sender is online and ready.', 'success');
    } else {
      indPeerState.textContent = 'Awaiting Sender';
      log('Room joined. Waiting for Android sender to start session...', 'info');
    }
  };

  signaling.onPeerStatus = (peerJoined, role) => {
    if (role === 'sender') {
      if (peerJoined) {
        indPeerState.textContent = 'Online';
        log('Android device joined the session.', 'success');
      } else {
        indPeerState.textContent = 'Offline';
        resetStreamState();
        log('Android device disconnected.', 'warn');
      }
    }
  };

  signaling.onOffer = async (sdp) => {
    log('Received WebRTC offer from Android device. Negotiating...', 'info');
    try {
      const answerSdp = await webrtc.handleOffer(sdp);
      const cleanAnswer = answerSdp.replace(/[a-zA-Z0-9-]+\.local/g, lanHostIp);
      signaling.sendAnswer(cleanAnswer);
      log('Generated and sent SDP answer.', 'success');
    } catch (err) {
      log(`Failed to process WebRTC offer: ${err.message}`, 'error');
    }
  };

  signaling.onIceCandidate = async (candidate) => {
    await webrtc.addIceCandidate(candidate);
  };

  signaling.onStreamState = (stateMsg) => {
    if (stateMsg.camera !== undefined) {
      indCameraState.textContent = stateMsg.camera ? 'Active' : 'Inactive';
      indCamera.classList.toggle('active', !!stateMsg.camera);
      if (!stateMsg.camera) cameraPlaceholder.style.display = 'flex';
    }
    if (stateMsg.screen !== undefined) {
      indScreenState.textContent = stateMsg.screen ? 'Active' : 'Inactive';
      indScreen.classList.toggle('active', !!stateMsg.screen);
      if (!stateMsg.screen) screenPlaceholder.style.display = 'flex';
    }
    if (stateMsg.mic !== undefined) {
      indAudioState.textContent = stateMsg.mic ? 'Active' : 'Inactive';
      indAudio.classList.toggle('active', !!stateMsg.mic);
    }
    log(`Stream state update: Camera=${stateMsg.camera}, Screen=${stateMsg.screen}, Mic=${stateMsg.mic}`, 'info');
  };

  signaling.onError = (err) => {
    log(`[${err.code}] ${err.message}`, 'error');
  };

  function resetStreamState() {
    indCamera.classList.remove('active');
    indCameraState.textContent = 'Inactive';
    cameraPlaceholder.style.display = 'flex';
    if (cameraVideo.srcObject) cameraVideo.srcObject = null;

    indScreen.classList.remove('active');
    indScreenState.textContent = 'Inactive';
    screenPlaceholder.style.display = 'flex';
    if (screenVideo.srcObject) screenVideo.srcObject = null;

    indAudio.classList.remove('active');
    indAudioState.textContent = 'Inactive';
    if (remoteAudio.srcObject) remoteAudio.srcObject = null;
    audioLevelMeter.style.width = '0%';
    btnMuteAudio.disabled = true;
  }

  // Audio Mute toggle
  btnMuteAudio.addEventListener('click', () => {
    const newMute = !webrtc.isMuted;
    webrtc.setMuted(newMute);
    btnMuteAudio.textContent = newMute ? 'Unmute' : 'Mute';
    log(`Audio output ${newMute ? 'muted' : 'unmuted'}.`, 'info');
  });

  // Connect action
  btnConnect.addEventListener('click', () => {
    const url = serverUrlInput.value.trim();
    const roomId = roomIdInput.value.trim();
    const token = sessionTokenInput.value.trim();

    if (!url) {
      log('Please enter a valid signaling server URL.', 'error');
      return;
    }
    if (!roomId) {
      log('Please enter the Room ID displayed on your Android device.', 'error');
      return;
    }
    if (!token) {
      log('Please enter the Pairing Code / Token displayed on your Android device.', 'error');
      return;
    }

    let finalUrl = url;
    if (finalUrl.includes('10.0.2.2')) {
      finalUrl = finalUrl.replace('10.0.2.2', 'localhost');
      serverUrlInput.value = finalUrl;
      log('Notice: 10.0.2.2 is an Android emulator address. Automatically converted to localhost for your computer browser.', 'warning');
    }

    signaling.connect(finalUrl, roomId, token);
  });

  // Disconnect action
  btnDisconnect.addEventListener('click', () => {
    signaling.disconnect();
    webrtc.close();
  });
});
