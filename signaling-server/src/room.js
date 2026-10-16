/**
 * Room and Session Management
 * Manages authenticated rooms, roles (sender/receiver), and message routing.
 */

const { isValidTokenFormat, secureCompare } = require('./auth');

class RoomManager {
  constructor() {
    /** @type {Map<string, { token: string, sender: any, receivers: Set<any>, createdAt: number }>} */
    this.rooms = new Map();
    /** @type {Map<any, { roomId: string, role: 'sender' | 'receiver' }>} */
    this.socketMeta = new Map();
  }

  /**
   * Registers a client socket into a room.
   * @param {any} ws WebSocket instance
   * @param {object} payload { role: 'sender'|'receiver', roomId: string, token: string }
   * @returns {{ success: boolean, code?: string, message?: string }}
   */
  register(ws, { role, roomId, token }) {
    if (!roomId || typeof roomId !== 'string' || roomId.trim().length === 0) {
      return { success: false, code: 'INVALID_ROOM_ID', message: 'Room ID must be a non-empty string' };
    }
    const cleanRoomId = roomId.trim();

    if (!role || (role !== 'sender' && role !== 'receiver')) {
      return { success: false, code: 'INVALID_ROLE', message: 'Role must be either "sender" or "receiver"' };
    }

    if (!isValidTokenFormat(token)) {
      return { success: false, code: 'INVALID_TOKEN_FORMAT', message: 'Session token must be 6-128 alphanumeric characters' };
    }

    if (role === 'sender') {
      // If room already exists with a different active sender, reject or replace if same token
      const existing = this.rooms.get(cleanRoomId);
      if (existing && existing.sender && existing.sender.readyState === 1) { // 1 = OPEN
        if (!secureCompare(existing.token, token)) {
          return { success: false, code: 'AUTH_FAILED', message: 'Room already active with different token' };
        }
        // Gracefully close previous sender
        try {
          existing.sender.send(JSON.stringify({ type: 'error', code: 'SUPERSEDED', message: 'Replaced by newer sender session' }));
          existing.sender.close(4409, 'Replaced by new sender');
        } catch (_) {}
      }

      const room = existing || {
        token,
        sender: null,
        receivers: new Set(),
        createdAt: Date.now()
      };
      room.token = token;
      room.sender = ws;
      this.rooms.set(cleanRoomId, room);
      this.socketMeta.set(ws, { roomId: cleanRoomId, role: 'sender' });

      // Notify any connected receivers that sender is here
      for (const rx of room.receivers) {
        if (rx.readyState === 1) {
          rx.send(JSON.stringify({ type: 'peer_status', peerJoined: true, peerRole: 'sender' }));
        }
      }

      return { success: true };
    }

    // Role is receiver
    const room = this.rooms.get(cleanRoomId);
    if (!room) {
      return { success: false, code: 'ROOM_NOT_FOUND', message: 'Specified room does not exist. Ensure sender is running.' };
    }

    if (!secureCompare(room.token, token)) {
      return { success: false, code: 'AUTH_FAILED', message: 'Invalid session token for this room' };
    }

    room.receivers.add(ws);
    this.socketMeta.set(ws, { roomId: cleanRoomId, role: 'receiver' });

    // Notify sender that a receiver joined
    if (room.sender && room.sender.readyState === 1) {
      room.sender.send(JSON.stringify({ type: 'peer_status', peerJoined: true, peerRole: 'receiver' }));
    }

    return {
      success: true,
      senderActive: !!(room.sender && room.sender.readyState === 1)
    };
  }

  /**
   * Route a signaling message between peers in the same room.
   * @param {any} ws Sender socket
   * @param {object} message
   */
  route(ws, message) {
    const meta = this.socketMeta.get(ws);
    if (!meta) {
      try {
        ws.send(JSON.stringify({ type: 'error', code: 'UNREGISTERED', message: 'Must register first' }));
      } catch (_) {}
      return;
    }

    const room = this.rooms.get(meta.roomId);
    if (!room) return;

    const payload = JSON.stringify(message);

    if (meta.role === 'sender') {
      // Forward to all receivers in the room
      for (const rx of room.receivers) {
        if (rx.readyState === 1) {
          rx.send(payload);
        }
      }
    } else {
      // Forward to the sender
      if (room.sender && room.sender.readyState === 1) {
        room.sender.send(payload);
      }
    }
  }

  /**
   * Cleans up on socket disconnect.
   * @param {any} ws
   */
  handleDisconnect(ws) {
    const meta = this.socketMeta.get(ws);
    if (!meta) return;

    this.socketMeta.delete(ws);
    const room = this.rooms.get(meta.roomId);
    if (!room) return;

    if (meta.role === 'sender') {
      // Sender left: notify all receivers
      for (const rx of room.receivers) {
        if (rx.readyState === 1) {
          rx.send(JSON.stringify({ type: 'peer_status', peerJoined: false, peerRole: 'sender' }));
        }
      }
      this.rooms.delete(meta.roomId);
    } else {
      // Receiver left
      room.receivers.delete(ws);
      if (room.sender && room.sender.readyState === 1) {
        room.sender.send(JSON.stringify({ type: 'peer_status', peerJoined: false, peerRole: 'receiver' }));
      }
      // If room has no sender and no receivers, clean it up
      if (!room.sender || room.sender.readyState !== 1) {
        if (room.receivers.size === 0) {
          this.rooms.delete(meta.roomId);
        }
      }
    }
  }

  /**
   * Total active rooms count
   */
  get activeRoomsCount() {
    return this.rooms.size;
  }
}

module.exports = { RoomManager };
