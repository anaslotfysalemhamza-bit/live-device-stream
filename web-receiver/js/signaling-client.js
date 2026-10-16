/**
 * Browser WebSocket Signaling Client
 * Handles authenticated room registration, peer presence notifications,
 * and WebRTC SDP/ICE message exchanges.
 */

class BrowserSignalingClient {
  constructor() {
    this.ws = null;
    this.serverUrl = '';
    this.roomId = '';
    this.token = '';
    this.state = 'DISCONNECTED'; // DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.reconnectTimer = null;
    this.pingInterval = null;

    // Event hooks
    this.onStateChange = null;
    this.onRegistered = null;
    this.onPeerStatus = null;
    this.onOffer = null;
    this.onIceCandidate = null;
    this.onStreamState = null;
    this.onStreamInfo = null;
    this.onError = null;
  }

  /**
   * Connect to signaling server and initiate authenticated registration
   * @param {string} serverUrl
   * @param {string} roomId
   * @param {string} token
   */
  connect(serverUrl, roomId, token) {
    this.disconnect();

    this.serverUrl = serverUrl;
    this.roomId = roomId;
    this.token = token;
    this.reconnectAttempts = 0;

    this._initiateSocket();
  }

  _initiateSocket() {
    this._setState('CONNECTING');

    const WebSocketImpl = (typeof window !== 'undefined' && window.WebSocket)
      ? window.WebSocket
      : (typeof global !== 'undefined' && global.WebSocket ? global.WebSocket : null);

    if (!WebSocketImpl) {
      this._emitError('WEBSOCKET_UNSUPPORTED', 'WebSockets are not supported in this runtime');
      return;
    }

    try {
      this.ws = new WebSocketImpl(this.serverUrl);
    } catch (e) {
      this._emitError('INVALID_URL', `Failed to open WebSocket to ${this.serverUrl}: ${e.message}`);
      this._setState('DISCONNECTED');
      return;
    }

    this.ws.onopen = () => {
      this._setState('AUTHENTICATING');
      this._send({
        type: 'register',
        role: 'receiver',
        roomId: this.roomId,
        token: this.token
      });

      this._startPingHeartbeat();
    };

    this.ws.onmessage = (event) => {
      this._handleMessage(event.data);
    };

    this.ws.onclose = (event) => {
      this._stopPingHeartbeat();
      const wasConnected = this.state === 'CONNECTED';
      this._setState('DISCONNECTED');

      if (event.code === 4403) {
        this._emitError('AUTH_FAILED', event.reason || 'Authentication failed: Invalid room or session token');
      } else if (event.code === 4409) {
        this._emitError('SUPERSEDED', event.reason || 'Session superseded by newer receiver');
      } else if (wasConnected && this.reconnectAttempts < this.maxReconnectAttempts) {
        this._scheduleReconnect();
      }
    };

    this.ws.onerror = (err) => {
      this._emitError('SOCKET_ERROR', 'WebSocket connection error');
    };
  }

  _handleMessage(rawData) {
    let msg;
    try {
      msg = JSON.parse(rawData);
    } catch (e) {
      this._emitError('JSON_PARSE_ERROR', 'Received malformed message from signaling server');
      return;
    }

    switch (msg.type) {
      case 'registered':
        this._setState('CONNECTED');
        if (this.onRegistered) {
          this.onRegistered(msg);
        }
        break;

      case 'peer_status':
        if (this.onPeerStatus) {
          this.onPeerStatus(msg.peerJoined, msg.peerRole);
        }
        break;

      case 'offer':
        if (this.onOffer) {
          this.onOffer(msg.sdp);
        }
        break;

      case 'ice_candidate':
        if (this.onIceCandidate) {
          this.onIceCandidate(msg.candidate);
        }
        break;

      case 'stream_state':
        if (this.onStreamState) {
          this.onStreamState(msg);
        }
        break;

      case 'stream_info':
        if (this.onStreamInfo) {
          this.onStreamInfo(msg.streamInfo);
        }
        break;

      case 'error':
        this._emitError(msg.code || 'SIGNALING_ERROR', msg.message || 'Unknown signaling error');
        break;

      case 'pong':
        // Heartbeat acknowledged
        break;

      default:
        // Ignore unhandled
        break;
    }
  }

  /**
   * Send SDP Answer back to sender
   * @param {string} sdp
   */
  sendAnswer(sdp) {
    this._send({
      type: 'answer',
      sdp
    });
  }

  /**
   * Send local ICE candidate to sender
   * @param {any} candidate
   */
  sendIceCandidate(candidate) {
    this._send({
      type: 'ice_candidate',
      candidate
    });
  }

  _send(obj) {
    if (this.ws && this.ws.readyState === 1) { // 1 = OPEN
      this.ws.send(JSON.stringify(obj));
    }
  }

  _startPingHeartbeat() {
    this._stopPingHeartbeat();
    this.pingInterval = setInterval(() => {
      this._send({ type: 'ping' });
    }, 20000);
  }

  _stopPingHeartbeat() {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
  }

  _scheduleReconnect() {
    this.reconnectAttempts++;
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 10000);
    this.reconnectTimer = setTimeout(() => {
      this._initiateSocket();
    }, delay);
  }

  disconnect() {
    this._stopPingHeartbeat();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onerror = null;
      this.ws.onclose = null;
      try {
        this.ws.close();
      } catch (_) {}
      this.ws = null;
    }
    this._setState('DISCONNECTED');
  }

  _setState(newState) {
    if (this.state !== newState) {
      this.state = newState;
      if (this.onStateChange) {
        this.onStateChange(newState);
      }
    }
  }

  _emitError(code, message) {
    if (this.onError) {
      this.onError({ code, message });
    }
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { BrowserSignalingClient };
}
