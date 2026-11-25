import fs from 'node:fs';
import path from 'node:path';

export class PluginLocale {
  constructor(strings = {}) {
    this.strings = strings;
  }

  translate(key, vars = null) {
    const tmpl = this.strings[key];
    if (tmpl === undefined) return String(key);
    if (!vars || typeof vars !== 'object') return tmpl;
    let out = tmpl;
    for (const [k, v] of Object.entries(vars)) {
      out = out.split(`{{${k}}}`).join(String(v)).split(`{${k}}`).join(String(v));
    }
    return out;
  }

  static fromDir(pluginDir, localeTag = 'zh-CN') {
    const locDir = path.join(pluginDir, 'locales');
    if (!fs.existsSync(locDir)) return new PluginLocale({});
    const clean = String(localeTag || 'zh-CN').replace('_', '-');
    const lang = clean.split('-')[0];
    const candidates = [...new Set([clean, lang, 'zh-CN', 'en'].filter(Boolean))].reverse();
    const merged = {};
    for (const tag of candidates) {
      const f = path.join(locDir, `${tag}.json`);
      if (fs.existsSync(f)) {
        try {
          const obj = JSON.parse(fs.readFileSync(f, 'utf8'));
          for (const [k, v] of Object.entries(obj)) {
            if (typeof v === 'string') merged[k] = v;
          }
        } catch {
          // ignore invalid locale file
        }
      }
    }
    return new PluginLocale(merged);
  }
}
