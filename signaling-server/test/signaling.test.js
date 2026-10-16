/**
 * Automated Test Suite for Signaling Server
 */

const assert = require('assert');
const http = require('http');
const { WebSocket, WebSocketServer } = require('ws');
const { RoomManager } = require('../src/room');
const { isValidTokenFormat, secureCompare, generateSecureToken } = require('../src/auth');

function runTest(name, fn) {
  return Promise.resolve()
    .then(() => fn())
    .then(() => {
      console.log(`  ✓ ${name}`);
    })
    .catch((err) => {
      console.error(`  ✗ ${name}`);
      throw err;
    });
}

function createEphemeralServer() {
  return new Promise((resolve) => {
    const roomManager = new RoomManager();
    const server = http.createServer();
    const wss = new WebSocketServer({ server });

    wss.on('connection', (ws) => {
      ws.isAlive = true;
      ws.on('message', (data) => {
        let msg;
        try {
          msg = JSON.parse(data.toString());
        } catch (_) {
          ws.send(JSON.stringify({ type: 'error', code: 'MALFORMED_JSON' }));
          return;
        }

        if (msg.type === 'register') {
          const res = roomManager.register(ws, msg);
          if (res.success) {
            ws.send(JSON.stringify({ type: 'registered', role: msg.role, roomId: msg.roomId }));
          } else {
            ws.send(JSON.stringify({ type: 'error', code: res.code, message: res.message }));
            ws.close(4403, res.message);
          }
        } else if (msg.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
        } else {
          roomManager.route(ws, msg);
        }
      });

      ws.on('close', () => {
        roomManager.handleDisconnect(ws);
      });
    });

    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      resolve({
        url: `ws://127.0.0.1:${port}`,
        close: () => new Promise((done) => {
          wss.clients.forEach((c) => c.terminate());
          wss.close(() => {
            server.close(done);
          });
        })
      });
    });
  });
}

function createMessageCollector(ws) {
  const queue = [];
  const waiters = [];

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());
      if (waiters.length > 0) {
        for (let i = 0; i < waiters.length; i++) {
          const { filterFn, resolve, timer } = waiters[i];
          if (!filterFn || filterFn(msg)) {
            clearTimeout(timer);
            waiters.splice(i, 1);
            resolve(msg);
            return;
          }
        }
      }
      queue.push(msg);
    } catch (_) {}
  });

  return {
    next(filterFn, timeoutMs = 3000) {
      // Check existing queue first
      const idx = queue.findIndex((m) => !filterFn || filterFn(m));
      if (idx !== -1) {
        const [msg] = queue.splice(idx, 1);
        return Promise.resolve(msg);
      }

      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          const wIdx = waiters.findIndex((w) => w.timer === timer);
          if (wIdx !== -1) waiters.splice(wIdx, 1);
          reject(new Error(`Timeout waiting for message (${timeoutMs}ms)`));
        }, timeoutMs);

        waiters.push({ filterFn, resolve, timer });
      });
    }
  };
}

