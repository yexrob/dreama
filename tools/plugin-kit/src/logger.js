const REDACTED = '[REDACTED]';
const AUTH_HEADER_RE = /\b(authorization\s*[:=]\s*(?:bearer|basic|mediabrowser|emby|token)\s+)([^\r\n;,"']+)/gi;
const COOKIE_HEADER_RE = /\b((?:set-)?cookie\s*[:=]\s*)([^\r\n]+)/gi;
const JSON_SECRET_RE = /("?(?:password|passwd|pwd|secret|token|access_?token|refresh_?token|api_?key|x-emby-token|x-mediabrowser-token|sessdata|bili_jct|pin)"?\s*[:=]\s*["']?)([^"',\s&;}\]]+)/gi;
const URL_USERINFO_RE = /(https?:\/\/)([^/\s:@]+:[^/\s@]+)(@)/g;

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
  constructor(pluginId, instanceId, knownSecrets = [], sink = null) {
    this.pluginId = pluginId;
    this.instanceId = instanceId;
    this.knownSecrets = knownSecrets;
    this.entries = [];
    this.sink = sink || ((level, line) => {
      if (level === 'ERROR' || level === 'WARN') console.error(line);
      else console.log(line);
    });
  }

  log(level, msg) {
    const scrubbed = redactSecrets(msg, this.knownSecrets);
    const line = `depth3d: plugin ${this.pluginId} [${this.instanceId}] ${level}: ${scrubbed}`;
    this.entries.push({ level, message: scrubbed, line });
    if (this.entries.length > 200) this.entries.shift();
    this.sink(level, line);
  }
}
