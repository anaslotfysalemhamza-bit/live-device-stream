/**
 * WebRTC Receiver Abstraction
 * Manages RTCPeerConnection lifecycle, SDP negotiation, ICE trickling,
 * and track binding to DOM video and audio elements.
 */

class WebRtcReceiver {
  /**
   * @param {object} config
   * @param {RTCConfiguration} [config.rtcConfig]
   * @param {(candidate: RTCIceCandidate) => void} config.onIceCandidate
   * @param {(track: MediaStreamTrack, stream: MediaStream) => void} [config.onTrack]
   * @param {(state: RTCPeerConnectionState) => void} [config.onConnectionStateChange]
   */
  constructor(config = {}) {
    this.rtcConfig = config.rtcConfig || {
      iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        { urls: 'stun:stun2.l.google.com:19302' },
        { urls: 'stun:stun.cloudflare.com:3478' }
      ]
    };
    this.onIceCandidate = config.onIceCandidate || (() => {});
    this.onTrack = config.onTrack || (() => {});
    this.onConnectionStateChange = config.onConnectionStateChange || (() => {});

    this.peerConnection = null;
    this.remoteStream = null;
    this.audioContext = null;
    this.audioAnalyser = null;
    this.isMuted = false;
  }

  /**
   * Initializes or resets the RTCPeerConnection instance
   */
  initializePeerConnection() {
    this.close();

    if (typeof RTCPeerConnection === 'undefined') {
      console.warn('[WebRTC] RTCPeerConnection not supported in this runtime.');
      return;
    }

    this.peerConnection = new RTCPeerConnection(this.rtcConfig);
    this.remoteStream = new MediaStream();

    this.peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        this.onIceCandidate(event.candidate);
      }
    };

    this.peerConnection.onconnectionstatechange = () => {
      if (this.peerConnection) {
        this.onConnectionStateChange(this.peerConnection.connectionState);
      }
    };

    this.peerConnection.ontrack = (event) => {
      if (event.streams && event.streams[0]) {
        this.onTrack(event.track, event.streams[0]);
      } else {
        this.remoteStream.addTrack(event.track);
        this.onTrack(event.track, this.remoteStream);
      }
    };
  }

  /**
   * Processes remote SDP Offer and produces local SDP Answer
   * @param {string} offerSdp
   * @returns {Promise<string>} answer SDP
   */
  async handleOffer(offerSdp) {
    if (!this.peerConnection) {
      this.initializePeerConnection();
    }

    const desc = new RTCSessionDescription({ type: 'offer', sdp: offerSdp });
    await this.peerConnection.setRemoteDescription(desc);

    const answer = await this.peerConnection.createAnswer();
    await this.peerConnection.setLocalDescription(answer);

    return answer.sdp;
  }

  /**
   * Adds an ICE candidate received from sender
   * @param {RTCIceCandidateInit} candidateInit
   */
  async addIceCandidate(candidateInit) {
    if (!this.peerConnection) return;
    try {
      await this.peerConnection.addIceCandidate(new RTCIceCandidate(candidateInit));
    } catch (err) {
      console.warn('[WebRTC] Failed to add ICE candidate:', err);
    }
  }

  /**
   * Attaches audio track to Web Audio API analyzer for volume metering
   * @param {MediaStreamTrack} audioTrack
   * @param {(levelPercent: number) => void} onLevel
   */
  startAudioMeter(audioTrack, onLevel) {
    if (typeof AudioContext === 'undefined' && typeof webkitAudioContext === 'undefined') return;

    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioCtx();
      const stream = new MediaStream([audioTrack]);
      const source = this.audioContext.createMediaStreamSource(stream);
      this.audioAnalyser = this.audioContext.createAnalyser();
      this.audioAnalyser.fftSize = 256;
      source.connect(this.audioAnalyser);

      const bufferLength = this.audioAnalyser.frequencyBinCount;
      const dataArray = new Uint8Array(bufferLength);

      const updateMeter = () => {
        if (!this.audioAnalyser) return;
        this.audioAnalyser.getByteFrequencyData(dataArray);
        let sum = 0;
        for (let i = 0; i < bufferLength; i++) {
          sum += dataArray[i];
        }
        const average = sum / bufferLength;
        const percent = Math.min(100, Math.round((average / 128) * 100));
        onLevel(percent);
        requestAnimationFrame(updateMeter);
      };
      requestAnimationFrame(updateMeter);
    } catch (e) {
      console.warn('[WebRTC] Audio meter setup warning:', e);
    }
  }

  /**
   * Set audio mute state
   * @param {boolean} muted
   */
  setMuted(muted) {
    this.isMuted = muted;
    if (this.remoteStream) {
      this.remoteStream.getAudioTracks().forEach((track) => {
        track.enabled = !muted;
      });
    }
  }

  /**
   * Close and release all resources
   */
  close() {
    if (this.audioContext) {
      try {
        this.audioContext.close();
      } catch (_) {}
      this.audioContext = null;
      this.audioAnalyser = null;
    }

    if (this.peerConnection) {
      this.peerConnection.ontrack = null;
      this.peerConnection.onicecandidate = null;
      this.peerConnection.onconnectionstatechange = null;
      try {
        this.peerConnection.close();
      } catch (_) {}
      this.peerConnection = null;
    }

    this.remoteStream = null;
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { WebRtcReceiver };
}
