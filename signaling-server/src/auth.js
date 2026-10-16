/**
 * Authentication and Token Utilities
 * Provides constant-time token comparison and token validation.
 */

const crypto = require('crypto');

/**
 * Validates whether a token conforms to requirements:
 * Must be a string between 6 and 128 characters, containing alphanumeric or dashes/underscores.
 * @param {any} token
 * @returns {boolean}
 */
function isValidTokenFormat(token) {
  if (typeof token !== 'string') return false;
  const trimmed = token.trim();
  if (trimmed.length < 6 || trimmed.length > 128) return false;
  return /^[a-zA-Z0-9_-]+$/.test(trimmed);
}

/**
 * Constant-time string comparison to avoid timing attacks.
 * @param {string} a
 * @param {string} b
 * @returns {boolean}
 */
function secureCompare(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/**
 * Generates a cryptographically-secure random token (default 16 bytes = 32 hex chars)
 * @param {number} bytes
 * @returns {string}
 */
function generateSecureToken(bytes = 16) {
  return crypto.randomBytes(bytes).toString('hex');
}

/**
 * Generates an 8-character human-readable pairing code (e.g. "X7K9-2P4M")
 * @returns {string}
 */
function generatePairingCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O, 1/I to avoid ambiguity
  let code = '';
  const randomBytes = crypto.randomBytes(8);
  for (let i = 0; i < 8; i++) {
    code += chars[randomBytes[i] % chars.length];
    if (i === 3) code += '-';
  }
  return code;
}

module.exports = {
  isValidTokenFormat,
  secureCompare,
  generateSecureToken,
  generatePairingCode
};
