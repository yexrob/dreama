import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import JSZip from 'jszip';
import { validateManifest } from './schema.js';

/** §11.4: maximum plugin package size is 8 MB. */
export const MAX_PACKAGE_BYTES = 8 * 1024 * 1024;
const MAX_ENTRIES = 64;
const MAX_UNPACKED_BYTES = 16 * 1024 * 1024;

const SIG_LFH = 0x04034b50;
const SIG_DD = 0x08074b50;
const SIG_CDH = 0x02014b50;
const SIG_EOCD = 0x06054b50;

const strictUtf8Decoder = new TextDecoder('utf-8', { fatal: true });

function decodeStrictUtf8(buf, label) {
  try {
    return strictUtf8Decoder.decode(buf);
  } catch {
    throw new Error(`Invalid UTF-8 bytes in plugin archive (${label})`);
  }
}

// Standard IEEE 802.3 CRC-32 table (works identically across all Node versions)
const CRC32_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let j = 0; j < 8; j++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    t[i] = c >>> 0;
  }
  return t;
})();

function crc32Unsigned(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) {
    c = CRC32_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  }
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function normalizeEntryName(raw) {
  const cleaned = String(raw).replace(/\\/g, '/').replace(/^\.\//, '');
  if (cleaned.startsWith('/') || cleaned.includes('..') || cleaned.includes('\0')) {
    throw new Error(`Archive contains illegal entry path: ${raw}`);
  }
  return cleaned;
}

export function sha256Hex(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

export async function packPluginToBuffer(pluginDir) {
  const manifestPath = path.join(pluginDir, 'manifest.json');
  const mainJsPath = path.join(pluginDir, 'main.js');
  if (!fs.existsSync(manifestPath)) {
    throw new Error(`Missing manifest.json in ${pluginDir}`);
  }
  if (!fs.existsSync(mainJsPath)) {
    throw new Error(`Missing main.js in ${pluginDir}`);
  }
  const manifestRaw = fs.readFileSync(manifestPath, 'utf8');
  const manifest = JSON.parse(manifestRaw);
  const check = validateManifest(manifest);
  if (!check.valid) {
    throw new Error(`Invalid manifest.json:\n  - ${check.errors.join('\n  - ')}`);
  }

  const zip = new JSZip();
  const fixedDate = new Date('2026-01-01T00:00:00Z');
  zip.file('manifest.json', manifestRaw, { date: fixedDate });
  zip.file('main.js', fs.readFileSync(mainJsPath), { date: fixedDate });

  const iconPath = path.join(pluginDir, 'icon.png');
  if (fs.existsSync(iconPath)) {
    zip.file('icon.png', fs.readFileSync(iconPath), { date: fixedDate });
  }

  const localesDir = path.join(pluginDir, 'locales');
  if (fs.existsSync(localesDir) && fs.statSync(localesDir).isDirectory()) {
    const entries = fs.readdirSync(localesDir).filter((f) => f.endsWith('.json')).sort();
    for (const file of entries) {
      zip.file(`locales/${file}`, fs.readFileSync(path.join(localesDir, file)), { date: fixedDate });
    }
  }

  const buffer = await zip.generateAsync({
    type: 'nodebuffer',
    compression: 'DEFLATE',
    compressionOptions: { level: 9 },
  });
  if (buffer.length > MAX_PACKAGE_BYTES) {
    throw new Error(`Packed archive exceeds 8 MB limit (${buffer.length} bytes)`);
  }
  return {
    manifest,
    buffer,
    sha256: sha256Hex(buffer),
  };
}

export async function packPluginToFile(pluginDir, outputPath = null) {
  const { manifest, buffer, sha256 } = await packPluginToBuffer(pluginDir);
  const outFile = outputPath || path.join(process.cwd(), `${manifest.id}-${manifest.version}.d3plugin`);
  fs.mkdirSync(path.dirname(path.resolve(outFile)), { recursive: true });
  fs.writeFileSync(outFile, buffer);
  return { manifest, outputPath: outFile, size: buffer.length, sha256 };
}

/**
 * Inspects a `.d3plugin` archive buffer enforcing protocol §11.4 (S9):
 * Parses BOTH the Zip Central Directory AND walks every Local File Header
 * sequentially from byte 0 to `cdOffset`, rejecting any size overflow (> 8 MB),
 * duplicate entry names, zip-slip paths, or mismatch between Central Directory
 * and Local File Headers (identical rules to Android `PluginPackageArchive.inspect`).
 */
export async function inspectArchiveBuffer(inputBuf) {
  const buf = Buffer.isBuffer(inputBuf) ? inputBuf : Buffer.from(inputBuf);
  if (buf.length > MAX_PACKAGE_BYTES) {
    throw new Error('Archive exceeds 8 MB package size limit');
  }
  if (buf.length < 22) {
    throw new Error('Archive is not a valid zip file');
  }

  // 1. Locate End of Central Directory (EOCD) with zero trailing bytes
  const minOffset = Math.max(0, buf.length - 22 - 65535);
  let eocdOffset = -1;
  for (let pos = buf.length - 22; pos >= minOffset; pos--) {
    if (buf.readUInt32LE(pos) === SIG_EOCD) {
      const commentLen = buf.readUInt16LE(pos + 20);
      if (pos + 22 + commentLen === buf.length) {
        eocdOffset = pos;
        break;
      }
    }
  }
  if (eocdOffset < 0) {
    throw new Error('Archive missing valid zip End of Central Directory');
  }

  const diskNo = buf.readUInt16LE(eocdOffset + 4);
  const cdDiskNo = buf.readUInt16LE(eocdOffset + 6);
  const entriesOnDisk = buf.readUInt16LE(eocdOffset + 8);
  const totalEntries = buf.readUInt16LE(eocdOffset + 10);
  const cdSize = buf.readUInt32LE(eocdOffset + 12);
  const cdOffset = buf.readUInt32LE(eocdOffset + 16);

  if (diskNo !== 0 || cdDiskNo !== 0 || entriesOnDisk !== totalEntries) {
    throw new Error('Multi-disk or inconsistent zip entry count is not supported');
  }
  if (totalEntries === 0xFFFF || cdSize === 0xFFFFFFFF || cdOffset === 0xFFFFFFFF) {
    throw new Error('Zip64 archives are not supported');
  }
  if (totalEntries > MAX_ENTRIES) {
    throw new Error(`Archive has too many entries (max ${MAX_ENTRIES})`);
  }
  if (cdOffset + cdSize !== eocdOffset) {
    throw new Error('Zip Central Directory offset/size mismatch');
  }

  // 2. Parse Central Directory File Headers and cross-check referenced Local File Headers
  const cdByOffset = new Map();
  const seenRaw = new Set();
  const seenNorm = new Set();
  let pos = cdOffset;

  for (let i = 0; i < totalEntries; i++) {
    if (pos + 46 > eocdOffset || buf.readUInt32LE(pos) !== SIG_CDH) {
      throw new Error('Corrupt zip Central Directory header');
    }
    const flags = buf.readUInt16LE(pos + 8);
    const method = buf.readUInt16LE(pos + 10);
    const crc32 = buf.readUInt32LE(pos + 16);
    const compSize = buf.readUInt32LE(pos + 20);
    const uncompSize = buf.readUInt32LE(pos + 24);
    const nameLen = buf.readUInt16LE(pos + 28);
    const extraLen = buf.readUInt16LE(pos + 30);
    const commentLen = buf.readUInt16LE(pos + 32);
    const lfhOffset = buf.readUInt32LE(pos + 42);

    if ((flags & 1) !== 0) throw new Error('Encrypted zip entries are not supported');
    if (method !== 0 && method !== 8) throw new Error(`Unsupported zip compression method: ${method}`);
    if (compSize === 0xFFFFFFFF || uncompSize === 0xFFFFFFFF || lfhOffset === 0xFFFFFFFF) {
      throw new Error('Zip64 entry is not supported');
    }

    const nameStart = pos + 46;
    const nextPos = nameStart + nameLen + extraLen + commentLen;
    if (nextPos > eocdOffset) throw new Error('Zip Central Directory entry out of bounds');

    const rawNameBuf = buf.subarray(nameStart, nameStart + nameLen);
    const rawName = decodeStrictUtf8(rawNameBuf, 'zip entry name');
    const normName = normalizeEntryName(rawName);
    if (seenRaw.has(rawName) || seenNorm.has(normName)) {
      throw new Error(`Archive contains duplicate entry: ${rawName}`);
    }
    seenRaw.add(rawName);
    seenNorm.add(normName);

    if (lfhOffset + 30 > cdOffset) {
      throw new Error(`Zip Local File Header offset out of bounds for ${rawName}`);
    }
    if (cdByOffset.has(lfhOffset)) {
      throw new Error(`Multiple Central Directory entries point to the same Local File Header: ${rawName}`);
    }
    cdByOffset.set(lfhOffset, {
      rawName,
      rawNameBuf,
      normName,
      flags,
      method,
      crc32,
      compSize,
      uncompSize,
      lfhOffset,
    });
    pos = nextPos;
  }

  if (pos !== eocdOffset) {
    throw new Error('Zip Central Directory has unparsed trailing bytes');
  }

  // 3. Walk ALL Local File Headers sequentially from 0 to cdOffset and match 1-to-1 with Central Directory
  let lfhPos = 0;
  let walkedCount = 0;
  let totalUnpacked = 0;
  let manifestRaw = null;
  let mainJs = null;
  let iconPng = null;
  const locales = {};

  while (lfhPos < cdOffset) {
    if (lfhPos + 30 > cdOffset || buf.readUInt32LE(lfhPos) !== SIG_LFH) {
      throw new Error('Zip Central Directory and Local File Headers mismatch (invalid local header stream)');
    }
    const cd = cdByOffset.get(lfhPos);
    if (!cd) {
      throw new Error('Zip Central Directory and Local File Headers mismatch (unindexed local header)');
    }

    const lfhFlags = buf.readUInt16LE(lfhPos + 6);
    const lfhMethod = buf.readUInt16LE(lfhPos + 8);
    const lfhCrc = buf.readUInt32LE(lfhPos + 14);
    const lfhCompSize = buf.readUInt32LE(lfhPos + 18);
    const lfhUncompSize = buf.readUInt32LE(lfhPos + 22);
    const lfhNameLen = buf.readUInt16LE(lfhPos + 26);
    const lfhExtraLen = buf.readUInt16LE(lfhPos + 28);

    const dataStart = lfhPos + 30 + lfhNameLen + lfhExtraLen;
    if (dataStart > cdOffset) {
      throw new Error(`Zip Local File Header out of bounds for ${cd.rawName}`);
    }
    const lfhNameBuf = buf.subarray(lfhPos + 30, lfhPos + 30 + lfhNameLen);
    if (!lfhNameBuf.equals(cd.rawNameBuf)) {
      throw new Error(`Zip Central Directory filename differs from Local File Header: ${cd.rawName}`);
    }
    if (lfhMethod !== cd.method) {
      throw new Error(`Zip compression method mismatch between CD and LFH: ${cd.rawName}`);
    }

    const hasDataDescriptor = (lfhFlags & 0x08) !== 0;
    if (!hasDataDescriptor) {
      if (lfhCrc !== cd.crc32 || lfhCompSize !== cd.compSize || lfhUncompSize !== cd.uncompSize) {
        throw new Error(`Zip CRC/size mismatch between CD and LFH: ${cd.rawName}`);
      }
    }

    const dataEnd = dataStart + cd.compSize;
    if (dataEnd > cdOffset) {
      throw new Error(`Zip entry compressed data out of bounds: ${cd.rawName}`);
    }
    const compData = buf.subarray(dataStart, dataEnd);

    let nextLfhPos = dataEnd;
    if (hasDataDescriptor) {
      if (nextLfhPos + 12 > cdOffset) {
        throw new Error(`Truncated zip data descriptor for ${cd.rawName}`);
      }
      if (buf.readUInt32LE(nextLfhPos) === SIG_DD) {
        nextLfhPos += 4;
      }
      if (nextLfhPos + 12 > cdOffset) {
        throw new Error(`Truncated zip data descriptor for ${cd.rawName}`);
      }
      const ddCrc = buf.readUInt32LE(nextLfhPos);
      const ddComp = buf.readUInt32LE(nextLfhPos + 4);
      const ddUncomp = buf.readUInt32LE(nextLfhPos + 8);
      if (ddCrc !== cd.crc32 || ddComp !== cd.compSize || ddUncomp !== cd.uncompSize) {
        throw new Error(`Zip data descriptor does not match Central Directory: ${cd.rawName}`);
      }
      nextLfhPos += 12;
    }

    const remaining = MAX_UNPACKED_BYTES - totalUnpacked;
    if (cd.uncompSize > remaining) {
      throw new Error('Archive unpacked size exceeds limit');
    }

    let content;
    if (cd.method === 0) {
      content = compData;
    } else {
      const inflated = zlib.inflateRawSync(compData, { info: true, maxOutputLength: remaining });
      if (inflated.engine.bytesWritten !== compData.length) {
        throw new Error(`Zip deflate stream contains trailing unconsumed bytes: ${cd.rawName}`);
      }
      content = inflated.buffer;
    }
    totalUnpacked += content.length;

    const isDir = cd.rawName.endsWith('/');
    if (isDir) {
      if (content.length !== 0 || cd.uncompSize !== 0) {
        throw new Error(`Zip directory entry contains data: ${cd.rawName}`);
      }
    } else {
      if (content.length !== cd.uncompSize) {
        throw new Error(`Zip decompressed size mismatch: ${cd.rawName}`);
      }
      if (crc32Unsigned(content) !== cd.crc32) {
        throw new Error(`Zip CRC-32 verification failed: ${cd.rawName}`);
      }

      if (cd.normName === 'manifest.json') manifestRaw = decodeStrictUtf8(content, 'manifest.json');
      else if (cd.normName === 'main.js') mainJs = decodeStrictUtf8(content, 'main.js');
      else if (cd.normName === 'icon.png') iconPng = Buffer.from(content);
      else if (cd.normName.startsWith('locales/') && cd.normName.endsWith('.json')) {
        const tag = cd.normName.slice('locales/'.length, -'.json'.length);
        if (tag && !tag.includes('/')) {
          locales[tag] = decodeStrictUtf8(content, cd.normName);
        }
      }
    }

    walkedCount += 1;
    lfhPos = nextLfhPos;
  }

  if (walkedCount !== cdByOffset.size) {
    throw new Error('Zip Central Directory and Local File Header entry count mismatch');
  }
  if (manifestRaw === null) throw new Error('Archive missing manifest.json');
  if (mainJs === null) throw new Error('Archive missing main.js');
  return { manifestRaw, manifest: JSON.parse(manifestRaw), mainJs, iconPng, locales };
}
