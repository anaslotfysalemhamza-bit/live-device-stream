#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Generating self-signed SSL/TLS certificates for local HTTPS/WSS testing..."

openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout key.pem \
  -out cert.pem \
  -days 365 \
  -subj "/C=US/ST=Dev/L=Local/O=LiveDeviceStream/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2"

chmod 600 key.pem
chmod 644 cert.pem

echo "Generated:"
echo "  Key:  $SCRIPT_DIR/key.pem"
echo "  Cert: $SCRIPT_DIR/cert.pem"
