/**
 * Signaling Server Entrypoint
 * Provides HTTP/HTTPS server for the Web Receiver dashboard and WebSocket server for signaling.
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { WebSocketServer } = require('ws');
const { RoomManager } = require('./room');

function getLocalIpAddress() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

const PORT = parseInt(process.env.PORT || '8443', 10);
const HOST = process.env.HOST || '0.0.0.0';
const SSL_KEY_PATH = process.env.SSL_KEY || path.join(__dirname, '../certs/key.pem');
const SSL_CERT_PATH = process.env.SSL_CERT || path.join(__dirname, '../certs/cert.pem');
const WEB_RECEIVER_DIR = path.resolve(__dirname, '../../web-receiver');

const roomManager = new RoomManager();

// Determine if SSL certificates are available
let isHttps = false;
let serverOptions = {};

if (fs.existsSync(SSL_KEY_PATH) && fs.existsSync(SSL_CERT_PATH)) {
  try {
    serverOptions = {
      key: fs.readFileSync(SSL_KEY_PATH),
      cert: fs.readFileSync(SSL_CERT_PATH)
    };
    isHttps = true;
  } catch (err) {
    console.warn(`[Server] Warning: Failed to load SSL certs, falling back to HTTP. Error: ${err.message}`);
    isHttps = false;
  }
}

// MIME types for static asset serving
const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png'
};

/**
 * Static file server for the Web Receiver dashboard
 */
function handleHttpRequest(req, res) {
  // CORS & Security headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Health check endpoint
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ok',
      protocol: isHttps ? 'https' : 'http',
      wsProtocol: isHttps ? 'wss' : 'ws',
      activeRooms: roomManager.activeRoomsCount,
      uptime: process.uptime()
    }));
    return;
  }

  // Static files for Web Receiver
  let reqPath = req.url.split('?')[0];
  if (reqPath === '/' || reqPath === '') {
    reqPath = '/index.html';
  }

  const safePath = path.normalize(reqPath).replace(/^(\.\.[/\\])+/, '');
  const filePath = path.join(WEB_RECEIVER_DIR, safePath);

  if (!filePath.startsWith(WEB_RECEIVER_DIR)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Forbidden');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    res.writeHead(200, { 'Content-Type': contentType });
    fs.createReadStream(filePath).pipe(res);
  });
}

// Create HTTP or HTTPS server
const server = isHttps
  ? https.createServer(serverOptions, handleHttpRequest)
  : http.createServer(handleHttpRequest);

// Attach WebSocket server
const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  const remoteIp = req.socket.remoteAddress;
  ws.isAlive = true;

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch (e) {
      ws.send(JSON.stringify({ type: 'error', code: 'MALFORMED_JSON', message: 'Message must be valid JSON' }));
      return;
    }

    if (!msg || typeof msg !== 'object' || !msg.type) {
      ws.send(JSON.stringify({ type: 'error', code: 'MISSING_TYPE', message: 'Message must contain a "type" field' }));
      return;
    }

    switch (msg.type) {
      case 'register': {
        const result = roomManager.register(ws, msg);
        if (result.success) {
          ws.send(JSON.stringify({
            type: 'registered',
            role: msg.role,
            roomId: msg.roomId,
            senderActive: result.senderActive || (msg.role === 'sender'),
            lanIp: getLocalIpAddress()
          }));
        } else {
          ws.send(JSON.stringify({
            type: 'error',
            code: result.code,
            message: result.message
          }));
          ws.close(4403, result.message);
        }
        break;
      }

      case 'ping': {
        ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
        break;
      }

      case 'offer':
      case 'answer':
      case 'ice_candidate':
      case 'stream_state':
      case 'stream_info': {
        roomManager.route(ws, msg);
        break;
      }

      default: {
        ws.send(JSON.stringify({ type: 'error', code: 'UNKNOWN_TYPE', message: `Unknown message type: ${msg.type}` }));
        break;
      }
    }
  });

  ws.on('close', () => {
    roomManager.handleDisconnect(ws);
  });

  ws.on('error', (err) => {
    console.error(`[WebSocket] Client error (${remoteIp}):`, err.message);
    roomManager.handleDisconnect(ws);
  });
});

// Periodic heartbeat interval to sweep dead connections
const heartbeatInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      roomManager.handleDisconnect(ws);
      return ws.terminate();
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on('close', () => {
  clearInterval(heartbeatInterval);
});

function startServer(port = PORT, host = HOST) {
  return new Promise((resolve) => {
    server.listen(port, host, () => {
      const scheme = isHttps ? 'https' : 'http';
      const wsScheme = isHttps ? 'wss' : 'ws';
      console.log(`[Signaling Server] Running on ${scheme}://${host}:${port}`);
      console.log(`[Signaling Server] WebSocket endpoint: ${wsScheme}://${host}:${port}`);
      console.log(`[Signaling Server] Serving Web Receiver from: ${WEB_RECEIVER_DIR}`);
      resolve({ server, wss, roomManager, port, host, isHttps });
    });
  });
}

// If run directly from CLI
if (require.main === module) {
  startServer().catch((err) => {
    console.error('[Signaling Server] Startup failed:', err);
    process.exit(1);
  });
}

module.exports = { startServer, server, wss, roomManager };
