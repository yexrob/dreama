import { parseHTML } from 'linkedom';

export class PluginDomParser {
  constructor() {
    this.nextId = 1;
    this.nodes = new Map();
  }

  parseHtml(html) {
    const { document } = parseHTML(String(html ?? ''));
    const root = document.documentElement || document;
    const id = this._register(root, document);
    return this._summarize(id, root);
  }

  parseXml(xml) {
    // linkedom parses XML cleanly via mimeType: 'text/xml'
    const { document } = parseHTML(String(xml ?? ''), 'text/xml');
    const root = document.documentElement || document;
    const id = this._register(root, document);
    return this._summarize(id, root);
  }

  selectFirst(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return null;
    const found = entry.queryRoot.querySelector(selector);
    if (!found) return null;
    const id = this._register(found, found);
    return this._summarize(id, found);
  }

  selectAll(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = Array.from(entry.queryRoot.querySelectorAll(selector));
    return list.map((el) => {
      const id = this._register(el, el);
      return this._summarize(id, el);
    });
  }

  children(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = Array.from(entry.el.children || []);
    return list.map((el) => {
      const id = this._register(el, el);
      return this._summarize(id, el);
    });
  }

  clear() {
    this.nodes.clear();
  }

  _register(el, queryRoot) {
    const id = this.nextId++;
    this.nodes.set(id, { el, queryRoot });
    return id;
  }

  _summarize(id, el) {
    const attrs = {};
    if (el.attributes) {
      for (const a of Array.from(el.attributes)) {
        attrs[a.name] = a.value;
      }
    }
    return {
      id,
      tag: (el.tagName || '').toLowerCase(),
      text: (el.textContent || '').trim(),
      html: el.innerHTML || '',
      outerHtml: el.outerHTML || '',
      attrs,
    };
  }
}
