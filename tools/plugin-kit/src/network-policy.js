import dns from 'node:dns/promises';

export class NetworkPolicyError extends Error {
  constructor(message) {
    super(message);
    this.name = 'NetworkPolicyError';
  }
}

export function canonicalizeHost(raw) {
  let h = String(raw || '').trim().toLowerCase();
  if (h.startsWith('[') && h.endsWith(']')) {
    h = h.slice(1, -1);
  }
  const zoneIdx = h.indexOf('%');
  if (zoneIdx >= 0) h = h.slice(0, zoneIdx);
  return h.replace(/\.+$/, '');
}

export function canonicalizePattern(raw) {
  return String(raw || '').trim().toLowerCase().replace(/\.+$/, '');
}

export function originKey(urlObj) {
  const scheme = urlObj.protocol.replace(/:$/, '').toLowerCase();
  const host = canonicalizeHost(urlObj.hostname);
  const port = urlObj.port ? Number(urlObj.port) : (scheme === 'https' ? 443 : 80);
  return `${scheme}://${host}:${port}`;
}

export function isForbiddenHostName(host) {
  const h = canonicalizeHost(host);
  return !h ||
    h === 'localhost' ||
    h.endsWith('.localhost') ||
    h === 'ip6-localhost' ||
    h === 'ip6-loopback';
}

export function isLocalDomain(host) {
  const h = canonicalizeHost(host);
  return h.endsWith('.local') ||
    h.endsWith('.lan') ||
    h.endsWith('.internal') ||
    (!h.includes('.') && !h.includes(':'));
}

function parseIpv4Part(part) {
  if (!part) return -1;
  let value;
  if (/^0[xX][0-9a-fA-F]+$/.test(part)) {
    value = Number.parseInt(part.slice(2), 16);
  } else if (/^0[0-7]+$/.test(part)) {
    value = Number.parseInt(part.slice(1), 8);
  } else if (/^(?:0|[1-9][0-9]*)$/.test(part)) {
    value = Number.parseInt(part, 10);
  } else {
    return -1;
  }
  if (!Number.isSafeInteger(value) || value < 0 || value > 0xFFFFFFFF) return -1;
  return value;
}

/**
 * Parses standard 4-octet decimal IPv4 AND legacy/non-standard forms
 * (`127.1`, `10.1`, `0x7f.0.0.1`, `0177.0.0.1`, `2130706433`) into a 4-byte Uint8Array.
 */
export function parseFlexibleIpv4Bytes(rawHost) {
  const host = canonicalizeHost(rawHost);
  if (!host || host.includes(':')) return null;
  const parts = host.split('.');
  if (parts.length < 1 || parts.length > 4) return null;
  if (parts.some((p) => !p)) return null;

  const vals = parts.map(parseIpv4Part);
  if (vals.some((v) => v < 0)) return null;

  let addr;
  if (vals.length === 1) {
    addr = vals[0];
  } else if (vals.length === 2) {
    if (vals[0] > 0xFF || vals[1] > 0xFFFFFF) return null;
    addr = (vals[0] * 0x1000000) + vals[1];
  } else if (vals.length === 3) {
    if (vals[0] > 0xFF || vals[1] > 0xFF || vals[2] > 0xFFFF) return null;
    addr = (vals[0] * 0x1000000) + (vals[1] * 0x10000) + vals[2];
  } else {
    if (vals[0] > 0xFF || vals[1] > 0xFF || vals[2] > 0xFF || vals[3] > 0xFF) return null;
    addr = (vals[0] * 0x1000000) + (vals[1] * 0x10000) + (vals[2] * 0x100) + vals[3];
  }
  return new Uint8Array([
    (addr >>> 24) & 0xFF,
    (addr >>> 16) & 0xFF,
    (addr >>> 8) & 0xFF,
    addr & 0xFF,
  ]);
}

/**
 * Parses any IPv6 literal (including embedded dotted IPv4 tails like
 * `::127.0.0.1`, `::ffff:10.0.0.1`, `64:ff9b::192.168.1.1`) into a 16-byte Uint8Array.
 */
