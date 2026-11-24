import crypto from 'node:crypto';

const MAX_RANDOM_BYTES = 65_536;

export const pluginCrypto = {
  md5(buf) {
    return crypto.createHash('md5').update(buf).digest('hex');
  },
  sha1(buf) {
    return crypto.createHash('sha1').update(buf).digest('hex');
  },
  sha256(buf) {
    return crypto.createHash('sha256').update(buf).digest('hex');
  },
  hmac(algo, keyBuf, dataBuf) {
    const norm = String(algo).toLowerCase().replace(/-/g, '');
    if (!['md5', 'sha1', 'sha256', 'sha512'].includes(norm)) {
      throw new Error(`Unsupported HMAC algorithm: ${algo}`);
    }
    return crypto.createHmac(norm, keyBuf).update(dataBuf).digest('hex');
  },
  aesEncrypt(mode, keyBuf, ivBuf, dataBuf, aadBuf = null) {
    const bits = keyBuf.length * 8;
    if (![128, 192, 256].includes(bits)) {
      throw new Error(`AES key must be 16, 24, or 32 bytes (got ${keyBuf.length})`);
    }
    const m = String(mode).toUpperCase();
    if (m === 'CBC' || m === 'AES-CBC') {
      if (ivBuf.length !== 16) throw new Error(`AES-CBC IV must be 16 bytes (got ${ivBuf.length})`);
      const cipher = crypto.createCipheriv(`aes-${bits}-cbc`, keyBuf, ivBuf);
      return Buffer.concat([cipher.update(dataBuf), cipher.final()]);
    }
    if (m === 'GCM' || m === 'AES-GCM') {
      if (ivBuf.length !== 12 && ivBuf.length !== 16) {
        throw new Error(`AES-GCM IV must be 12 or 16 bytes (got ${ivBuf.length})`);
      }
      const cipher = crypto.createCipheriv(`aes-${bits}-gcm`, keyBuf, ivBuf);
      if (aadBuf && aadBuf.length > 0) cipher.setAAD(aadBuf);
      const enc = Buffer.concat([cipher.update(dataBuf), cipher.final()]);
      const tag = cipher.getAuthTag();
      return Buffer.concat([enc, tag]);
    }
    throw new Error(`Unsupported AES mode: ${mode}`);
  },
  aesDecrypt(mode, keyBuf, ivBuf, dataBuf, aadBuf = null) {
    const bits = keyBuf.length * 8;
    if (![128, 192, 256].includes(bits)) {
      throw new Error(`AES key must be 16, 24, or 32 bytes (got ${keyBuf.length})`);
    }
    const m = String(mode).toUpperCase();
    if (m === 'CBC' || m === 'AES-CBC') {
      if (ivBuf.length !== 16) throw new Error(`AES-CBC IV must be 16 bytes (got ${ivBuf.length})`);
      const decipher = crypto.createDecipheriv(`aes-${bits}-cbc`, keyBuf, ivBuf);
      return Buffer.concat([decipher.update(dataBuf), decipher.final()]);
    }
    if (m === 'GCM' || m === 'AES-GCM') {
      if (ivBuf.length !== 12 && ivBuf.length !== 16) {
        throw new Error(`AES-GCM IV must be 12 or 16 bytes (got ${ivBuf.length})`);
      }
      if (dataBuf.length < 16) throw new Error('Invalid AES-GCM ciphertext');
      const ct = dataBuf.subarray(0, dataBuf.length - 16);
      const tag = dataBuf.subarray(dataBuf.length - 16);
      const decipher = crypto.createDecipheriv(`aes-${bits}-gcm`, keyBuf, ivBuf);
      if (aadBuf && aadBuf.length > 0) decipher.setAAD(aadBuf);
      decipher.setAuthTag(tag);
      return Buffer.concat([decipher.update(ct), decipher.final()]);
    }
    throw new Error(`Unsupported AES mode: ${mode}`);
  },
  rsaEncrypt(publicKeyPem, dataBuf, padding = 'PKCS1') {
    const pem = publicKeyPem.includes('BEGIN PUBLIC KEY')
      ? publicKeyPem
      : `-----BEGIN PUBLIC KEY-----\n${publicKeyPem.trim()}\n-----END PUBLIC KEY-----`;
    const p = String(padding || 'PKCS1').toUpperCase();
    let opts;
    if (p === 'PKCS1' || p === 'RSA/ECB/PKCS1PADDING') {
      opts = { key: pem, padding: crypto.constants.RSA_PKCS1_PADDING };
    } else if (p === 'OAEP' || p === 'OAEP-SHA256') {
      opts = { key: pem, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' };
    } else if (p === 'OAEP-SHA1') {
      opts = { key: pem, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha1' };
    } else {
      throw new Error(`Unsupported RSA padding: ${padding}`);
    }
    return crypto.publicEncrypt(opts, dataBuf);
  },
  randomBytes(n) {
    const len = Number(n);
    if (!Number.isInteger(len) || len < 0 || len > MAX_RANDOM_BYTES) {
      throw new Error(`randomBytes length must be in 0..${MAX_RANDOM_BYTES}`);
    }
    return crypto.randomBytes(len);
  },
  base64Encode(buf, urlSafe = false) {
    if (urlSafe) {
      return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }
    return buf.toString('base64');
  },
  base64Decode(str) {
    const norm = String(str).trim().replace(/-/g, '+').replace(/_/g, '/');
    return Buffer.from(norm, 'base64');
  },
  hexEncode(buf) {
    return buf.toString('hex');
  },
  hexDecode(hex) {
    const clean = String(hex).trim();
    if (clean.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(clean)) {
      throw new Error('Invalid hex string');
    }
    return Buffer.from(clean, 'hex');
  },
};
