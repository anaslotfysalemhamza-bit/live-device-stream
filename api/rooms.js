// In-memory room storage (will reset on cold starts)
const rooms = new Map();

function cleanupOldRooms() {
  const now = Date.now();
  for (const [roomId, room] of rooms.entries()) {
    if (now - room.lastActivity > 3600000) { // 1 hour
      rooms.delete(roomId);
    }
  }
}

export default function handler(req, res) {
  // Enable CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  cleanupOldRooms();

  const { method } = req;
  const { roomId, token, role } = req.query;

  if (method === 'GET') {
    // Get room info
    if (!roomId) {
      return res.status(400).json({ error: 'roomId required' });
    }

    const room = rooms.get(roomId);
    if (!room) {
      return res.status(404).json({ error: 'ROOM_NOT_FOUND' });
    }

    return res.status(200).json({
      roomId: room.roomId,
      senderActive: !!room.sender,
      receiverActive: !!room.receiver,
      lastActivity: room.lastActivity
    });
  }

  if (method === 'POST') {
    // Register/create room
    const { roomId, token, role } = req.body;

    if (!roomId || !token || !role) {
      return res.status(400).json({ error: 'Missing required fields' });
    }

    let room = rooms.get(roomId);

    // Create room if doesn't exist (sender creates it)
    if (!room) {
      if (role !== 'sender') {
        return res.status(404).json({ error: 'ROOM_NOT_FOUND' });
      }

      room = {
        roomId,
        token,
        sender: null,
        receiver: null,
        messages: [],
        lastActivity: Date.now()
      };
      rooms.set(roomId, room);
    }

    // Verify token
    if (room.token !== token) {
      return res.status(403).json({ error: 'INVALID_TOKEN' });
    }

    // Register client
    const clientId = `${role}-${Date.now()}`;
    if (role === 'sender') {
      room.sender = { id: clientId, lastSeen: Date.now() };
    } else if (role === 'receiver') {
      room.receiver = { id: clientId, lastSeen: Date.now() };
    }

    room.lastActivity = Date.now();

    return res.status(200).json({
      success: true,
      clientId,
      roomId: room.roomId,
      senderActive: !!room.sender,
      receiverActive: !!room.receiver
    });
  }

  if (method === 'DELETE') {
    // Unregister/leave room
    if (!roomId) {
      return res.status(400).json({ error: 'roomId required' });
    }

    const room = rooms.get(roomId);
    if (room) {
      if (role === 'sender') {
        room.sender = null;
      } else if (role === 'receiver') {
        room.receiver = null;
      }

      // Delete room if empty
      if (!room.sender && !room.receiver) {
        rooms.delete(roomId);
      }
    }

    return res.status(200).json({ success: true });
  }

  return res.status(405).json({ error: 'Method not allowed' });
}
