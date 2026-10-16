#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================================="
echo "    Live Device Stream — Local Session Launcher"
echo "=========================================================="

# 1. Detect local IP
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")
PORT=${PORT:-8443}

echo "Local IP: $LOCAL_IP"

# 2. Check for ADB device
if which adb >/dev/null 2>&1; then
  DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | awk '{print $1}')
  if [ -n "$DEVICES" ]; then
    echo "Found connected Android device(s):"
    echo "$DEVICES"
    echo "Setting up ADB reverse port forwarding (tcp:$PORT -> tcp:$PORT)..."
    adb reverse "tcp:$PORT" "tcp:$PORT" || true
    echo "✓ ADB reverse configured. On Android you can connect to: ws://127.0.0.1:$PORT"
  else
    echo "No ADB device detected via USB. Make sure your Android 12 phone is on the same Wi-Fi:"
    echo "  Android Signaling URL: ws://$LOCAL_IP:$PORT"
  fi
fi

# 3. Verify server dependencies
cd "$ROOT_DIR/signaling-server"
if [ ! -d "node_modules" ]; then
  echo "Installing signaling server dependencies..."
  npm install
fi

echo ""
echo "----------------------------------------------------------"
echo "  1. Web Receiver Dashboard : http://localhost:$PORT"
echo "  2. Android Signaling URL   : ws://127.0.0.1:$PORT (USB) or ws://$LOCAL_IP:$PORT (Wi-Fi)"
echo "----------------------------------------------------------"
echo "Starting Signaling Server..."
exec node src/server.js
