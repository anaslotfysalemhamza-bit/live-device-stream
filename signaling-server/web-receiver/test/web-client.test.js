/**
 * Automated Test Suite for Web Receiver Client Logic
 */

const assert = require('assert');
const http = require('http');
const { WebSocket, WebSocketServer } = require('ws');
const { BrowserSignalingClient } = require('../js/signaling-client');

// Inject global WebSocket for Node.js environment
global.WebSocket = WebSocket;

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

function createMockSignalingServer() {
  return new Promise((resolve) => {
    const server = http.createServer();
    const wss = new WebSocketServer({ server });

    wss.on('connection', (ws) => {
      ws.on('message', (raw) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'register') {
          if (msg.token === 'VALID_TEST_TOKEN') {
            ws.send(JSON.stringify({
              type: 'registered',
              role: msg.role,
              roomId: msg.roomId,
              senderActive: true
            }));
            // Send mock stream_info and stream_state
            ws.send(JSON.stringify({
              type: 'stream_info',
              streamInfo: {
                screenTrackId: 'live_screen_track',
                cameraTrackId: 'live_camera_track',
                audioTrackId: 'live_audio_track'
              }
            }));
            ws.send(JSON.stringify({
              type: 'stream_state',
              camera: true,
              screen: false,
              mic: true
            }));
          } else {
            ws.send(JSON.stringify({
              type: 'error',
              code: 'AUTH_FAILED',
              message: 'Invalid session token'
            }));
            ws.close(4403, 'Auth Failed');
          }
        } else if (msg.type === 'answer') {
          ws.send(JSON.stringify({ type: 'ack_answer', sdp: msg.sdp }));
        } else if (msg.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong' }));
        }
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

async function main() {
  console.log('\n--- Running Automated Tests: Web Receiver Signaling Client ---');
  const mockServer = await createMockSignalingServer();

  try {
    await runTest('BrowserSignalingClient connects and authenticates successfully', async () => {
      const client = new BrowserSignalingClient();
      let state = 'INIT';
      let registered = false;

      await new Promise((resolve, reject) => {
        client.onStateChange = (s) => {
          state = s;
        };
        client.onRegistered = (msg) => {
          registered = true;
          assert.strictEqual(msg.role, 'receiver');
          assert.strictEqual(msg.roomId, 'ROOM-TEST');
          assert.strictEqual(msg.senderActive, true);
          resolve();
        };
        client.onError = (err) => {
          reject(new Error(`Unexpected error: ${err.message}`));
        };

        client.connect(mockServer.url, 'ROOM-TEST', 'VALID_TEST_TOKEN');
      });

      assert.strictEqual(state, 'CONNECTED');
      assert.strictEqual(registered, true);
      client.disconnect();
      assert.strictEqual(client.state, 'DISCONNECTED');
    });

    await runTest('BrowserSignalingClient dispatches stream_info and stream_state', async () => {
      const client = new BrowserSignalingClient();
      let receivedStreamInfo = null;
      let receivedStreamState = null;

      await new Promise((resolve, reject) => {
        client.onStreamInfo = (info) => {
          receivedStreamInfo = info;
          if (receivedStreamState) resolve();
        };
        client.onStreamState = (state) => {
          receivedStreamState = state;
          if (receivedStreamInfo) resolve();
        };
        client.onError = (err) => reject(new Error(err.message));
        client.connect(mockServer.url, 'ROOM-TEST', 'VALID_TEST_TOKEN');
      });

      assert.ok(receivedStreamInfo, 'stream_info should be received');
      assert.strictEqual(receivedStreamInfo.screenTrackId, 'live_screen_track');
      assert.strictEqual(receivedStreamInfo.cameraTrackId, 'live_camera_track');

      assert.ok(receivedStreamState, 'stream_state should be received');
      assert.strictEqual(receivedStreamState.camera, true);
      assert.strictEqual(receivedStreamState.screen, false);
      assert.strictEqual(receivedStreamState.mic, true);

      client.disconnect();
    });

    await runTest('BrowserSignalingClient reports AUTH_FAILED on incorrect token', async () => {
      const client = new BrowserSignalingClient();
      let capturedError = null;

      await new Promise((resolve) => {
        client.onError = (err) => {
          capturedError = err;
        };
        client.onStateChange = (s) => {
          if (s === 'DISCONNECTED' && capturedError) {
            resolve();
          }
        };
        client.connect(mockServer.url, 'ROOM-TEST', 'BAD_TOKEN_123');
      });

      assert.ok(capturedError, 'Error should be reported');
      assert.strictEqual(capturedError.code, 'AUTH_FAILED');
      assert.strictEqual(client.state, 'DISCONNECTED');
      client.disconnect();
    });

    await runTest('BrowserSignalingClient handles graceful manual disconnect', async () => {
      const client = new BrowserSignalingClient();
      await new Promise((resolve) => {
        client.onRegistered = () => resolve();
        client.connect(mockServer.url, 'ROOM-TEST', 'VALID_TEST_TOKEN');
      });

      assert.strictEqual(client.state, 'CONNECTED');
      client.disconnect();
      assert.strictEqual(client.state, 'DISCONNECTED');
      assert.strictEqual(client.ws, null);
    });

  } finally {
    await mockServer.close();
  }

  console.log('\nAll web receiver tests passed successfully!\n');
}

main().catch((err) => {
  console.error('\nWeb client test suite failure:', err);
  process.exit(1);
});