export function parseIpv6Bytes(rawHost) {
  let host = canonicalizeHost(rawHost);
  if (!host.includes(':')) return null;

  // Handle optional embedded dotted IPv4 tail
  const lastColon = host.lastIndexOf(':');
  if (lastColon >= 0 && host.slice(lastColon + 1).includes('.')) {
    const v4 = parseFlexibleIpv4Bytes(host.slice(lastColon + 1));
    if (!v4) return null;
    const hi = ((v4[0] << 8) | v4[1]).toString(16);
    const lo = ((v4[2] << 8) | v4[3]).toString(16);
    host = `${host.slice(0, lastColon)}:${hi}:${lo}`;
  }

  const dblIdx = host.indexOf('::');
  if (dblIdx !== -1 && host.indexOf('::', dblIdx + 2) !== -1) return null;

  let leftGroups = [];
  let rightGroups = [];
  if (dblIdx >= 0) {
    const left = host.slice(0, dblIdx);
    const right = host.slice(dblIdx + 2);
    leftGroups = left ? left.split(':') : [];
    rightGroups = right ? right.split(':') : [];
    if (leftGroups.length + rightGroups.length > 7) return null;
  } else {
    leftGroups = host.split(':');
    if (leftGroups.length !== 8) return null;
  }

  const parseGroup = (g) => {
    if (!/^[0-9a-f]{1,4}$/.test(g)) return -1;
    return Number.parseInt(g, 16);
  };
  const leftVals = leftGroups.map(parseGroup);
  const rightVals = rightGroups.map(parseGroup);
  if (leftVals.some((v) => v < 0) || rightVals.some((v) => v < 0)) return null;

  const zeroCount = 8 - leftVals.length - rightVals.length;
  const groups = [...leftVals, ...new Array(zeroCount).fill(0), ...rightVals];
  if (groups.length !== 8) return null;

  const out = new Uint8Array(16);
  for (let i = 0; i < 8; i++) {
    out[i * 2] = (groups[i] >> 8) & 0xFF;
    out[i * 2 + 1] = groups[i] & 0xFF;
  }
  return out;
}

export function parseAnyIpLiteral(rawHost) {
  const h = canonicalizeHost(rawHost);
  if (!h) return null;
  if (h.includes(':')) return parseIpv6Bytes(h);
  return parseFlexibleIpv4Bytes(h);
}

export function formatIpBytes(bytes) {
  if (bytes.length === 4) {
    return `${bytes[0]}.${bytes[1]}.${bytes[2]}.${bytes[3]}`;
  }
  const groups = [];
  for (let i = 0; i < 16; i += 2) {
    groups.push(((bytes[i] << 8) | bytes[i + 1]).toString(16));
  }
  return groups.join(':');
}

function extractEmbeddedIpv4(b16) {
  // IPv4-mapped ::ffff:0:0/96
  let z10 = true;
  for (let i = 0; i < 10; i++) if (b16[i] !== 0) { z10 = false; break; }
  if (z10 && b16[10] === 0xFF && b16[11] === 0xFF) {
    return b16.slice(12, 16);
  }
  // IPv4-compatible ::/96 (excluding :: and ::1)
  if (z10 && b16[10] === 0 && b16[11] === 0) {
    const isAllZero = b16[12] === 0 && b16[13] === 0 && b16[14] === 0 && b16[15] === 0;
    const isLoopback = b16[12] === 0 && b16[13] === 0 && b16[14] === 0 && b16[15] === 1;
    if (!isAllZero && !isLoopback) {
      return b16.slice(12, 16);
    }
  }
  // NAT64 well-known 64:ff9b::/96
  if (b16[0] === 0x00 && b16[1] === 0x64 && b16[2] === 0xFF && b16[3] === 0x9B) {
    let midZero = true;
    for (let i = 4; i < 12; i++) if (b16[i] !== 0) { midZero = false; break; }
    if (midZero) return b16.slice(12, 16);
    // NAT64 local-use 64:ff9b:1::/48
    if (b16[4] === 0x00 && b16[5] === 0x01) {
      return b16.slice(12, 16);
    }
  }
  // 6to4 2002::/16
  if (b16[0] === 0x20 && b16[1] === 0x02) {
    return b16.slice(2, 6);
  }
  return null;
}

