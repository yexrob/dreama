import { DOMParser } from 'linkedom';
import * as parse5 from 'parse5';

/** §11.1 (S4): max 8 parsed documents per call, 4 MB per doc, 200k total element nodes (excluding #document). */
export const MAX_DOCS_PER_CALL = 8;
export const MAX_DOC_BYTES = 4 * 1024 * 1024;
export const MAX_TOTAL_NODES = 200_000;
export const MAX_SELECTOR_STEPS = 2_000_000;
const MAX_PSEUDO_NESTING = 8;

const MEMO_UNCOMPUTED = 0;
const MEMO_FALSE = 1;
const MEMO_TRUE = 2;

export class DomTimeoutError extends Error {
  constructor(message = 'CSS 选择器执行超时') {
    super(message);
    this.name = 'DomTimeoutError';
  }
}

const HTML_SKIP_TEXT_TAGS = new Set(['script', 'style']);
const HTML_RAW_TEXT_TAGS = new Set(['script', 'style', 'xmp', 'iframe', 'noembed', 'noframes', 'plaintext']);
const HTML_VOID_TAGS = new Set([
  'area', 'base', 'basefont', 'bgsound', 'br', 'col', 'embed', 'frame',
  'hr', 'img', 'input', 'keygen', 'link', 'meta', 'param', 'source', 'track', 'wbr',
]);
const HTML_BLOCK_TAGS = new Set([
  'address', 'article', 'aside', 'blockquote', 'body', 'br', 'caption', 'center',
  'col', 'colgroup', 'dd', 'details', 'dialog', 'dir', 'div', 'dl', 'dt',
  'fieldset', 'figcaption', 'figure', 'footer', 'form', 'frame', 'frameset',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'head', 'header', 'hgroup', 'hr', 'html',
  'li', 'main', 'menu', 'nav', 'noframes', 'noscript', 'ol', 'optgroup', 'option',
  'p', 'pre', 'section', 'select', 'table', 'tbody', 'td', 'tfoot', 'th',
  'thead', 'title', 'tr', 'ul', 'video',
]);

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

// Unified node accessors supporting BOTH parse5 HTML5 AST nodes and linkedom XML nodes
function isDocumentNode(node) {
  return Boolean(node) && (node.nodeName === '#document' || node.nodeType === 9);
}

function isElementNode(node) {
  if (!node) return false;
  if (node.nodeType !== undefined) return node.nodeType === 1;
  return typeof node.tagName === 'string';
}

function isTextNode(node) {
  if (!node) return false;
  if (node.nodeType !== undefined) return node.nodeType === 3;
  return node.nodeName === '#text';
}

function isCDataNode(node) {
  return Boolean(node) && node.nodeType === 4;
}

function isCommentNode(node) {
  if (!node) return false;
  if (node.nodeType !== undefined) return node.nodeType === 8;
  return node.nodeName === '#comment';
}

function getNodeTextValue(node) {
  if (node.value !== undefined) return String(node.value);
  return String(node.nodeValue ?? '');
}

function getNodeCommentData(node) {
  if (node.data !== undefined) return String(node.data);
  return String(node.nodeValue ?? '');
}

function getRawTagName(node) {
  return String(node?.tagName ?? '');
}

function getChildNodes(node) {
  if (!node) return [];
  return node.childNodes || [];
}

function getElementChildren(node) {
  const out = [];
  for (const c of getChildNodes(node)) {
    if (isElementNode(c)) out.push(c);
  }
  return out;
}

function getParentNode(node) {
  return node?.parentNode ?? null;
}

function getParentElement(node) {
  const p = getParentNode(node);
  return isElementNode(p) ? p : null;
}

function getElementAttributes(el) {
  if (!el || isDocumentNode(el)) return [];
  if (Array.isArray(el.attrs)) return el.attrs;
  if (el.attributes) return Array.from(el.attributes);
  return [];
}

