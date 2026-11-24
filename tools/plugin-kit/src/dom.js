import { DOMParser, parseHTML } from 'linkedom';

/** §11.1 (S4): max 8 parsed documents per call, 4 MB per doc, 200k total element nodes. */
export const MAX_DOCS_PER_CALL = 8;
export const MAX_DOC_BYTES = 4 * 1024 * 1024;
export const MAX_TOTAL_NODES = 200_000;

const HTML_SKIP_TEXT_TAGS = new Set(['script', 'style']);

const HTML_BLOCK_TAGS = new Set([
  'address', 'article', 'aside', 'blockquote', 'body', 'br', 'caption', 'center',
  'col', 'colgroup', 'dd', 'details', 'dialog', 'dir', 'div', 'dl', 'dt',
  'fieldset', 'figcaption', 'figure', 'footer', 'form', 'frame', 'frameset',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'head', 'header', 'hgroup', 'hr', 'html',
  'li', 'main', 'menu', 'nav', 'noframes', 'noscript', 'ol', 'optgroup', 'option',
  'p', 'pre', 'section', 'select', 'table', 'tbody', 'td', 'tfoot', 'th',
  'thead', 'title', 'tr', 'ul', 'video',
]);

function normalizeHtmlInput(text) {
  if (/<html[\s>]/i.test(text)) return text;
  if (/<body[\s>]/i.test(text) || /<head[\s>]/i.test(text)) {
    return `<html>${text}</html>`;
  }
  return `<html><head></head><body>${text}</body></html>`;
}

/**
 * Collapses ONLY ASCII whitespace (`[ \t\n\r\f]`) and NBSP (`\u00A0`),
 * preserving Unicode spaces such as U+2003 (EM SPACE) identically to Android
 * `PluginDomParser.kt`.
 */
function collapseAsciiAndNbspWhitespace(raw) {
  const s = String(raw ?? '');
  let out = '';
  let inSpace = true;
  for (let i = 0; i < s.length; i++) {
    const ch = s.charCodeAt(i);
    const isSpace = ch === 0x20 || ch === 0x09 || ch === 0x0A || ch === 0x0D || ch === 0x0C || ch === 0xA0;
    if (isSpace) {
      if (!inSpace) {
        out += ' ';
        inSpace = true;
      }
    } else {
      out += s[i];
      inSpace = false;
    }
  }
  if (out.endsWith(' ')) out = out.slice(0, -1);
  return out;
}

function getElementAttr(el, name, isXml) {
  if (!el || el.nodeType === 9 || !el.attributes) return null;
  if (isXml) {
    for (const a of Array.from(el.attributes)) {
      if (a.name === name) return a.value ?? '';
    }
    return null;
  }
  const lower = String(name).toLowerCase();
  for (const a of Array.from(el.attributes)) {
    if (String(a.name).toLowerCase() === lower) return a.value ?? '';
  }
  return null;
}

function getElementChildren(el) {
  if (!el) return [];
  if (el.children && el.children.length > 0) return Array.from(el.children);
  const out = [];
  for (const c of el.childNodes || []) {
    if (c.nodeType === 1) out.push(c);
  }
  return out;
}

function getParentElement(el) {
  const p = el?.parentNode;
  if (!p || p.nodeType !== 1) return null;
  return p;
}

function getPrevElementSibling(el) {
  let cur = el?.previousSibling;
  while (cur) {
    if (cur.nodeType === 1) return cur;
    cur = cur.previousSibling;
  }
  return null;
}

/**
 * Shared-subset CSS selector parser & evaluator mirroring Android's
 * `CssSelectorEngine` in `PluginDomParser.kt` identically.
 */
class SelectorParser {
  constructor(src) {
    this.src = String(src ?? '');
    this.pos = 0;
  }