export function isAlwaysBlockedBytes(bytes) {
  if (bytes.length === 4) {
    const [b0, b1, b2] = bytes;
    if (b0 === 0 || b0 === 127) return true; // 0/8, 127/8 loopback
    if (b0 === 169 && b1 === 254) return true; // 169.254/16 link-local / cloud metadata
    if (b0 === 192 && b1 === 0 && b2 === 0) return true; // 192.0.0/24 IETF protocol assignments
    if (b0 === 192 && b1 === 0 && b2 === 2) return true; // TEST-NET-1
    if (b0 === 198 && b1 === 51 && b2 === 100) return true; // TEST-NET-2
    if (b0 === 203 && b1 === 0 && b2 === 113) return true; // TEST-NET-3
    if (b0 >= 224) return true; // 224/4 multicast, 240/4 reserved, 255.255.255.255 broadcast
    return false;
  }
  if (bytes.length === 16) {
    // :: and ::1
    let first15Zero = true;
    for (let i = 0; i < 15; i++) if (bytes[i] !== 0) { first15Zero = false; break; }
    if (first15Zero && (bytes[15] === 0 || bytes[15] === 1)) return true;
    // fe80::/10 link-local
    if (bytes[0] === 0xFE && (bytes[1] & 0xC0) === 0x80) return true;
    // ff00::/8 multicast
    if (bytes[0] === 0xFF) return true;
    // 100::/64 discard
    if (bytes[0] === 0x01 && bytes[1] === 0x00 &&
        bytes[2] === 0 && bytes[3] === 0 && bytes[4] === 0 && bytes[5] === 0 && bytes[6] === 0 && bytes[7] === 0) {
      return true;
    }
    // 2001:db8::/32 documentation
    if (bytes[0] === 0x20 && bytes[1] === 0x01 && bytes[2] === 0x0D && bytes[3] === 0xB8) return true;

    const embedded = extractEmbeddedIpv4(bytes);
    if (embedded) return isAlwaysBlockedBytes(embedded);
    return false;
  }
  return true;
}

export function isPrivateBytes(bytes) {
  if (bytes.length === 4) {
    const [b0, b1] = bytes;
    if (b0 === 10) return true; // 10/8
    if (b0 === 172 && b1 >= 16 && b1 <= 31) return true; // 172.16/12
    if (b0 === 192 && b1 === 168) return true; // 192.168/16
    if (b0 === 100 && b1 >= 64 && b1 <= 127) return true; // 100.64/10 CGNAT
    if (b0 === 198 && (b1 === 18 || b1 === 19)) return true; // 198.18/15 benchmark
    return false;
  }
  if (bytes.length === 16) {
    if ((bytes[0] & 0xFE) === 0xFC) return true; // fc00::/7 ULA
    const embedded = extractEmbeddedIpv4(bytes);
    if (embedded) return isPrivateBytes(embedded);
    return false;
  }
  return false;
}

export function isAlwaysBlockedLiteral(host) {
  const bytes = parseAnyIpLiteral(host);
  return bytes ? isAlwaysBlockedBytes(bytes) : false;
}

export function isLoopbackLiteral(host) {
  return isAlwaysBlockedLiteral(host);
}

export function isPrivateLiteral(host) {
  const bytes = parseAnyIpLiteral(host);
  return bytes ? isPrivateBytes(bytes) : false;
}

export function matchesWhitelist(host, patterns) {
  const h = canonicalizeHost(host);
  return (patterns || []).some((raw) => {
    const p = canonicalizePattern(raw);
    if (p.startsWith('*.')) {
      const suffix = p.slice(2);
      return h.length > suffix.length + 1 && h.endsWith(`.${suffix}`);
    }
    return h === p;
  });
}