function getElementAttr(el, name, isXml) {
  if (!el || isDocumentNode(el)) return null;
  const attrs = getElementAttributes(el);
  if (isXml) {
    for (const a of attrs) {
      if (a.name === name) return a.value ?? '';
    }
    return null;
  }
  const lower = String(name).toLowerCase();
  for (const a of attrs) {
    if (String(a.name).toLowerCase() === lower) return a.value ?? '';
  }
  return null;
}

// Canonical HTML/XML serializer matching Android PluginDomParser.kt bit-for-bit
function escapeHtmlText(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '&') out += '&amp;';
    else if (c === '\u00A0') out += '&nbsp;';
    else if (c === '<') out += '&lt;';
    else if (c === '>') out += '&gt;';
    else out += c;
  }
  return out;
}

function escapeHtmlAttr(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '&') out += '&amp;';
    else if (c === '\u00A0') out += '&nbsp;';
    else if (c === '"') out += '&quot;';
    else out += c;
  }
  return out;
}

function escapeXmlText(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '&') out += '&amp;';
    else if (c === '<') out += '&lt;';
    else if (c === '>') out += '&gt;';
    else out += c;
  }
  return out;
}

function escapeXmlAttr(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '&') out += '&amp;';
    else if (c === '<') out += '&lt;';
    else if (c === '>') out += '&gt;';
    else if (c === '"') out += '&quot;';
    else out += c;
  }
  return out;
}

function serializeChildren(node, isXml, parts) {
  const rawParent = !isXml && isElementNode(node) && HTML_RAW_TEXT_TAGS.has(getRawTagName(node).toLowerCase());
  for (const child of getChildNodes(node)) {
    if (rawParent && isTextNode(child)) {
      parts.push(getNodeTextValue(child));
    } else {
      serializeNode(child, isXml, parts);
    }
  }
}

function serializeNode(node, isXml, parts) {
  if (isCDataNode(node)) {
    const data = getNodeTextValue(node);
    parts.push(isXml ? `<![CDATA[${data}]]>` : escapeHtmlText(data));
    return;
  }
  if (isTextNode(node)) {
    const data = getNodeTextValue(node);
    parts.push(isXml ? escapeXmlText(data) : escapeHtmlText(data));
    return;
  }
  if (isCommentNode(node)) {
    parts.push(`<!--${getNodeCommentData(node)}-->`);
    return;
  }
  if (isElementNode(node)) {
    const tag = isXml ? getRawTagName(node) : getRawTagName(node).toLowerCase();
    parts.push(`<${tag}`);
    for (const a of getElementAttributes(node)) {
      const key = isXml ? a.name : String(a.name).toLowerCase();
      const v = a.value ?? '';
      if (!isXml && v === '') {
        parts.push(` ${key}`);
      } else {
        parts.push(` ${key}="${isXml ? escapeXmlAttr(v) : escapeHtmlAttr(v)}"`);
      }
    }
    if (isXml) {
      const ch = getChildNodes(node);
      if (ch.length === 0) {
        parts.push('/>');
      } else {
        parts.push('>');
        serializeChildren(node, true, parts);
        parts.push(`</${tag}>`);
      }
    } else {
      parts.push('>');
      if (!HTML_VOID_TAGS.has(tag)) {
        serializeChildren(node, false, parts);
        parts.push(`</${tag}>`);
      }
    }
  }
}

/**
 * Shared-subset CSS selector parser & memoized O(N * S) DP evaluator
 * mirroring Android's `CssSelectorEngine` in `PluginDomParser.kt` identically.
 */