  parseSelectorList(eofExpected) {
    const out = [];
    this.skipWs();
    if (this.eof()) throw new Error('CSS 选择器不能为空');
    while (true) {
      out.push(this.parseComplexSelector());
      this.skipWs();
      if (this.peek() === ',') {
        this.pos += 1;
        this.skipWs();
        continue;
      }
      break;
    }
    if (eofExpected && !this.eof()) {
      throw new Error(`无效的 CSS 选择器语法：'${this.src}'（位置 ${this.pos}）`);
    }
    return out;
  }

  parseComplexSelector() {
    this.skipWs();
    const head = this.parseCompoundSelector();
    const steps = [];
    while (true) {
      const hadWs = this.skipWs();
      if (this.eof() || this.peek() === ',' || this.peek() === ')') break;
      let comb;
      const c = this.peek();
      if (c === '>') { this.pos += 1; this.skipWs(); comb = '>'; }
      else if (c === '+') { this.pos += 1; this.skipWs(); comb = '+'; }
      else if (c === '~') { this.pos += 1; this.skipWs(); comb = '~'; }
      else if (hadWs) { comb = ' '; }
      else break;
      steps.push([comb, this.parseCompoundSelector()]);
    }
    return { head, steps };
  }

  parseCompoundSelector() {
    let nsPrefix = null;
    let tagName = null;
    const ids = [];
    const classes = [];
    const attrs = [];
    const pseudos = [];
    let matchedAny = false;

    if (!this.eof()) {
      const c = this.peek();
      if (c === '|') {
        this.pos += 1;
        nsPrefix = '';
        if (this.peek() === '*') { this.pos += 1; tagName = null; }
        else tagName = this.readIdent();
        matchedAny = true;
      } else if (c === '*') {
        this.pos += 1;
        if (this.peek() === '|') {
          this.pos += 1;
          nsPrefix = '*';
          if (this.peek() === '*') { this.pos += 1; tagName = null; }
          else tagName = this.readIdent();
        } else {
          tagName = null;
        }
        matchedAny = true;
      } else if (this.isIdentStart(c)) {
        const first = this.readIdent();
        if (this.peek() === '|') {
          this.pos += 1;
          nsPrefix = first;
          if (this.peek() === '*') { this.pos += 1; tagName = null; }
          else tagName = this.readIdent();
        } else {
          tagName = first;
        }
        matchedAny = true;
      }
    }

    while (!this.eof()) {
      const c = this.peek();
      if (c === '#') {
        this.pos += 1;
        ids.push(this.readIdent());
        matchedAny = true;
      } else if (c === '.') {
        this.pos += 1;
        classes.push(this.readIdent());
        matchedAny = true;
      } else if (c === '[') {
        attrs.push(this.parseAttrSelector());
        matchedAny = true;
      } else if (c === ':') {
        pseudos.push(this.parsePseudoSelector());
        matchedAny = true;
      } else {
        break;
      }
    }

    if (!matchedAny) {
      throw new Error(`无效的 CSS 选择器：'${this.src}'（位置 ${this.pos}）`);
    }
    return { nsPrefix, tagName, ids, classes, attrs, pseudos };
  }

  parseAttrSelector() {
    this.pos += 1; // '['
    this.skipWs();
    const name = this.readIdent();
    this.skipWs();
    if (this.peek() === ']') {
      this.pos += 1;
      return { name, op: 'exists', value: null, caseFlag: null };
    }
    let op;
    if (this.src.startsWith('~=', this.pos)) { this.pos += 2; op = '~='; }
    else if (this.src.startsWith('|=', this.pos)) { this.pos += 2; op = '|='; }
    else if (this.src.startsWith('^=', this.pos)) { this.pos += 2; op = '^='; }
    else if (this.src.startsWith('$=', this.pos)) { this.pos += 2; op = '$='; }
    else if (this.src.startsWith('*=', this.pos)) { this.pos += 2; op = '*='; }
    else if (this.peek() === '=') { this.pos += 1; op = '='; }
    else throw new Error(`不支持的属性选择器操作符：'${this.src}'（位置 ${this.pos}）`);

    this.skipWs();
    const value = (this.peek() === '"' || this.peek() === "'")
      ? this.readQuotedString()
      : this.readUnquotedAttrValue();
    this.skipWs();
    let caseFlag = null;
    const f = this.peek();
    if (f === 'i' || f === 'I' || f === 's' || f === 'S') {
      caseFlag = f.toLowerCase();
      this.pos += 1;
      this.skipWs();
    }
    if (this.peek() !== ']') {
      throw new Error(`属性选择器缺少右括号 ']'：'${this.src}'`);
    }
    this.pos += 1;
    return { name, op, value, caseFlag };
  }