export function extractUserHosts(manifest, config) {
  const out = new Set();
  for (const field of manifest.config || []) {
    if (field.type !== 'url') continue;
    const raw = config?.[field.key];
    if (typeof raw !== 'string' || !raw.trim()) continue;
    try {
      const candidate = raw.includes('://') ? raw.trim() : `https://${raw.trim()}`;
      const u = new URL(candidate);
      out.add(canonicalizeHost(u.hostname));
    } catch {
      // ignore invalid
    }
  }
  return out;
}

export class NetworkPolicy {
  constructor(permissions = {}, userHosts = new Set(), dnsResolver = null, opts = {}) {
    this.permissions = {
      hosts: (permissions.hosts || []).map((h) => canonicalizePattern(h)),
      userHosts: Boolean(permissions.userHosts),
      lan: Boolean(permissions.lan),
    };
    this.userHosts = new Set([...userHosts].map((h) => canonicalizeHost(h)));
    this.userHostIps = new Set();
    for (const raw of userHosts) {
      const b = parseAnyIpLiteral(raw);
      if (b) this.userHostIps.add(formatIpBytes(b));
    }
    this.requireHttpsAlways = Boolean(opts.requireHttpsAlways);
    this.dnsResolver = dnsResolver || (async (host) => {
      const lit = parseAnyIpLiteral(host);
      if (lit) return [formatIpBytes(lit)];
      const records = await dns.lookup(host, { all: true });
      return records.map((r) => r.address);
    });
  }

  async validateFetchUrl(rawUrl, previousScheme = null) {
    const url = this._parseAndCheckScheme(rawUrl, previousScheme);
    const host = canonicalizeHost(url.hostname);
    this._rejectForbiddenHostName(host);

    const { addressStrings, addressBytes } = await this._resolveHost(host);
    this._checkNoAlwaysBlocked(host, addressBytes);

    const inHosts = matchesWhitelist(host, this.permissions.hosts);
    const inUserHosts = this.permissions.userHosts && this._isUserConfiguredHost(host, addressBytes);
    const inDeclaredScope = inHosts || inUserHosts;
    if (!inDeclaredScope) {
      throw new NetworkPolicyError(`主机不在插件声明的访问白名单内：${host}`);
    }

    const isPrivateOrLan = addressBytes.some((b) => isPrivateBytes(b)) || isLocalDomain(host);
    if (isPrivateOrLan && !this.permissions.lan) {
      throw new NetworkPolicyError(`插件未声明局域网权限，禁止访问私有地址：${host}`);
    }

    if (url.protocol === 'http:') {
      if (this.requireHttpsAlways) {
        throw new NetworkPolicyError(`此请求仅允许使用 HTTPS：${host}`);
      }
      // §11.3: http allowed for user-entered URL hosts, OR declared hosts in LAN when lan=true
      const httpAllowed = inUserHosts || (this.permissions.lan && isPrivateOrLan);
      if (!httpAllowed) {
        throw new NetworkPolicyError(`公网白名单主机必须使用 HTTPS，禁止明文 HTTP：${host}`);
      }
    }

    return { url, canonicalHost: host, addresses: addressStrings };
  }

  async validateResourceUrl(rawUrl, previousScheme = null) {
    const url = this._parseAndCheckScheme(rawUrl, previousScheme);
    const host = canonicalizeHost(url.hostname);
    this._rejectForbiddenHostName(host);

    const { addressStrings, addressBytes } = await this._resolveHost(host);
    this._checkNoAlwaysBlocked(host, addressBytes);

    const inHosts = matchesWhitelist(host, this.permissions.hosts);
    const inUserHosts = this.permissions.userHosts && this._isUserConfiguredHost(host, addressBytes);
    const inDeclaredScope = inHosts || inUserHosts;
    const isPrivateOrLan = addressBytes.some((b) => isPrivateBytes(b)) || isLocalDomain(host);

    if (isPrivateOrLan) {
      if (!this.permissions.lan || !inDeclaredScope) {
        throw new NetworkPolicyError(`该私有网段地址不在插件声明的局域网访问范围内：${host}`);
      }
    }
    if (this.requireHttpsAlways && url.protocol !== 'https:') {
      throw new NetworkPolicyError(`此请求仅允许使用 HTTPS：${host}`);
    }

    return { url, canonicalHost: host, addresses: addressStrings };
  }

