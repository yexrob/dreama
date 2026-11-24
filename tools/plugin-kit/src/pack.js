import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import JSZip from 'jszip';
import { validateManifest } from './schema.js';

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

export async function inspectArchiveBuffer(buf) {
  const zip = await JSZip.loadAsync(buf);
  const manifestFile = zip.file('manifest.json');
  const mainFile = zip.file('main.js');
  if (!manifestFile) throw new Error('Archive missing manifest.json');
  if (!mainFile) throw new Error('Archive missing main.js');
  const manifestRaw = await manifestFile.async('string');
  const mainJs = await mainFile.async('string');
  const locales = {};
  for (const [name, entry] of Object.entries(zip.files)) {
    if (!entry.dir && name.startsWith('locales/') && name.endsWith('.json')) {
      const tag = name.slice('locales/'.length, -'.json'.length);
      locales[tag] = await entry.async('string');
    }
  }
  return { manifestRaw, manifest: JSON.parse(manifestRaw), mainJs, locales };
}
