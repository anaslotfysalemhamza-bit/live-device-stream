// Health check endpoint
export default function handler(req, res) {
  res.status(200).json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    message: 'Live Device Stream Signaling Server is running'
  });
}
