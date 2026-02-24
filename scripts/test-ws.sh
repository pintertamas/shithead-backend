#!/usr/bin/env bash
# Manual WebSocket connection test for the Shithead game.
#
# Prerequisites: npm install -g wscat
#
# Usage:
#   ./scripts/test-ws.sh
#
# You'll be prompted for:
#   1. WebSocket URL  (wss://xxx.execute-api.eu-central-1.amazonaws.com)
#   2. Game session ID (the 6-char code, e.g. A1B2C3)
#   3. Cognito ID token (copy from browser DevTools:
#        Application > Local Storage > your-site > shithead_auth > idToken)

set -euo pipefail

if ! command -v wscat &>/dev/null; then
  echo "wscat not found. Install it with:  npm install -g wscat"
  exit 1
fi

read -rp "WebSocket URL (wss://...): " WS_URL
read -rp "Game session ID: " SESSION_ID
read -rp "Cognito ID token: " TOKEN

# Strip trailing slash from URL if present
WS_URL="${WS_URL%/}"

FULL_URL="${WS_URL}?game_session_id=${SESSION_ID}"

echo ""
echo "Connecting to: ${FULL_URL}"
echo "Token (first 20 chars): ${TOKEN:0:20}..."
echo ""

# -s sets the Sec-WebSocket-Protocol header, matching the frontend's
#   new WebSocket(url, ["Bearer <token>"])
wscat -c "${FULL_URL}" -s "Bearer ${TOKEN}"
