import { parseHTML } from 'linkedom';

/** §11.1 (S4): max 8 parsed documents per call, max 4 MB per document. */
export const MAX_DOCS_PER_CALL = 8;
export const MAX_DOC_BYTES = 4 * 1024 * 1024;

export class PluginDomParser {
  constructor(maxDocsPerCall = MAX_DOCS_PER_CALL, maxDocBytes = MAX_DOC_BYTES) {
    this.maxDocsPerCall = maxDocsPerCall;
    this.maxDocBytes = maxDocBytes;
    this.nextId = 1;
    this.docCount = 0;
    this.nodes = new Map();
  }

  parseHtml(html) {
    const text = String(html ?? '');
    this._checkLimits(text);
    const { document } = parseHTML(text);
    const root = document.documentElement || document;
    this.docCount += 1;
    return this._register(root, document);
  }

  parseXml(xml) {
    const text = String(xml ?? '');
    this._checkLimits(text);
    const { document } = parseHTML(text, 'text/xml');
    const root = document.documentElement || document;
    this.docCount += 1;
    return this._register(root, document);
  }

  selectFirstId(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return -1;
    const found = entry.queryRoot.querySelector(selector);
    if (!found) return -1;
    return this._register(found, found);
  }

  selectAllIds(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = Array.from(entry.queryRoot.querySelectorAll(selector));
    return list.map((el) => this._register(el, el));
  }

  childrenIds(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = Array.from(entry.el.children || []);
    return list.map((el) => this._register(el, el));
  }

  tag(nodeId) {
    const entry = this.nodes.get(nodeId);
    return entry ? String(entry.el.tagName || '').toLowerCase() : '';
  }

  text(nodeId) {
    const entry = this.nodes.get(nodeId);
    return entry ? String(entry.el.textContent || '').trim() : '';
  }

  html(nodeId) {
    const entry = this.nodes.get(nodeId);
    return entry ? String(entry.el.innerHTML || '') : '';
  }

  outerHtml(nodeId) {
    const entry = this.nodes.get(nodeId);
    return entry ? String(entry.el.outerHTML || '') : '';
  }

  attr(nodeId, name) {
    const entry = this.nodes.get(nodeId);
    if (!entry || typeof entry.el.getAttribute !== 'function') return null;
    if (!entry.el.hasAttribute(name)) return null;
    return entry.el.getAttribute(name);
  }

  attrsJson(nodeId) {
    const entry = this.nodes.get(nodeId);
    const attrs = {};
    if (entry?.el?.attributes) {
      for (const a of Array.from(entry.el.attributes)) {
        attrs[a.name] = a.value;
      }
    }
    return JSON.stringify(attrs);
  }

  clear() {
    this.nodes.clear();
    this.docCount = 0;
  }

  _checkLimits(text) {
    if (this.docCount >= this.maxDocsPerCall) {
      throw new Error(`单次调用解析文档数超过上限（最多 ${this.maxDocsPerCall} 个）`);
    }
    if (Buffer.byteLength(text, 'utf8') > this.maxDocBytes) {
      throw new Error(`解析文档超过大小限制（上限 ${Math.round(this.maxDocBytes / (1024 * 1024))} MB）`);
    }
  }

  _register(el, queryRoot) {
    const id = this.nextId++;
    this.nodes.set(id, { el, queryRoot });
    return id;
  }
}
