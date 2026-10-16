# Live Device Stream - Signaling Server

WebSocket-based signaling server for Android screen streaming via WebRTC.

## Deploy to Render

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy)

### Manual Deployment Steps:

1. **Push to GitHub** (or any Git provider)
2. **Create New Web Service** on Render.com
3. **Connect your repository**
4. **Configure:**
   - Build Command: `npm install`
   - Start Command: `npm start`
   - Environment Variables:
     - `PORT`: 10000 (Render's default)
     - `HOST`: 0.0.0.0
     - `NODE_ENV`: production

## After Deployment

Your signaling server will be available at:
- HTTP: `http://your-app-name.onrender.com`
- WebSocket: `ws://your-app-name.onrender.com`

Use this URL in both:
1. Android app's server URL field
2. Web receiver (it will use the same origin automatically)

## Local Development

```bash
npm install
npm start
```

Server runs on `http://localhost:8443` by default.
