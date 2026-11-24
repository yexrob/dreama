import { DOMParser, parseHTML } from 'linkedom';

/** §11.1 (S4): max 8 parsed documents per call, 4 MB per doc, 200k total element nodes. */
export const MAX_DOCS_PER_CALL = 8;
export const MAX_DOC_BYTES = 4 * 1024 * 1024;
export const MAX_TOTAL_NODES = 200_000;

function normalizeHtmlInput(text) {
  if (/<html[\s>]/i.test(text)) return text;
  if (/<body[\s>]/i.test(text) || /<head[\s>]/i.test(text)) {
    return `<html>${text}</html>`;
  }
  return `<html><head></head><body>${text}</body></html>`;
}

function collapseWhitespace(raw) {
  return String(raw ?? '').replace(/\s+/g, ' ').trim();
}

export class PluginDomParser {
  constructor(
    maxDocsPerCall = MAX_DOCS_PER_CALL,
    maxDocBytes = MAX_DOC_BYTES,
    maxTotalNodes = MAX_TOTAL_NODES,
  ) {
    this.maxDocsPerCall = maxDocsPerCall;
    this.maxDocBytes = maxDocBytes;
    this.maxTotalNodes = maxTotalNodes;
    this.nextId = 1;
    this.docCount = 0;
    this.totalNodes = 0;
    this.nodes = new Map();
    this._xmlParser = new DOMParser();
  }

  parseHtml(html) {
    const text = String(html ?? '');
    this._checkPreParseLimits(text);
    const { document } = parseHTML(normalizeHtmlInput(text));
    const count = this._countElementsWithinLimit(document);
    this.docCount += 1;
    this.totalNodes += count;
    return this._register(document, false, true);
  }

  parseXml(xml) {
    const text = String(xml ?? '');
    this._checkPreParseLimits(text);
    const document = this._xmlParser.parseFromString(text, 'text/xml');
    const count = this._countElementsWithinLimit(document);
    this.docCount += 1;
    this.totalNodes += count;
    return this._register(document, true, true);
  }

  selectFirstId(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return -1;
    const found = entry.el.querySelector(selector);
    if (!found) return -1;
    return this._register(found, entry.isXml, false);
  }

  selectAllIds(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = Array.from(entry.el.querySelectorAll(selector));
    return list.map((el) => this._register(el, entry.isXml, false));
  }

  childrenIds(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = Array.from(entry.el.children || []);
    return list.map((el) => this._register(el, entry.isXml, false));
  }

  tag(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    if (entry.isDocument) return '#document';
    const raw = String(entry.el.tagName || '');
    return entry.isXml ? raw : raw.toLowerCase();
  }

  text(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    if (entry.isDocument) {
      // Match Jsoup Document.text() which joins element text without comments/doctype
      const parts = [];
      for (const child of Array.from(entry.el.children || [])) {
        const t = collapseWhitespace(child.textContent);
        if (t) parts.push(t);
      }
      return parts.join(' ').trim();
    }
    if (entry.isXml) {
      return collapseWhitespace(entry.el.textContent);
    }
    return collapseWhitespace(this._collectHtmlText(entry.el));
  }

  html(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    if (entry.isDocument) {
      return Array.from(entry.el.children || []).map((c) => c.outerHTML || '').join('');
    }
    return String(entry.el.innerHTML || '');
  }

  outerHtml(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    if (entry.isDocument) {
      return entry.el.toString();
    }
    return String(entry.el.outerHTML || '');
  }

  attr(nodeId, name) {
    const entry = this.nodes.get(nodeId);
    if (!entry || entry.isDocument || typeof entry.el.getAttribute !== 'function') return null;
    if (!entry.el.hasAttribute(name)) return null;
    const val = entry.el.getAttribute(name);
    return val === null || val === undefined ? '' : String(val);
  }

  attrsJson(nodeId) {
    const entry = this.nodes.get(nodeId);
    const attrs = {};
    if (entry && !entry.isDocument && entry.el?.attributes) {
      for (const a of Array.from(entry.el.attributes)) {
        attrs[a.name] = a.value;
      }
    }
    return JSON.stringify(attrs);
  }

  clear() {
    this.nodes.clear();
    this.docCount = 0;
    this.totalNodes = 0;
  }

  _collectHtmlText(node) {
    // Match Jsoup Element.text(): insert space around block-level element boundaries,
    // while keeping inline elements concatenated unless source whitespace separates them.
    const BLOCK_TAGS = new Set([
      'ADDRESS', 'ARTICLE', 'ASIDE', 'BLOCKQUOTE', 'BR', 'DD', 'DIV', 'DL', 'DT',
      'FIELDSET', 'FIGCAPTION', 'FIGURE', 'FOOTER', 'FORM', 'H1', 'H2', 'H3', 'H4',
      'H5', 'H6', 'HEADER', 'HR', 'LI', 'MAIN', 'NAV', 'OL', 'P', 'PRE', 'SECTION',
      'TABLE', 'TBODY', 'TD', 'TFOOT', 'TH', 'THEAD', 'TR', 'UL',
    ]);
    const parts = [];
    const walk = (n) => {
      if (n.nodeType === 3) {
        parts.push(n.nodeValue || '');
        return;
      }
      if (n.nodeType !== 1) return;
      const isBlock = BLOCK_TAGS.has(String(n.tagName || '').toUpperCase());
      if (isBlock) parts.push(' ');
      for (const child of n.childNodes || []) {
        walk(child);
      }
      if (isBlock) parts.push(' ');
    };
    walk(node);
    return parts.join('');
  }

  _checkPreParseLimits(text) {
    if (this.docCount >= this.maxDocsPerCall) {
      throw new Error(`单次调用解析文档数超过上限（最多 ${this.maxDocsPerCall} 个）`);
    }
    if (Buffer.byteLength(text, 'utf8') > this.maxDocBytes) {
      throw new Error(`解析文档超过大小限制（上限 ${Math.round(this.maxDocBytes / (1024 * 1024))} MB）`);
    }
    const remaining = this.maxTotalNodes - this.totalNodes;
    if (remaining <= 0) {
      throw new Error(`单次调用解析的 DOM 节点总数超过上限（最多 ${this.maxTotalNodes} 个）`);
    }
    let openTags = 0;
    for (let i = 0; i < text.length - 1; i++) {
      if (text.charCodeAt(i) === 60) { // '<'
        const next = text.charCodeAt(i + 1);
        if (next !== 47 && next !== 33 && next !== 63 && next !== 32 && next !== 9 && next !== 10 && next !== 13) {
          openTags += 1;
          if (openTags > remaining) {
            throw new Error(`单次调用解析的 DOM 节点总数超过上限（最多 ${this.maxTotalNodes} 个）`);
          }
        }
      }
    }
  }

  _countElementsWithinLimit(root) {
    const remaining = this.maxTotalNodes - this.totalNodes;
    let count = 0;
    const stack = [root];
    while (stack.length > 0) {
      const cur = stack.pop();
      count += 1;
      if (count > remaining) {
        throw new Error(`单次调用解析的 DOM 节点总数超过上限（最多 ${this.maxTotalNodes} 个）`);
      }
      const children = cur.children || [];
      for (let i = 0; i < children.length; i++) {
        stack.push(children[i]);
      }
    }
    return count;
  }

  _register(el, isXml, isDocument) {
    const id = this.nextId++;
    this.nodes.set(id, { el, isXml, isDocument });
    return id;
  }
}
