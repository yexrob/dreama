import fs from 'node:fs';
import path from 'node:path';

export const MAX_STORAGE_BYTES = 1024 * 1024;

export class PluginStorage {
  constructor(filePath = null, maxBytes = MAX_STORAGE_BYTES) {
    this.filePath = filePath;
    this.maxBytes = maxBytes;
    this.data = {};
    if (filePath && fs.existsSync(filePath)) {
      let parsed;
      try {
        parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
      } catch (err) {
        // Preserve corrupted file untouched and report error (do not silently wipe)
        throw new Error(`插件存储文件已损坏，未覆盖原文件：${err.message}`);
      }
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('插件存储文件格式无效，未覆盖原文件');
      }
      this.data = parsed;
    }
  }

  get(key) {
    return Object.prototype.hasOwnProperty.call(this.data, key) ? this.data[key] : null;
  }

  set(key, value) {
    const prevExisted = Object.prototype.hasOwnProperty.call(this.data, key);
    const prev = this.data[key];
    this.data[key] = value === undefined ? null : value;
    const raw = Buffer.from(JSON.stringify(this.data), 'utf8');
    if (raw.length > this.maxBytes) {
      if (prevExisted) this.data[key] = prev;
      else delete this.data[key];
      throw new Error(`插件存储空间超过上限（${Math.round(this.maxBytes / 1024)} KB）`);
    }
    this._persist(raw);
  }

  remove(key) {
    if (Object.prototype.hasOwnProperty.call(this.data, key)) {
      delete this.data[key];
      this._persist(Buffer.from(JSON.stringify(this.data), 'utf8'));
    }
  }

  keys() {
    return Object.keys(this.data);
  }

  clear() {
    this.data = {};
    if (this.filePath && fs.existsSync(this.filePath)) {
      fs.unlinkSync(this.filePath);
    }
  }

  _persist(buf) {
    if (!this.filePath) return;
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    fs.writeFileSync(this.filePath, buf);
  }
}