class SelectorParser {
  constructor(src, nestingDepth = 0) {
    this.src = String(src ?? '');
    this.pos = 0;
    this.nestingDepth = nestingDepth;
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
    const compounds = [this.parseCompoundSelector()];
    const combinators = [];
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
      combinators.push(comb);
      compounds.push(this.parseCompoundSelector());
    }
    return { compounds, combinators };
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
    this.pos += 1;
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
    this.pos += 1;
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
    this.pos += 1;
    if (name === 'not' || name === 'is') {
      if (this.nestingDepth >= MAX_PSEUDO_NESTING) {
        throw new Error(`CSS 伪类嵌套层数超过上限（最多 ${MAX_PSEUDO_NESTING} 层）`);
      }
      const sub = new SelectorParser(this.src, this.nestingDepth + 1);
      sub.pos = this.pos;
      const inner = sub.parseSelectorList(false);
      this.pos = sub.pos;
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

function matchesAnPlusB(idx, a, b) {
  if (a === 0) return idx === b;
  const diff = idx - b;
  if (diff % a !== 0) return false;
  return (diff / a) >= 0;
}

class QueryContext {
  constructor(isXml, maxSteps, deadlineMs) {
    this.isXml = isXml;
    this.maxSteps = maxSteps;
    this.deadlineMs = deadlineMs;
    this.steps = 0;
    this.memos = new Map();
    this.siblingTables = new Map();
  }

  tick() {
    this.steps += 1;
    if (this.steps > this.maxSteps) {
      throw new Error(`CSS 选择器匹配步数超过上限（最多 ${this.maxSteps} 步）`);
    }
    if ((this.steps & 1023) === 0 && this.deadlineMs !== Infinity && Date.now() > this.deadlineMs) {
      throw new DomTimeoutError('CSS 选择器执行超时');
    }
  }

  memoFor(sel) {
    let m = this.memos.get(sel);
    if (!m) {
      m = {
        stepCount: sel.compounds.length,
        matchStep: new Map(),
        anyAncestor: new Map(),
        anyPrevSibling: new Map(),
      };
      this.memos.set(sel, m);
    }
    return m;
  }

  getRow(map, el, stepCount) {
    let row = map.get(el);
    if (!row) {
      row = new Uint8Array(stepCount);
      map.set(el, row);
    }
    return row;
  }

  siblingTableFor(parent) {
    let table = this.siblingTables.get(parent);
    if (table) return table;
    const children = getElementChildren(parent);
    const n = children.length;
    const childIndex1Based = new Map();
    const typeIndex1Based = new Map();
    const typeTotal = new Map();
    const prevSibling = new Map();
    const typeCounts = new Map();
    const tags = new Array(n);
    let prev = null;
    for (let i = 0; i < n; i++) {
      const c = children[i];
      this.tick();
      childIndex1Based.set(c, i + 1);
      prevSibling.set(c, prev);
      prev = c;
      const tag = this.isXml ? getRawTagName(c) : getRawTagName(c).toLowerCase();
      const cnt = (typeCounts.get(tag) || 0) + 1;
      typeCounts.set(tag, cnt);
      typeIndex1Based.set(c, cnt);
      tags[i] = tag;
    }
    for (let i = 0; i < n; i++) {
      typeTotal.set(children[i], typeCounts.get(tags[i]));
    }
    table = { totalChildren: n, childIndex1Based, typeIndex1Based, typeTotal, prevSibling };
    this.siblingTables.set(parent, table);
    return table;
  }

  prevElementSiblingFast(el) {
    const p = getParentNode(el);
    if (!p) return null;
    return this.siblingTableFor(p).prevSibling.get(el) ?? null;
  }

  matchesAny(el, selectors) {
    for (const sel of selectors) {
      if (this.evalMatchStep(el, sel, sel.compounds.length - 1)) return true;
    }
    return false;
  }

  evalMatchStep(el, sel, idx) {
    const memo = this.memoFor(sel);
    const row = this.getRow(memo.matchStep, el, memo.stepCount);
    const cached = row[idx];
    if (cached !== MEMO_UNCOMPUTED) return cached === MEMO_TRUE;

    this.tick();
    let result = false;
    if (this.matchesCompound(el, sel.compounds[idx])) {
      if (idx === 0) {
        result = true;
      } else {
        const comb = sel.combinators[idx - 1];
        if (comb === '>') {
          const p = getParentElement(el);
          result = p !== null && this.evalMatchStep(p, sel, idx - 1);
        } else if (comb === ' ') {
          result = this.evalAnyAncestorMatches(el, sel, idx - 1);
        } else if (comb === '+') {
          const prev = this.prevElementSiblingFast(el);
          result = prev !== null && this.evalMatchStep(prev, sel, idx - 1);
        } else if (comb === '~') {
          result = this.evalAnyPrevSiblingMatches(el, sel, idx - 1);
        }
      }
    }
    row[idx] = result ? MEMO_TRUE : MEMO_FALSE;
    return result;
  }

  evalAnyAncestorMatches(el, sel, idx) {
    const memo = this.memoFor(sel);
    const chain = [];
    let cur = el;
    let baseResult = false;
    while (true) {
      const ancRow = this.getRow(memo.anyAncestor, cur, memo.stepCount);
      const cached = ancRow[idx];
      if (cached !== MEMO_UNCOMPUTED) {
        baseResult = cached === MEMO_TRUE;
        break;
      }
      this.tick();
      const p = getParentElement(cur);
      if (!p) {
        ancRow[idx] = MEMO_FALSE;
        baseResult = false;
        break;
      }
      if (this.evalMatchStep(p, sel, idx)) {
        ancRow[idx] = MEMO_TRUE;
        baseResult = true;
        break;
      }
      chain.push(cur);
      cur = p;
    }
    const fill = baseResult ? MEMO_TRUE : MEMO_FALSE;
    for (let i = chain.length - 1; i >= 0; i--) {
      this.getRow(memo.anyAncestor, chain[i], memo.stepCount)[idx] = fill;
    }
    return baseResult;
  }

  evalAnyPrevSiblingMatches(el, sel, idx) {
    const memo = this.memoFor(sel);
    const chain = [];
    let cur = el;
    let baseResult = false;
    while (true) {
      const sibRow = this.getRow(memo.anyPrevSibling, cur, memo.stepCount);
      const cached = sibRow[idx];
      if (cached !== MEMO_UNCOMPUTED) {
        baseResult = cached === MEMO_TRUE;
        break;
      }
      this.tick();
      const prev = this.prevElementSiblingFast(cur);
      if (!prev) {
        sibRow[idx] = MEMO_FALSE;
        baseResult = false;
        break;
      }
      if (this.evalMatchStep(prev, sel, idx)) {
        sibRow[idx] = MEMO_TRUE;
        baseResult = true;
        break;
      }
      chain.push(cur);
      cur = prev;
    }
    const fill = baseResult ? MEMO_TRUE : MEMO_FALSE;
    for (let i = chain.length - 1; i >= 0; i--) {
      this.getRow(memo.anyPrevSibling, chain[i], memo.stepCount)[idx] = fill;
    }
    return baseResult;
  }

  matchesCompound(el, comp) {
    if (!isElementNode(el)) return false;
    if (comp.nsPrefix !== null || comp.tagName !== null) {
      if (!matchesTag(getRawTagName(el), comp.nsPrefix, comp.tagName, this.isXml)) return false;
    }
    for (const id of comp.ids) {
      if (getElementAttr(el, 'id', this.isXml) !== id) return false;
    }
    if (comp.classes.length > 0) {
      const cls = getElementAttr(el, 'class', this.isXml);
      if (cls === null) return false;
      const tokens = cls.trim().split(/[ \t\n\r\f]+/).filter(Boolean);
      for (const c of comp.classes) {
        if (!tokens.includes(c)) return false;
      }
    }
    for (const am of comp.attrs) {
      if (!matchesAttr(el, am, this.isXml)) return false;
    }
    for (const pm of comp.pseudos) {
      if (!this.matchesPseudo(el, pm)) return false;
    }
    return true;
  }

  matchesPseudo(el, pm) {
    switch (pm.kind) {
      case 'first-child': {
        const p = getParentNode(el);
        return Boolean(p) && this.siblingTableFor(p).childIndex1Based.get(el) === 1;
      }
      case 'last-child': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        return t.childIndex1Based.get(el) === t.totalChildren;
      }
      case 'only-child': {
        const p = getParentNode(el);
        return Boolean(p) && this.siblingTableFor(p).totalChildren === 1;
      }
      case 'first-of-type': {
        const p = getParentNode(el);
        return Boolean(p) && this.siblingTableFor(p).typeIndex1Based.get(el) === 1;
      }
      case 'last-of-type': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        return t.typeIndex1Based.get(el) === t.typeTotal.get(el);
      }
      case 'only-of-type': {
        const p = getParentNode(el);
        return Boolean(p) && this.siblingTableFor(p).typeTotal.get(el) === 1;
      }
      case 'empty':
        for (const n of getChildNodes(el)) {
          if (isElementNode(n)) return false;
          if ((isTextNode(n) || isCDataNode(n)) && getNodeTextValue(n).length > 0) return false;
        }
        return true;
      case 'root':
        return isDocumentNode(getParentNode(el));
      case 'nth': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        let idx;
        if (pm.ofType) {
          const pos = t.typeIndex1Based.get(el);
          const tot = t.typeTotal.get(el);
          if (!pos || !tot) return false;
          idx = pm.fromEnd ? (tot - pos + 1) : pos;
        } else {
          const pos = t.childIndex1Based.get(el);
          if (!pos) return false;
          idx = pm.fromEnd ? (t.totalChildren - pos + 1) : pos;
        }
        return matchesAnPlusB(idx, pm.a, pm.b);
      }
      case 'not':
        return !this.matchesAny(el, pm.inner);
      case 'is':
        return this.matchesAny(el, pm.inner);
      default:
        return false;
    }
  }
}