  validateResourceUrlPrecheck(rawUrl) {
    const url = this._parseAndCheckScheme(rawUrl, null);
    const host = canonicalizeHost(url.hostname);
    this._rejectForbiddenHostName(host);

    const parsedIp = parseAnyIpLiteral(host);
    if (parsedIp) {
      this._checkNoAlwaysBlocked(host, [parsedIp]);
      if (isPrivateBytes(parsedIp)) {
        const inDeclared = matchesWhitelist(host, this.permissions.hosts) ||
          (this.permissions.userHosts && this._isUserConfiguredHost(host, [parsedIp]));
        if (!this.permissions.lan || !inDeclared) {
          throw new NetworkPolicyError(`该私有网段地址不在插件声明的局域网访问范围内：${host}`);
        }
      }
    } else if (isLocalDomain(host)) {
      const inDeclared = matchesWhitelist(host, this.permissions.hosts) ||
        (this.permissions.userHosts && this.userHosts.has(host));
      if (!this.permissions.lan || !inDeclared) {
        throw new NetworkPolicyError(`该局域网域名不在插件声明的访问范围内：${host}`);
      }
    }
    return url;
  }

  validatePlaybackUrlSyntax(rawUrl) {
    return this.validateResourceUrlPrecheck(rawUrl);
  }

  _isUserConfiguredHost(host, addressBytes) {
    if (this.userHosts.has(host)) return true;
    if (this.userHostIps.size > 0 && addressBytes.every((b) => this.userHostIps.has(formatIpBytes(b)))) {
      return true;
    }
    return false;
  }

  async _resolveHost(host) {
    const lit = parseAnyIpLiteral(host);
    if (lit) {
      return { addressStrings: [formatIpBytes(lit)], addressBytes: [lit] };
    }
    let resolved;
    try {
      resolved = await this.dnsResolver(host);
    } catch (e) {
      throw new NetworkPolicyError(`无法解析主机地址：${host} (${e.message})`);
    }
    if (!resolved || resolved.length === 0) {
      throw new NetworkPolicyError(`无法解析主机地址：${host}`);
    }
    const addressBytes = [];
    const addressStrings = [];
    for (const rawIp of resolved) {
      const b = parseAnyIpLiteral(rawIp);
      if (!b) {
        throw new NetworkPolicyError(`无法识别解析出的 IP 地址：${rawIp}`);
      }
      addressBytes.push(b);
      addressStrings.push(formatIpBytes(b));
    }
    return { addressStrings, addressBytes };
  }

  _checkNoAlwaysBlocked(host, addressBytes) {
    if (addressBytes.some((b) => isAlwaysBlockedBytes(b))) {
      throw new NetworkPolicyError(`禁止访问回环、链路本地或保留地址：${host}`);
    }
  }

  _parseAndCheckScheme(rawUrl, previousScheme) {
    const trimmed = String(rawUrl || '').trim();
    const sepIdx = trimmed.indexOf('://');
    const rawScheme = sepIdx > 0 ? trimmed.slice(0, sepIdx).toLowerCase() : '';
    if (rawScheme !== 'http' && rawScheme !== 'https') {
      throw new NetworkPolicyError('仅允许 http 或 https 协议');
    }
    let parsed;
    try {
      parsed = new URL(trimmed);
    } catch {
      throw new NetworkPolicyError('不支持或无效的请求地址');
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new NetworkPolicyError('仅允许 http 或 https 协议');
    }
    if (previousScheme && String(previousScheme).toLowerCase().replace(/:$/, '') === 'https' &&
        parsed.protocol === 'http:') {
      throw new NetworkPolicyError('禁止从 HTTPS 降级重定向到 HTTP');
    }
    return parsed;
  }

  _rejectForbiddenHostName(host) {
    if (isForbiddenHostName(host)) {
      throw new NetworkPolicyError(`禁止访问回环或本地主机名：${host}`);
    }
  }
}