async function main() {
  console.log('\n--- Running Unit Tests: Auth Utilities ---');

  await runTest('isValidTokenFormat validates properly', () => {
    assert.strictEqual(isValidTokenFormat('short'), false, 'too short');
    assert.strictEqual(isValidTokenFormat('validToken1234'), true, 'standard alphanumeric');
    assert.strictEqual(isValidTokenFormat('token-with_dashes-123'), true, 'dashes & underscores');
    assert.strictEqual(isValidTokenFormat('has space 1234'), false, 'no spaces allowed');
    assert.strictEqual(isValidTokenFormat(null), false, 'null rejected');
  });

  await runTest('secureCompare constant-time equality', () => {
    assert.strictEqual(secureCompare('secret12345', 'secret12345'), true);
    assert.strictEqual(secureCompare('secret12345', 'secret12346'), false);
    assert.strictEqual(secureCompare('short', 'longer-string'), false);
  });

  console.log('\n--- Running Integration Tests: WebSocket Signaling & Auth ---');
  const testServer = await createEphemeralServer();

  try {
    await runTest('Sender can register room with secure token', async () => {
      const ws = new WebSocket(testServer.url);
      const collector = createMessageCollector(ws);
      await new Promise((r) => ws.on('open', r));

      const token = generateSecureToken();
      ws.send(JSON.stringify({
        type: 'register',
        role: 'sender',
        roomId: 'ROOM-100',
        token
      }));

      const msg = await collector.next((m) => m.type === 'registered');
      assert.strictEqual(msg.role, 'sender');
      assert.strictEqual(msg.roomId, 'ROOM-100');
      ws.close();
    });

    await runTest('Receiver with matching token successfully pairs with sender', async () => {
      const senderWs = new WebSocket(testServer.url);
      const senderCollector = createMessageCollector(senderWs);
      await new Promise((r) => senderWs.on('open', r));
      const token = 'SECRET_TOKEN_999';

      senderWs.send(JSON.stringify({
        type: 'register',
        role: 'sender',
        roomId: 'ROOM-200',
        token
      }));
      await senderCollector.next((m) => m.type === 'registered');

      const receiverWs = new WebSocket(testServer.url);
      const receiverCollector = createMessageCollector(receiverWs);
      await new Promise((r) => receiverWs.on('open', r));

      receiverWs.send(JSON.stringify({
        type: 'register',
        role: 'receiver',
        roomId: 'ROOM-200',
        token
      }));

      const rxRegistered = await receiverCollector.next((m) => m.type === 'registered');
      assert.strictEqual(rxRegistered.role, 'receiver');

      // Sender should receive peer_status { peerJoined: true, peerRole: 'receiver' }
      const senderNotice = await senderCollector.next((m) => m.type === 'peer_status');
      assert.strictEqual(senderNotice.peerJoined, true);
      assert.strictEqual(senderNotice.peerRole, 'receiver');

      senderWs.close();
      receiverWs.close();
    });

    await runTest('Receiver with wrong token is rejected with AUTH_FAILED', async () => {
      const senderWs = new WebSocket(testServer.url);
      const senderCollector = createMessageCollector(senderWs);
      await new Promise((r) => senderWs.on('open', r));
      const correctToken = 'CORRECT_TOKEN_123';

      senderWs.send(JSON.stringify({
        type: 'register',
        role: 'sender',
        roomId: 'ROOM-300',
        token: correctToken
      }));
      await senderCollector.next((m) => m.type === 'registered');

      const receiverWs = new WebSocket(testServer.url);
      const receiverCollector = createMessageCollector(receiverWs);
      await new Promise((r) => receiverWs.on('open', r));

      receiverWs.send(JSON.stringify({
        type: 'register',
        role: 'receiver',
        roomId: 'ROOM-300',
        token: 'WRONG_TOKEN_999'
      }));

      const errMsg = await receiverCollector.next((m) => m.type === 'error');
      assert.strictEqual(errMsg.code, 'AUTH_FAILED');

      senderWs.close();
      receiverWs.close();
    });

    await runTest('Signals (offer, answer, ICE candidates) route between peers', async () => {
      const senderWs = new WebSocket(testServer.url);
      const receiverWs = new WebSocket(testServer.url);
      const senderCollector = createMessageCollector(senderWs);
      const receiverCollector = createMessageCollector(receiverWs);

      await Promise.all([
        new Promise((r) => senderWs.on('open', r)),
        new Promise((r) => receiverWs.on('open', r))
      ]);

      const token = 'ROUTING_TOKEN_123';
      senderWs.send(JSON.stringify({ type: 'register', role: 'sender', roomId: 'ROOM-400', token }));
      await senderCollector.next((m) => m.type === 'registered');

      receiverWs.send(JSON.stringify({ type: 'register', role: 'receiver', roomId: 'ROOM-400', token }));
      await receiverCollector.next((m) => m.type === 'registered');

      // Sender sends mock offer
      senderWs.send(JSON.stringify({ type: 'offer', sdp: 'mock-offer-sdp-data' }));
      const rxOffer = await receiverCollector.next((m) => m.type === 'offer');
      assert.strictEqual(rxOffer.sdp, 'mock-offer-sdp-data');

      // Receiver sends mock answer
      receiverWs.send(JSON.stringify({ type: 'answer', sdp: 'mock-answer-sdp-data' }));
      const txAnswer = await senderCollector.next((m) => m.type === 'answer');
      assert.strictEqual(txAnswer.sdp, 'mock-answer-sdp-data');

      senderWs.close();
      receiverWs.close();
    });

    await runTest('Ping request receives pong response', async () => {
      const ws = new WebSocket(testServer.url);
      const collector = createMessageCollector(ws);
      await new Promise((r) => ws.on('open', r));
      ws.send(JSON.stringify({ type: 'ping' }));
      const pong = await collector.next((m) => m.type === 'pong');
      assert.ok(pong.timestamp);
      ws.close();
    });

  } finally {
    await testServer.close();
  }

  console.log('\nAll signaling server tests passed successfully!\n');
}

main().catch((err) => {
  console.error('\nTest suite failure:', err);
  process.exit(1);
});