  parsePseudoSelector() {
    this.pos += 1; // ':'
    if (this.peek() === ':') {
      this.pos += 1;
      const pe = this.readIdent();
      throw new Error(`不支持的 CSS 伪元素：::${pe}`);
    }
    const rawName = this.readIdent();
    const name = rawName.toLowerCase();
    const hasParens = this.peek() === '(';
    if (!hasParens) {
      if (['first-child', 'last-child', 'only-child', 'first-of-type', 'last-of-type', 'only-of-type', 'empty', 'root'].includes(name)) {
        return { kind: name };
      }
      throw new Error(`不支持的 CSS 选择器伪类：:${rawName}`);
    }
    this.pos += 1; // '('
    if (name === 'not' || name === 'is') {
      const inner = this.parseSelectorList(false);
      this.skipWs();
      if (this.peek() !== ')') throw new Error(`:${name}() 缺少右括号：'${this.src}'`);
      this.pos += 1;
      return { kind: name, inner };
    }
    if (['nth-child', 'nth-last-child', 'nth-of-type', 'nth-last-of-type'].includes(name)) {
      const arg = this.readBalancedParenArg();
      const { a, b } = parseAnPlusB(arg);
      return {
        kind: 'nth',
        a,
        b,
        fromEnd: name === 'nth-last-child' || name === 'nth-last-of-type',
        ofType: name === 'nth-of-type' || name === 'nth-last-of-type',
      };
    }
    throw new Error(`不支持的 CSS 选择器伪类：:${rawName}(...)`);
  }

  readBalancedParenArg() {
    const start = this.pos;
    let depth = 1;
    while (!this.eof()) {
      const c = this.src[this.pos];
      if (c === '(') depth += 1;
      else if (c === ')') {
        depth -= 1;
        if (depth === 0) {
          const content = this.src.slice(start, this.pos);
          this.pos += 1;
          return content;
        }
      }
      this.pos += 1;
    }
    throw new Error(`CSS 伪类缺少右括号：'${this.src}'`);
  }

  readIdent() {
    let out = '';
    while (!this.eof()) {
      const c = this.src[this.pos];
      if (c === '\\' && this.pos + 1 < this.src.length) {
        this.pos += 1;
        out += this.src[this.pos++];
      } else if (this.isIdentChar(c)) {
        out += c;
        this.pos += 1;
      } else {
        break;
      }
    }
    if (!out) throw new Error(`无效的 CSS 标识符：'${this.src}'（位置 ${this.pos}）`);
    return out;
  }

  readQuotedString() {
    const q = this.src[this.pos++];
    let out = '';
    while (!this.eof()) {
      const c = this.src[this.pos++];
      if (c === '\\' && !this.eof()) out += this.src[this.pos++];
      else if (c === q) return out;
      else out += c;
    }
    throw new Error(`CSS 选择器引号未闭合：'${this.src}'`);
  }

  readUnquotedAttrValue() {
    let out = '';
    while (!this.eof()) {
      const c = this.src[this.pos];
      if (c === ']' || /\s/.test(c)) break;
      if (c === '\\' && this.pos + 1 < this.src.length) {
        this.pos += 1;
        out += this.src[this.pos++];
      } else {
        out += c;
        this.pos += 1;
      }
    }
    return out;
  }

