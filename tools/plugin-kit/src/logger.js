const REDACTED = '[REDACTED]';
const TRUNCATION_SUFFIX = '...[truncated]';
const AUTH_HEADER_RE = /\b(authorization\s*[:=]\s*(?:bearer|basic|mediabrowser|emby|token)\s+)([^\r\n;,"']+)/gi;
const COOKIE_HEADER_RE = /\b((?:set-)?cookie\s*[:=]\s*)([^\r\n]+)/gi;
const JSON_SECRET_RE = /("?(?:password|passwd|pwd|secret|token|access_?token|refresh_?token|api_?key|x-emby-token|x-mediabrowser-token|sessdata|bili_jct|pin)"?\s*[:=]\s*["']?)([^"',\s&;}\]]+)/gi;
const URL_USERINFO_RE = /(https?:\/\/)([^/\s:@]+:[^/\s@]+)(@)/g;

export const MAX_LOG_ENTRIES = 200;
export const MAX_LOG_MESSAGE_BYTES = 4 * 1024;
export const MAX_LOG_BUFFER_BYTES = 256 * 1024;

export function truncateUtf8(input, maxBytes = MAX_LOG_MESSAGE_BYTES) {
  const s = String(input ?? '');
  if (maxBytes <= 0) return '';
  if (s.length <= Math.floor(maxBytes / 4)) return s;
  const suffixBytes = TRUNCATION_SUFFIX.length;
  const limit = Math.max(0, maxBytes - suffixBytes);
  let bytes = 0;
  let cutIdx = -1;
  for (let i = 0; i < s.length; ) {
    const cp = s.codePointAt(i);
    const step = cp > 0xFFFF ? 2 : 1;
    const cpBytes = cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
    if (bytes + cpBytes > maxBytes) {
      return cutIdx >= 0 ? s.slice(0, cutIdx) + TRUNCATION_SUFFIX : s.slice(0, i);
    }
    bytes += cpBytes;
    i += step;
    if (bytes <= limit) cutIdx = i;
  }
  return s;
}

export function redactSecrets(input, knownSecrets = []) {
  let out = String(input ?? '');
  for (const secret of knownSecrets) {
    if (typeof secret === 'string' && secret.length >= 3) {
      out = out.split(secret).join(REDACTED);
    }
  }
  out = out.replace(URL_USERINFO_RE, `$1${REDACTED}$3`);
  out = out.replace(AUTH_HEADER_RE, `$1${REDACTED}`);
  out = out.replace(COOKIE_HEADER_RE, `$1${REDACTED}`);
  out = out.replace(JSON_SECRET_RE, `$1${REDACTED}`);
  return out;
}

export class PluginLogger {
  constructor(pluginId, instanceId, knownSecrets = [], sink = null, opts = {}) {
    this.pluginId = pluginId;
    this.instanceId = instanceId;
    this.knownSecrets = knownSecrets;
    this.maxEntries = opts.maxEntries ?? MAX_LOG_ENTRIES;
    this.maxMessageBytes = opts.maxMessageBytes ?? MAX_LOG_MESSAGE_BYTES;
    this.maxBufferBytes = opts.maxBufferBytes ?? MAX_LOG_BUFFER_BYTES;
    this.entries = [];
    this.totalBufferedBytes = 0;
    this.sink = sink || ((level, line) => {
      if (level === 'ERROR' || level === 'WARN') console.error(line);
      else console.log(line);
    });
  }

  log(level, msg) {
    const truncated = truncateUtf8(msg, this.maxMessageBytes);
    const scrubbed = truncateUtf8(redactSecrets(truncated, this.knownSecrets), this.maxMessageBytes);
    const msgBytes = Buffer.byteLength(scrubbed, 'utf8');
    const line = `depth3d: plugin ${this.pluginId} [${this.instanceId}] ${level}: ${scrubbed}`;
    while (this.entries.length >= this.maxEntries && this.entries.length > 0) {
      const removed = this.entries.shift();
      this.totalBufferedBytes -= removed.byteSize;
    }
    while (this.entries.length > 0 && this.totalBufferedBytes + msgBytes > this.maxBufferBytes) {
      const removed = this.entries.shift();
      this.totalBufferedBytes -= removed.byteSize;
    }
    this.entries.push({ level, message: scrubbed, line, byteSize: msgBytes });
    this.totalBufferedBytes += msgBytes;
    this.sink(level, line);
  }
}
