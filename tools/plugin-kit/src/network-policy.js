import dns from 'node:dns/promises';
import net from 'node:net';

export class NetworkPolicyError extends Error {
  constructor(message) {
    super(message);
    this.name = 'NetworkPolicyError';
  }
}

export function isLocalDomain(host) {
  const h = host.toLowerCase();
  return h.endsWith('.local') || h.endsWith('.lan') || h.endsWith('.internal') ||
    (!h.includes('.') && !h.includes(':'));
}

function parseIpv4(ip) {
  const parts = ip.split('.');
  if (parts.length !== 4) return null;
  const nums = parts.map((p) => (/^\d+$/.test(p) ? Number(p) : -1));
  if (nums.some((n) => n < 0 || n > 255)) return null;
  return nums;
}

function extractMappedIpv4(ip) {
  const low = ip.toLowerCase();
  if (low.startsWith('::ffff:')) {
    const rest = low.slice(7);
    if (net.isIPv4(rest)) return rest;
  }
  return null;
}

export function isLoopbackOrReservedIp(ip) {
  const clean = ip.replace(/^\[|\]$/g, '').split('%')[0].toLowerCase();
  const mapped = extractMappedIpv4(clean);
  if (mapped) return isLoopbackOrReservedIp(mapped);

  if (net.isIPv4(clean)) {
    const [b0] = parseIpv4(clean);
    if (b0 === 0 || b0 === 127) return true;
    if (b0 >= 224) return true; // multicast / reserved
    return false;
  }
  if (net.isIPv6(clean)) {
    if (clean === '::1' || clean === '::' || clean === '0:0:0:0:0:0:0:1' || clean === '0:0:0:0:0:0:0:0') {
      return true;
    }
    if (clean.startsWith('ff')) return true; // multicast
    return false;
  }
  return false;
}

export function isPrivateOrLanIp(ip) {
  const clean = ip.replace(/^\[|\]$/g, '').split('%')[0].toLowerCase();
  const mapped = extractMappedIpv4(clean);
  if (mapped) return isPrivateOrLanIp(mapped);

  if (net.isIPv4(clean)) {
    const [b0, b1] = parseIpv4(clean);
    if (b0 === 10) return true;
    if (b0 === 172 && b1 >= 16 && b1 <= 31) return true;
    if (b0 === 192 && b1 === 168) return true;
    if (b0 === 169 && b1 === 254) return true;
    if (b0 === 100 && b1 >= 64 && b1 <= 127) return true;
    if (b0 === 198 && (b1 === 18 || b1 === 19)) return true;
    return false;
  }
  if (net.isIPv6(clean)) {
    if (clean.startsWith('fc') || clean.startsWith('fd')) return true; // ULA fc00::/7
    if (/^fe[89ab]/.test(clean)) return true; // link-local fe80::/10
    return false;
  }
  return false;
}

export function isLoopbackLiteral(host) {
  const clean = host.trim().replace(/^\[|\]$/g, '');
  if (!net.isIP(clean)) return false;
  return isLoopbackOrReservedIp(clean);
}

export function isPrivateLiteral(host) {
  const clean = host.trim().replace(/^\[|\]$/g, '');
  if (!net.isIP(clean)) return false;
  return isPrivateOrLanIp(clean);
}

export function matchesWhitelist(host, patterns) {
  const h = host.toLowerCase();
  return (patterns || []).some((raw) => {
    const p = String(raw).toLowerCase();
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
      const u = new URL(raw.includes('://') ? raw.trim() : `https://${raw.trim()}`);
      out.add(u.hostname.toLowerCase());
    } catch {
      // ignore invalid
    }
  }
  return out;
}

export class NetworkPolicy {
  constructor(permissions = {}, userHosts = new Set(), dnsResolver = null) {
    this.permissions = {
      hosts: (permissions.hosts || []).map((h) => String(h).toLowerCase()),
      userHosts: Boolean(permissions.userHosts),
      lan: Boolean(permissions.lan),
    };
    this.userHosts = new Set([...userHosts].map((h) => String(h).toLowerCase()));
    this.dnsResolver = dnsResolver || (async (host) => {
      if (net.isIP(host)) return [host];
      const records = await dns.lookup(host, { all: true });
      return records.map((r) => r.address);
    });
  }

  async validateFetchUrl(rawUrl) {
    const url = this._parseHttpUrl(rawUrl);
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
    this._rejectLoopbackName(host);

    let addresses;
    if (net.isIP(host)) {
      addresses = [host];
    } else {
      try {
        addresses = await this.dnsResolver(host);
      } catch (e) {
        throw new NetworkPolicyError(`无法解析主机地址：${host} (${e.message})`);
      }
    }
    if (!addresses || addresses.length === 0) {
      throw new NetworkPolicyError(`无法解析主机地址：${host}`);
    }
    if (addresses.some((ip) => isLoopbackOrReservedIp(ip))) {
      throw new NetworkPolicyError(`禁止访问回环或保留地址：${host}`);
    }

    const isLanHost = addresses.some((ip) => isPrivateOrLanIp(ip)) || isLocalDomain(host);
    if (isLanHost && !this.permissions.lan) {
      throw new NetworkPolicyError(`插件未声明局域网权限，禁止访问私有地址：${host}`);
    }

    if (url.protocol === 'http:') {
      if (!this.permissions.lan) {
        throw new NetworkPolicyError(`插件未声明局域网权限，仅允许使用 HTTPS：${host}`);
      }
      if (!isLanHost) {
        throw new NetworkPolicyError(`公网主机必须使用 HTTPS，禁止明文 HTTP：${host}`);
      }
    }

    const allowed = matchesWhitelist(host, this.permissions.hosts) ||
      (this.permissions.userHosts && this.userHosts.has(host)) ||
      (this.permissions.lan && isLanHost);
    if (!allowed) {
      throw new NetworkPolicyError(`主机不在插件声明的访问白名单内：${host}`);
    }

    return { url, addresses };
  }

  validatePlaybackUrlSyntax(rawUrl) {
    const url = this._parseHttpUrl(rawUrl);
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
    this._rejectLoopbackName(host);
    if (isLoopbackLiteral(host)) {
      throw new NetworkPolicyError(`播放地址禁止指向回环地址：${host}`);
    }
    if (!this.permissions.lan && (isPrivateLiteral(host) || isLocalDomain(host))) {
      throw new NetworkPolicyError(`插件未声明局域网权限，播放地址禁止指向私有网段：${host}`);
    }
    return url;
  }

  _parseHttpUrl(rawUrl) {
    let parsed;
    try {
      parsed = new URL(String(rawUrl).trim());
    } catch {
      throw new NetworkPolicyError('不支持或无效的请求地址');
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new NetworkPolicyError('仅允许 http 或 https 协议');
    }
    return parsed;
  }

  _rejectLoopbackName(host) {
    if (host === 'localhost' || host.endsWith('.localhost') || host === 'ip6-localhost' || host === 'ip6-loopback') {
      throw new NetworkPolicyError(`禁止访问回环地址：${host}`);
    }
  }
}