  isIdentStart(c) {
    if (!c) return false;
    const k = c.charCodeAt(0);
    return (k >= 97 && k <= 122) || (k >= 65 && k <= 90) || c === '_' || c === '-' || c === '\\' || k >= 0x80;
  }

  isIdentChar(c) {
    if (!c) return false;
    const k = c.charCodeAt(0);
    return this.isIdentStart(c) || (k >= 48 && k <= 57);
  }

  skipWs() {
    const start = this.pos;
    while (!this.eof() && /\s/.test(this.src[this.pos])) this.pos += 1;
    return this.pos > start;
  }

  eof() { return this.pos >= this.src.length; }
  peek() { return this.pos < this.src.length ? this.src[this.pos] : ''; }
}

function parseAnPlusB(raw) {
  const s = String(raw).replace(/\s+/g, '').toLowerCase();
  if (s === 'odd') return { a: 2, b: 1 };
  if (s === 'even') return { a: 2, b: 0 };
  const nIdx = s.indexOf('n');
  if (nIdx < 0) {
    if (!/^[+-]?\d+$/.test(s)) throw new Error(`无效的 :nth-* 参数：${raw}`);
    return { a: 0, b: Number(s) };
  }
  const aPart = s.slice(0, nIdx);
  let a;
  if (aPart === '' || aPart === '+') a = 1;
  else if (aPart === '-') a = -1;
  else if (/^[+-]?\d+$/.test(aPart)) a = Number(aPart);
  else throw new Error(`无效的 :nth-* 参数：${raw}`);

  const bPart = s.slice(nIdx + 1);
  let b = 0;
  if (bPart !== '') {
    if (!/^[+-]\d+$/.test(bPart)) throw new Error(`无效的 :nth-* 参数：${raw}`);
    b = Number(bPart);
  }
  return { a, b };
}

function matchesTag(actualRaw, nsPrefix, wantedTag, isXml) {
  const actual = isXml ? String(actualRaw || '') : String(actualRaw || '').toLowerCase();
  const wanted = wantedTag === null ? null : (isXml ? wantedTag : wantedTag.toLowerCase());
  const ns = nsPrefix === null ? null : (isXml ? nsPrefix : nsPrefix.toLowerCase());
  if (ns === null) {
    if (wanted === null) return true;
    return actual === wanted;
  }
  const colonIdx = actual.indexOf(':');
  const actualNs = colonIdx >= 0 ? actual.slice(0, colonIdx) : '';
  const actualLocal = colonIdx >= 0 ? actual.slice(colonIdx + 1) : actual;
  const nsMatches = ns === '*' ? true : (ns === '' ? colonIdx < 0 : actualNs === ns);
  if (!nsMatches) return false;
  return wanted === null || actualLocal === wanted;
}

function matchesAttr(el, am, isXml) {
  const actual = getElementAttr(el, am.name, isXml);
  if (actual === null) return false;
  if (am.op === 'exists') return true;
  const expected = am.value ?? '';
  const ci = am.caseFlag === 'i';
  const a = ci ? actual.toLowerCase() : actual;
  const e = ci ? expected.toLowerCase() : expected;
  switch (am.op) {
    case '=': return a === e;
    case '~=': return e.length > 0 && !/\s/.test(e) && a.trim().split(/[ \t\n\r\f]+/).includes(e);
    case '|=': return a === e || a.startsWith(`${e}-`);
    case '^=': return e.length > 0 && a.startsWith(e);
    case '$=': return e.length > 0 && a.endsWith(e);
    case '*=': return e.length > 0 && a.includes(e);
    default: return false;
  }
}

function sameTypeSiblings(el, isXml) {
  const parent = el.parentNode;
  if (!parent) return null;
  const myTag = isXml ? el.tagName : String(el.tagName || '').toLowerCase();
  return getElementChildren(parent).filter((s) => {
    const t = isXml ? s.tagName : String(s.tagName || '').toLowerCase();
    return t === myTag;
  });
}