function queryDescendants(scopeRoot, selectors, isXml, firstOnly, maxSteps, deadlineMs) {
  const ctx = new QueryContext(isXml, maxSteps, deadlineMs);
  const out = [];
  const stack = [];
  const top = getElementChildren(scopeRoot);
  for (let i = top.length - 1; i >= 0; i--) stack.push(top[i]);
  while (stack.length > 0) {
    ctx.tick();
    const el = stack.pop();
    if (ctx.matchesAny(el, selectors)) {
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
    maxSelectorSteps = MAX_SELECTOR_STEPS,
  ) {
    this.maxDocsPerCall = maxDocsPerCall;
    this.maxDocBytes = maxDocBytes;
    this.maxTotalNodes = maxTotalNodes;
    this.maxSelectorSteps = maxSelectorSteps;
    this.nextId = 1;
    this.docCount = 0;
    this.totalNodes = 0;
    this.nodes = new Map();
    this.deadlineMs = Infinity;
    this._xmlParser = new DOMParser();
  }

  setCallDeadlineMs(deadlineMs) {
    this.deadlineMs = deadlineMs;
  }

  parseHtml(html) {
    const text = String(html ?? '');
    this._checkPreParseLimits(text);
    // WHATWG HTML5 compliant tree construction matching Android Jsoup
    const document = parse5.parse(text);
    const count = this._normalizeAndCountElements(document, true);
    this.docCount += 1;
    this.totalNodes += count;
    return this._register(document, false, true);
  }

  parseXml(xml) {
    const text = String(xml ?? '');
    this._checkPreParseLimits(text);
    const document = this._xmlParser.parseFromString(text, 'text/xml');
    const count = this._normalizeAndCountElements(document, false);
    this.docCount += 1;
    this.totalNodes += count;
    return this._register(document, true, true);
  }

  selectFirstId(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return -1;
    const parsed = new SelectorParser(selector).parseSelectorList(true);
    const found = queryDescendants(entry.el, parsed, entry.isXml, true, this.maxSelectorSteps, this.deadlineMs);
    if (!found) return -1;
    return this._register(found, entry.isXml, false);
  }

  selectAllIds(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const parsed = new SelectorParser(selector).parseSelectorList(true);
    const list = queryDescendants(entry.el, parsed, entry.isXml, false, this.maxSelectorSteps, this.deadlineMs);
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
    const raw = getRawTagName(entry.el);
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
    const parts = [];
    serializeChildren(entry.el, entry.isXml, parts);
    return parts.join('');
  }

  outerHtml(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    const parts = [];
    if (entry.isDocument) {
      serializeChildren(entry.el, entry.isXml, parts);
    } else {
      serializeNode(entry.el, entry.isXml, parts);
    }
    return parts.join('');
  }

  attr(nodeId, name) {
    const entry = this.nodes.get(nodeId);
    if (!entry || entry.isDocument) return null;
    return getElementAttr(entry.el, String(name), entry.isXml);
  }

  attrsJson(nodeId) {
    const entry = this.nodes.get(nodeId);
    const attrs = {};
    if (entry && !entry.isDocument) {
      for (const a of getElementAttributes(entry.el)) {
        const key = entry.isXml ? a.name : String(a.name).toLowerCase();
        attrs[key] = a.value ?? '';
      }
    }
    return JSON.stringify(attrs);
  }

  clear() {
    this.nodes.clear();
    this.docCount = 0;
    this.totalNodes = 0;
    this.deadlineMs = Infinity;
  }

  _collectXmlText(node, parts) {
    if (!node) return;
    if (isTextNode(node) || isCDataNode(node)) {
      parts.push(getNodeTextValue(node));
      return;
    }
    if (!isElementNode(node) && !isDocumentNode(node)) return;
    for (const child of getChildNodes(node)) {
      this._collectXmlText(child, parts);
    }
  }

  _collectHtmlText(node, parts) {
    if (!node) return;
    if (isTextNode(node) || isCDataNode(node)) {
      parts.push(getNodeTextValue(node));
      return;
    }
    if (isDocumentNode(node)) {
      for (const child of getChildNodes(node)) {
        this._collectHtmlText(child, parts);
      }
      return;
    }
    if (!isElementNode(node)) return;
    const tag = getRawTagName(node).toLowerCase();
    if (HTML_SKIP_TEXT_TAGS.has(tag)) return;
    const isBlock = HTML_BLOCK_TAGS.has(tag);
    if (isBlock) parts.push(' ');
    for (const child of getChildNodes(node)) {
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

  /**
   * Counts strictly Element nodes (excluding `#document` itself, matching Android
   * `PluginDomParser.kt`), and in parse5 HTML trees flattens `<template>`'s
   * `.content.childNodes` directly onto the `<template>` node so `<template>`
   * traversal matches Jsoup.
   */
  _normalizeAndCountElements(doc, isParse5Html) {
    const remaining = this.maxTotalNodes - this.totalNodes;
    let count = 0;
    const stack = [];
    const top = getElementChildren(doc);
    for (let i = top.length - 1; i >= 0; i--) stack.push(top[i]);
    while (stack.length > 0) {
      const cur = stack.pop();
      count += 1;
      if (count > remaining) {
        throw new Error(`单次调用解析的 DOM 节点总数超过上限（最多 ${this.maxTotalNodes} 个）`);
      }
      if (isParse5Html && cur.content && Array.isArray(cur.content.childNodes) && cur.content.childNodes.length > 0) {
        const tmplChildren = cur.content.childNodes;
        for (const tc of tmplChildren) {
          tc.parentNode = cur;
        }
        cur.childNodes = tmplChildren;
        cur.content = null;
      }
      const ch = getElementChildren(cur);
      for (let i = ch.length - 1; i >= 0; i--) stack.push(ch[i]);
    }
    return count;
  }

  _register(el, isXml, isDocument) {
    const id = this.nextId++;
    this.nodes.set(id, { el, isXml, isDocument });
    return id;
  }
}
