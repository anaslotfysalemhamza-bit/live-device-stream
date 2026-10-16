// In-memory message storage (shared with rooms)
const rooms = new Map();

export default function handler(req, res) {
  // Enable CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const { roomId, token, since } = req.query;

  if (!roomId || !token) {
    return res.status(400).json({ error: 'roomId and token required' });
  }

  const room = rooms.get(roomId);
  if (!room) {
    return res.status(404).json({ error: 'ROOM_NOT_FOUND' });
  }

  if (room.token !== token) {
    return res.status(403).json({ error: 'INVALID_TOKEN' });
  }

  if (req.method === 'GET') {
    // Poll for new messages
    const sinceTime = since ? parseInt(since) : 0;
    const newMessages = (room.messages || []).filter(m => m.timestamp > sinceTime);

    return res.status(200).json({
      messages: newMessages,
      timestamp: Date.now()
    });
  }

  if (req.method === 'POST') {
    // Send message
    const message = req.body;

    if (!message || !message.type) {
      return res.status(400).json({ error: 'Invalid message' });
    }

    const timestampedMessage = {
      ...message,
      timestamp: Date.now()
    };

    if (!room.messages) {
      room.messages = [];
    }

    room.messages.push(timestampedMessage);

    // Keep only last 50 messages
    if (room.messages.length > 50) {
      room.messages = room.messages.slice(-50);
    }

    room.lastActivity = Date.now();

    return res.status(200).json({
      success: true,
      timestamp: timestampedMessage.timestamp
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
}