function childIndex(el, fromEnd, ofType, isXml) {
  const parent = el.parentNode;
  if (!parent) return -1;
  const list = ofType ? sameTypeSiblings(el, isXml) : getElementChildren(parent);
  if (!list) return -1;
  const pos = list.indexOf(el);
  if (pos < 0) return -1;
  return fromEnd ? list.length - pos : pos + 1;
}

function matchesAnPlusB(idx, a, b) {
  if (a === 0) return idx === b;
  const diff = idx - b;
  if (diff % a !== 0) return false;
  return (diff / a) >= 0;
}

function matchesPseudo(el, pm, isXml) {
  switch (pm.kind) {
    case 'first-child': return childIndex(el, false, false, isXml) === 1;
    case 'last-child': return childIndex(el, true, false, isXml) === 1;
    case 'only-child': {
      const parent = el.parentNode;
      return Boolean(parent) && getElementChildren(parent).length === 1;
    }
    case 'first-of-type': return childIndex(el, false, true, isXml) === 1;
    case 'last-of-type': return childIndex(el, true, true, isXml) === 1;
    case 'only-of-type': {
      const list = sameTypeSiblings(el, isXml);
      return Boolean(list) && list.length === 1;
    }
    case 'empty':
      for (const n of el.childNodes || []) {
        if (n.nodeType === 1) return false;
        if ((n.nodeType === 3 || n.nodeType === 4) && (n.nodeValue || '').length > 0) return false;
      }
      return true;
    case 'root':
      return el.parentNode && el.parentNode.nodeType === 9;
    case 'nth': {
      const idx = childIndex(el, pm.fromEnd, pm.ofType, isXml);
      return idx > 0 && matchesAnPlusB(idx, pm.a, pm.b);
    }
    case 'not':
      return !matchesAny(el, pm.inner, isXml);
    case 'is':
      return matchesAny(el, pm.inner, isXml);
    default:
      return false;
  }
}

function matchesCompound(el, comp, isXml) {
  if (!el || el.nodeType !== 1) return false;
  if (comp.nsPrefix !== null || comp.tagName !== null) {
    if (!matchesTag(el.tagName, comp.nsPrefix, comp.tagName, isXml)) return false;
  }
  for (const id of comp.ids) {
    if (getElementAttr(el, 'id', isXml) !== id) return false;
  }
  if (comp.classes.length > 0) {
    const cls = getElementAttr(el, 'class', isXml);
    if (cls === null) return false;
    const tokens = cls.trim().split(/[ \t\n\r\f]+/).filter(Boolean);
    for (const c of comp.classes) {
      if (!tokens.includes(c)) return false;
    }
  }
  for (const am of comp.attrs) {
    if (!matchesAttr(el, am, isXml)) return false;
  }
  for (const pm of comp.pseudos) {
    if (!matchesPseudo(el, pm, isXml)) return false;
  }
  return true;
}

function matchesStep(el, compounds, combinators, idx, isXml) {
  if (!matchesCompound(el, compounds[idx], isXml)) return false;
  if (idx === 0) return true;
  const comb = combinators[idx - 1];
  if (comb === '>') {
    const p = getParentElement(el);
    return p !== null && matchesStep(p, compounds, combinators, idx - 1, isXml);
  }
  if (comb === ' ') {
    let p = getParentElement(el);
    while (p) {
      if (matchesStep(p, compounds, combinators, idx - 1, isXml)) return true;
      p = getParentElement(p);
    }
    return false;
  }
  if (comb === '+') {
    const prev = getPrevElementSibling(el);
    return prev !== null && matchesStep(prev, compounds, combinators, idx - 1, isXml);
  }
  if (comb === '~') {
    let prev = getPrevElementSibling(el);
    while (prev) {
      if (matchesStep(prev, compounds, combinators, idx - 1, isXml)) return true;
      prev = getPrevElementSibling(prev);
    }
    return false;
  }
  return false;
}

function matchesComplex(el, sel, isXml) {
  const compounds = [sel.head];
  const combinators = [];
  for (const [comb, comp] of sel.steps) {
    combinators.push(comb);
    compounds.push(comp);
  }
  return matchesStep(el, compounds, combinators, compounds.length - 1, isXml);
}

function matchesAny(el, selectors, isXml) {
  for (const s of selectors) {
    if (matchesComplex(el, s, isXml)) return true;
  }
  return false;
}

function queryDescendants(scopeRoot, selectors, isXml, firstOnly) {
  const out = [];
  const stack = [];
  const top = getElementChildren(scopeRoot);
  for (let i = top.length - 1; i >= 0; i--) stack.push(top[i]);
  while (stack.length > 0) {
    const el = stack.pop();
    if (matchesAny(el, selectors, isXml)) {
      if (firstOnly) return el;
      out.push(el);
    }
    const ch = getElementChildren(el);
    for (let i = ch.length - 1; i >= 0; i--) stack.push(ch[i]);
  }
  return firstOnly ? null : out;
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
    const parsed = new SelectorParser(selector).parseSelectorList(true);
    const found = queryDescendants(entry.el, parsed, entry.isXml, true);
    if (!found) return -1;
    return this._register(found, entry.isXml, false);
  }

  selectAllIds(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const parsed = new SelectorParser(selector).parseSelectorList(true);
    const list = queryDescendants(entry.el, parsed, entry.isXml, false);
    return list.map((el) => this._register(el, entry.isXml, false));
  }

  childrenIds(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = getElementChildren(entry.el);
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
    const parts = [];
    if (entry.isXml) {
      this._collectXmlText(entry.el, parts);
    } else {
      this._collectHtmlText(entry.el, parts);
    }
    return collapseAsciiAndNbspWhitespace(parts.join(''));
  }

  html(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    if (entry.isDocument) {
      return getElementChildren(entry.el).map((c) => c.outerHTML || '').join('');
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
    if (!entry || entry.isDocument) return null;
    return getElementAttr(entry.el, String(name), entry.isXml);
  }

  attrsJson(nodeId) {
    const entry = this.nodes.get(nodeId);
    const attrs = {};
    if (entry && !entry.isDocument && entry.el?.attributes) {
      for (const a of Array.from(entry.el.attributes)) {
        const key = entry.isXml ? a.name : String(a.name).toLowerCase();
        attrs[key] = a.value;
      }
    }
    return JSON.stringify(attrs);
  }

  clear() {
    this.nodes.clear();
    this.docCount = 0;
    this.totalNodes = 0;
  }

  _collectXmlText(node, parts) {
    if (!node) return;
    if (node.nodeType === 3 || node.nodeType === 4) {
      parts.push(node.nodeValue || '');
      return;
    }
    if (node.nodeType !== 1 && node.nodeType !== 9) return;
    for (const child of node.childNodes || []) {
      this._collectXmlText(child, parts);
    }
  }

  _collectHtmlText(node, parts) {
    if (!node) return;
    if (node.nodeType === 3 || node.nodeType === 4) {
      parts.push(node.nodeValue || '');
      return;
    }
    if (node.nodeType === 9) {
      for (const child of node.childNodes || []) {
        this._collectHtmlText(child, parts);
      }
      return;
    }
    if (node.nodeType !== 1) return;
    const tag = String(node.tagName || '').toLowerCase();
    if (HTML_SKIP_TEXT_TAGS.has(tag)) return;
    const isBlock = HTML_BLOCK_TAGS.has(tag);
    if (isBlock) parts.push(' ');
    for (const child of node.childNodes || []) {
      this._collectHtmlText(child, parts);
    }
    if (isBlock) parts.push(' ');
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
      if (text.charCodeAt(i) === 60) {
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
      const children = getElementChildren(cur);
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
