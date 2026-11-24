import { DOMParser } from 'linkedom';
import * as parse5 from 'parse5';

/**
 * §11.1 (S4):
 * - Max 8 simultaneously live documents per VM, 4 MB per doc
 * - Max 200,000 elements per doc, 400,000 live elements across VM (excluding #document)
 * - Per-document deduplicated node handle registry + explicit doc.close()
 * - Hard selector caps: 4 KB text, 32 groups, 32 compounds, nesting 4, value 256,
 *   :nth-* in [-1_000_000, +1_000_000], 2M step budget, 16 MB query memory budget
 */
export const MAX_LIVE_DOCS = 8;
export const MAX_DOCS_PER_CALL = MAX_LIVE_DOCS;
export const MAX_DOC_BYTES = 4 * 1024 * 1024;
export const MAX_DOC_NODES = 200_000;
export const MAX_TOTAL_NODES = MAX_DOC_NODES;
export const MAX_LIVE_NODES = 400_000;
export const MAX_SELECTOR_STEPS = 2_000_000;
export const MAX_SELECTOR_MEMORY_BYTES = 16 * 1024 * 1024;
export const MAX_SELECTOR_BYTES = 4 * 1024;
export const MAX_SELECTOR_GROUPS = 32;
export const MAX_COMPOUNDS_PER_SELECTOR = 32;
export const MAX_PSEUDO_NESTING = 4;
export const MAX_SELECTOR_VALUE_LENGTH = 256;
export const MAX_NTH_ABS = 1_000_000;

const MEMO_UNCOMPUTED = 0;
const MEMO_FALSE = 1;
const MEMO_TRUE = 2;

export class DomLimitExceededError extends Error {
  constructor(message = 'DOM 资源超限') {
    super(message);
    this.name = 'DomLimitExceededError';
  }
}

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

function checkDeadline(deadlineMs) {
  if (deadlineMs !== Infinity && Date.now() > deadlineMs) {
    throw new DomTimeoutError('DOM 操作执行超时');
  }
}

function isAsciiWsCode(code) {
  return code === 0x20 || code === 0x09 || code === 0x0A || code === 0x0D || code === 0x0C;
}

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

// Iterative (O(1) call stack) canonical HTML/XML serializer matching Android PluginDomParser.kt bit-for-bit
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

function pushSerializedChildren(node, isXml, stack) {
  const rawParent = !isXml && isElementNode(node) && HTML_RAW_TEXT_TAGS.has(getRawTagName(node).toLowerCase());
  const ch = getChildNodes(node);
  for (let i = ch.length - 1; i >= 0; i--) {
    const child = ch[i];
    if (rawParent && isTextNode(child)) {
      stack.push({ __rawText: getNodeTextValue(child) });
    } else {
      stack.push(child);
    }
  }
}

function drainSerializeStack(stack, isXml, parts, deadlineMs) {
  let ops = 0;
  while (stack.length > 0) {
    if ((++ops & 1023) === 0) checkDeadline(deadlineMs);
    const item = stack.pop();
    if (typeof item === 'string') {
      parts.push(item);
      continue;
    }
    if (item && typeof item.__rawText === 'string') {
      parts.push(item.__rawText);
      continue;
    }
    if (isCDataNode(item)) {
      const data = getNodeTextValue(item);
      parts.push(isXml ? `<![CDATA[${data}]]>` : escapeHtmlText(data));
      continue;
    }
    if (isTextNode(item)) {
      const data = getNodeTextValue(item);
      parts.push(isXml ? escapeXmlText(data) : escapeHtmlText(data));
      continue;
    }
    if (isCommentNode(item)) {
      parts.push(`<!--${getNodeCommentData(item)}-->`);
      continue;
    }
    if (isElementNode(item)) {
      const tag = isXml ? getRawTagName(item) : getRawTagName(item).toLowerCase();
      parts.push(`<${tag}`);
      for (const a of getElementAttributes(item)) {
        const key = isXml ? a.name : String(a.name).toLowerCase();
        const v = a.value ?? '';
        if (!isXml && v === '') {
          parts.push(` ${key}`);
        } else {
          parts.push(` ${key}="${isXml ? escapeXmlAttr(v) : escapeHtmlAttr(v)}"`);
        }
      }
      if (isXml) {
        const ch = getChildNodes(item);
        if (ch.length === 0) {
          parts.push('/>');
        } else {
          parts.push('>');
          stack.push(`</${tag}>`);
          pushSerializedChildren(item, true, stack);
        }
      } else {
        parts.push('>');
        if (!HTML_VOID_TAGS.has(tag)) {
          stack.push(`</${tag}>`);
          pushSerializedChildren(item, false, stack);
        }
      }
    }
  }
}

function serializeChildrenIterative(node, isXml, parts, deadlineMs) {
  const stack = [];
  pushSerializedChildren(node, isXml, stack);
  drainSerializeStack(stack, isXml, parts, deadlineMs);
}

function serializeNodeIterative(node, isXml, parts, deadlineMs) {
  const stack = [node];
  drainSerializeStack(stack, isXml, parts, deadlineMs);
}

function charsEqual(a, b, ci) {
  if (a === b) return true;
  if (!ci) return false;
  let ca = a.charCodeAt(0);
  let cb = b.charCodeAt(0);
  if (ca >= 65 && ca <= 90) ca += 32;
  if (cb >= 65 && cb <= 90) cb += 32;
  if (ca === cb) return true;
  if (ca >= 128 || cb >= 128) return a.toLowerCase() === b.toLowerCase();
  return false;
}

function buildKmpLps(pattern, ci) {
  const m = pattern.length;
  const lps = new Int32Array(m);
  let len = 0;
  let i = 1;
  while (i < m) {
    if (charsEqual(pattern[i], pattern[len], ci)) {
      len += 1;
      lps[i] = len;
      i += 1;
    } else if (len !== 0) {
      len = lps[len - 1];
    } else {
      lps[i] = 0;
      i += 1;
    }
  }
  return lps;
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
      if (out.length >= MAX_SELECTOR_GROUPS) {
        throw new DomLimitExceededError(`CSS 选择器逗号分组数超过上限（最多 ${MAX_SELECTOR_GROUPS} 组）`);
      }
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
      if (compounds.length >= MAX_COMPOUNDS_PER_SELECTOR) {
        throw new DomLimitExceededError(`单个 CSS 选择器复合段数超过上限（最多 ${MAX_COMPOUNDS_PER_SELECTOR} 段）`);
      }
      combinators.push(comb);
      compounds.push(this.parseCompoundSelector());
    }
    return { compounds, combinators, isNested: this.nestingDepth > 0 };
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
      return { name, op: 'exists', value: null, caseFlag: null, kmpLps: null };
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
    const kmpLps = (op === '*=' && value.length > 0) ? buildKmpLps(value, caseFlag === 'i') : null;
    return { name, op, value, caseFlag, kmpLps };
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
        throw new DomLimitExceededError(`CSS 伪类嵌套层数超过上限（最多 ${MAX_PSEUDO_NESTING} 层）`);
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
      if (this.pos - start > MAX_SELECTOR_VALUE_LENGTH) {
        throw new DomLimitExceededError(`CSS 伪类参数超过长度上限（最多 ${MAX_SELECTOR_VALUE_LENGTH} 字符）`);
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
      if (out.length > MAX_SELECTOR_VALUE_LENGTH) {
        throw new DomLimitExceededError(`CSS 选择器标识符超过长度上限（最多 ${MAX_SELECTOR_VALUE_LENGTH} 字符）`);
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
      if (out.length > MAX_SELECTOR_VALUE_LENGTH) {
        throw new DomLimitExceededError(`CSS 选择器属性值超过长度上限（最多 ${MAX_SELECTOR_VALUE_LENGTH} 字符）`);
      }
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
      if (out.length > MAX_SELECTOR_VALUE_LENGTH) {
        throw new DomLimitExceededError(`CSS 选择器属性值超过长度上限（最多 ${MAX_SELECTOR_VALUE_LENGTH} 字符）`);
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

function parseBoundedNthInt(token, requireSign, raw) {
  const re = requireSign ? /^[+-]\d+$/ : /^[+-]?\d+$/;
  if (!re.test(token)) throw new Error(`无效的 :nth-* 参数：${raw}`);
  const val = Number(token);
  if (!Number.isSafeInteger(val) || val < -MAX_NTH_ABS || val > MAX_NTH_ABS) {
    throw new Error(`:nth-* 参数超出范围（允许 ±${MAX_NTH_ABS}）：${raw}`);
  }
  return val;
}

function parseAnPlusB(raw) {
  const s = String(raw).replace(/\s+/g, '').toLowerCase();
  if (s === 'odd') return { a: 2, b: 1 };
  if (s === 'even') return { a: 2, b: 0 };
  const nIdx = s.indexOf('n');
  if (nIdx < 0) {
    return { a: 0, b: parseBoundedNthInt(s, false, raw) };
  }
  const aPart = s.slice(0, nIdx);
  let a;
  if (aPart === '' || aPart === '+') a = 1;
  else if (aPart === '-') a = -1;
  else a = parseBoundedNthInt(aPart, false, raw);

  const bPart = s.slice(nIdx + 1);
  const b = bPart === '' ? 0 : parseBoundedNthInt(bPart, true, raw);
  return { a, b };
}

function parseSelector(rawSelector) {
  const s = String(rawSelector ?? '');
  if (s.length > MAX_SELECTOR_BYTES || Buffer.byteLength(s, 'utf8') > MAX_SELECTOR_BYTES) {
    throw new DomLimitExceededError(`CSS 选择器超过长度上限（最多 ${MAX_SELECTOR_BYTES} 字节）`);
  }
  return new SelectorParser(s, 0).parseSelectorList(true);
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

function matchesAnPlusB(idx, a, b) {
  if (a === 0) return idx === b;
  const diff = idx - b;
  if (diff % a !== 0) return false;
  return (diff / a) >= 0;
}

class QueryContext {
  constructor(isXml, maxSteps, maxMemoryBytes, deadlineMs) {
    this.isXml = isXml;
    this.maxSteps = maxSteps;
    this.maxMemoryBytes = maxMemoryBytes;
    this.deadlineMs = deadlineMs;
    this.steps = 0;
    this.memoryBytes = 0;
    this.memos = new Map();
    this.siblingTables = new Map();
    this.classTokenCache = new Map();
  }

  tick(cost = 1) {
    const prev = this.steps;
    const next = prev + cost;
    this.steps = next;
    if (next > this.maxSteps) {
      throw new DomLimitExceededError(`CSS 选择器匹配步数超过上限（最多 ${this.maxSteps} 步）`);
    }
    if ((prev >>> 10) !== (next >>> 10) && this.deadlineMs !== Infinity && Date.now() > this.deadlineMs) {
      throw new DomTimeoutError('CSS 选择器执行超时');
    }
  }

  chargeMemory(bytes) {
    if (bytes <= 0) return;
    this.memoryBytes += bytes;
    if (this.memoryBytes > this.maxMemoryBytes) {
      const mb = Math.max(1, Math.floor(this.maxMemoryBytes / (1024 * 1024)));
      throw new DomLimitExceededError(`CSS 选择器查询内存超过上限（最多 ${mb} MB）`);
    }
  }

  memoFor(sel) {
    let m = this.memos.get(sel);
    if (!m) {
      const stepCount = sel.compounds.length;
      m = {
        stepCount,
        matchRowLen: sel.isNested ? stepCount : Math.max(0, stepCount - 1),
        combRowLen: Math.max(0, stepCount - 1),
        matchStep: null,
        selfOrAncestor: null,
        anyPrevSibling: null,
      };
      this.memos.set(sel, m);
    }
    return m;
  }

  getCached(map, el, idx) {
    if (!map) return MEMO_UNCOMPUTED;
    const row = map.get(el);
    return row ? row[idx] : MEMO_UNCOMPUTED;
  }

  setCached(memo, field, rowLen, el, idx, value) {
    let map = memo[field];
    if (!map) {
      map = new Map();
      memo[field] = map;
    }
    let row = map.get(el);
    if (!row) {
      const allocBytes = 32 + Math.ceil(rowLen / 8) * 8;
      this.chargeMemory(allocBytes);
      row = new Uint8Array(rowLen);
      map.set(el, row);
    }
    row[idx] = value;
  }

  siblingTableFor(parent) {
    let table = this.siblingTables.get(parent);
    if (table) return table;
    const children = getElementChildren(parent);
    const n = children.length;
    this.chargeMemory(44 * n + 128);
    const index0Based = new Map();
    const typeIndex1Based = new Int32Array(n);
    const typeTotal = new Int32Array(n);
    const typeCounts = new Map();
    const tags = new Array(n);
    for (let i = 0; i < n; i++) {
      const c = children[i];
      this.tick(1);
      index0Based.set(c, i);
      const tag = this.isXml ? getRawTagName(c) : getRawTagName(c).toLowerCase();
      const cnt = (typeCounts.get(tag) || 0) + 1;
      typeCounts.set(tag, cnt);
      typeIndex1Based[i] = cnt;
      tags[i] = tag;
    }
    for (let i = 0; i < n; i++) {
      typeTotal[i] = typeCounts.get(tags[i]);
    }
    table = { children, totalChildren: n, index0Based, typeIndex1Based, typeTotal };
    this.siblingTables.set(parent, table);
    return table;
  }

  prevElementSiblingFast(el) {
    const p = getParentNode(el);
    if (!p) return null;
    const t = this.siblingTableFor(p);
    const i = t.index0Based.get(el);
    return (i !== undefined && i > 0) ? t.children[i - 1] : null;
  }

  getClassTokens(el) {
    const cached = this.classTokenCache.get(el);
    if (cached !== undefined) return cached;
    const clsAttr = getElementAttr(el, 'class', this.isXml);
    if (!clsAttr) {
      const empty = new Set();
      this.chargeMemory(32);
      this.classTokenCache.set(el, empty);
      return empty;
    }
    const tokens = new Set();
    const len = clsAttr.length;
    let i = 0;
    let budgetChunk = 0;
    let uniqueTokenBytes = 64;
    while (i < len) {
      while (i < len && isAsciiWsCode(clsAttr.charCodeAt(i))) {
        i += 1;
        budgetChunk += 1;
        if (budgetChunk >= 256) {
          this.tick(budgetChunk);
          budgetChunk = 0;
        }
      }
      const start = i;
      while (i < len && !isAsciiWsCode(clsAttr.charCodeAt(i))) {
        i += 1;
        budgetChunk += 1;
        if (budgetChunk >= 256) {
          this.tick(budgetChunk);
          budgetChunk = 0;
        }
      }
      if (i > start) {
        const tok = clsAttr.slice(start, i);
        if (!tokens.has(tok)) {
          tokens.add(tok);
          uniqueTokenBytes += 32 + tok.length * 2;
          if (uniqueTokenBytes >= 4096) {
            this.chargeMemory(uniqueTokenBytes);
            uniqueTokenBytes = 0;
          }
        }
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    if (uniqueTokenBytes > 0) this.chargeMemory(uniqueTokenBytes);
    this.classTokenCache.set(el, tokens);
    return tokens;
  }

  matchesAny(el, selectors, isTopLevel) {
    for (const sel of selectors) {
      if (this.evalMatchStep(el, sel, sel.compounds.length - 1, isTopLevel)) return true;
    }
    return false;
  }

  evalMatchStep(el, sel, idx, isTopLevel) {
    const stepCount = sel.compounds.length;
    const shouldMemo = stepCount > 1 && (!isTopLevel || idx < stepCount - 1);
    const memo = shouldMemo ? this.memoFor(sel) : null;
    if (memo) {
      const cached = this.getCached(memo.matchStep, el, idx);
      if (cached !== MEMO_UNCOMPUTED) return cached === MEMO_TRUE;
    }

    this.tick(1);
    let result = false;
    if (this.matchesCompound(el, sel.compounds[idx])) {
      if (idx === 0) {
        result = true;
      } else {
        const comb = sel.combinators[idx - 1];
        if (comb === '>') {
          const p = getParentElement(el);
          result = p !== null && this.evalMatchStep(p, sel, idx - 1, false);
        } else if (comb === ' ') {
          const p = getParentElement(el);
          result = p !== null && this.evalSelfOrAncestorMatches(p, sel, idx - 1);
        } else if (comb === '+') {
          const prev = this.prevElementSiblingFast(el);
          result = prev !== null && this.evalMatchStep(prev, sel, idx - 1, false);
        } else if (comb === '~') {
          result = this.evalAnyPrevSiblingMatches(el, sel, idx - 1);
        }
      }
    }
    if (memo) {
      this.setCached(memo, 'matchStep', memo.matchRowLen, el, idx, result ? MEMO_TRUE : MEMO_FALSE);
    }
    return result;
  }

  evalSelfOrAncestorMatches(parent, sel, idx) {
    const memo = this.memoFor(sel);
    const quick = this.getCached(memo.selfOrAncestor, parent, idx);
    if (quick !== MEMO_UNCOMPUTED) return quick === MEMO_TRUE;

    const chain = [];
    let cur = parent;
    let baseResult = false;
    while (true) {
      const cached = this.getCached(memo.selfOrAncestor, cur, idx);
      if (cached !== MEMO_UNCOMPUTED) {
        baseResult = cached === MEMO_TRUE;
        break;
      }
      this.tick(1);
      if (this.evalMatchStep(cur, sel, idx, false)) {
        this.setCached(memo, 'selfOrAncestor', memo.combRowLen, cur, idx, MEMO_TRUE);
        baseResult = true;
        break;
      }
      chain.push(cur);
      const p = getParentElement(cur);
      if (!p) {
        baseResult = false;
        break;
      }
      cur = p;
    }
    const fill = baseResult ? MEMO_TRUE : MEMO_FALSE;
    for (let i = chain.length - 1; i >= 0; i--) {
      this.setCached(memo, 'selfOrAncestor', memo.combRowLen, chain[i], idx, fill);
    }
    return baseResult;
  }

  evalAnyPrevSiblingMatches(el, sel, idx) {
    const memo = this.memoFor(sel);
    const quick = this.getCached(memo.anyPrevSibling, el, idx);
    if (quick !== MEMO_UNCOMPUTED) return quick === MEMO_TRUE;

    const chain = [];
    let cur = el;
    let baseResult = false;
    while (true) {
      const cached = this.getCached(memo.anyPrevSibling, cur, idx);
      if (cached !== MEMO_UNCOMPUTED) {
        baseResult = cached === MEMO_TRUE;
        break;
      }
      this.tick(1);
      const prev = this.prevElementSiblingFast(cur);
      if (!prev) {
        this.setCached(memo, 'anyPrevSibling', memo.combRowLen, cur, idx, MEMO_FALSE);
        baseResult = false;
        break;
      }
      if (this.evalMatchStep(prev, sel, idx, false)) {
        this.setCached(memo, 'anyPrevSibling', memo.combRowLen, cur, idx, MEMO_TRUE);
        baseResult = true;
        break;
      }
      chain.push(cur);
      cur = prev;
    }
    const fill = baseResult ? MEMO_TRUE : MEMO_FALSE;
    for (let i = chain.length - 1; i >= 0; i--) {
      this.setCached(memo, 'anyPrevSibling', memo.combRowLen, chain[i], idx, fill);
    }
    return baseResult;
  }

  matchesCompound(el, comp) {
    if (!isElementNode(el)) return false;
    if (comp.nsPrefix !== null || comp.tagName !== null) {
      if (!matchesTag(getRawTagName(el), comp.nsPrefix, comp.tagName, this.isXml)) return false;
    }
    for (const id of comp.ids) {
      this.tick(1);
      const actual = getElementAttr(el, 'id', this.isXml);
      if (actual === null || !this._equalsBudgeted(actual, id, false)) return false;
    }
    if (comp.classes.length > 0) {
      const tokens = this.getClassTokens(el);
      for (const c of comp.classes) {
        this.tick(1);
        if (!tokens.has(c)) return false;
      }
    }
    for (const am of comp.attrs) {
      if (!this.matchesAttr(el, am)) return false;
    }
    for (const pm of comp.pseudos) {
      this.tick(1);
      if (!this.matchesPseudo(el, pm)) return false;
    }
    return true;
  }

  matchesAttr(el, am) {
    this.tick(1);
    const actual = getElementAttr(el, am.name, this.isXml);
    if (actual === null) return false;
    if (am.op === 'exists') return true;
    const expected = am.value ?? '';
    const ci = am.caseFlag === 'i';
    switch (am.op) {
      case '=': return this._equalsBudgeted(actual, expected, ci);
      case '~=': return this._includesWordBudgeted(actual, expected, ci);
      case '|=': return this._dashMatchBudgeted(actual, expected, ci);
      case '^=': return expected.length > 0 && this._startsWithBudgeted(actual, expected, ci);
      case '$=': return expected.length > 0 && this._endsWithBudgeted(actual, expected, ci);
      case '*=': return expected.length > 0 && this._kmpContainsBudgeted(actual, expected, am.kmpLps, ci);
      default: return false;
    }
  }

  _equalsBudgeted(actual, expected, ci) {
    if (actual.length !== expected.length) return false;
    let budgetChunk = 0;
    for (let i = 0; i < expected.length; i++) {
      budgetChunk += 1;
      if (budgetChunk >= 64) {
        this.tick(budgetChunk);
        budgetChunk = 0;
      }
      if (!charsEqual(actual[i], expected[i], ci)) {
        if (budgetChunk > 0) this.tick(budgetChunk);
        return false;
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    return true;
  }

  _startsWithBudgeted(actual, prefix, ci) {
    if (actual.length < prefix.length) return false;
    let budgetChunk = 0;
    for (let i = 0; i < prefix.length; i++) {
      budgetChunk += 1;
      if (budgetChunk >= 64) {
        this.tick(budgetChunk);
        budgetChunk = 0;
      }
      if (!charsEqual(actual[i], prefix[i], ci)) {
        if (budgetChunk > 0) this.tick(budgetChunk);
        return false;
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    return true;
  }

  _endsWithBudgeted(actual, suffix, ci) {
    const offset = actual.length - suffix.length;
    if (offset < 0) return false;
    let budgetChunk = 0;
    for (let i = 0; i < suffix.length; i++) {
      budgetChunk += 1;
      if (budgetChunk >= 64) {
        this.tick(budgetChunk);
        budgetChunk = 0;
      }
      if (!charsEqual(actual[offset + i], suffix[i], ci)) {
        if (budgetChunk > 0) this.tick(budgetChunk);
        return false;
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    return true;
  }

  _dashMatchBudgeted(actual, expected, ci) {
    if (actual.length < expected.length) return false;
    if (!this._startsWithBudgeted(actual, expected, ci)) return false;
    return actual.length === expected.length || actual[expected.length] === '-';
  }

  _includesWordBudgeted(actual, word, ci) {
    if (word.length === 0 || /\s/.test(word)) return false;
    const n = actual.length;
    const m = word.length;
    let i = 0;
    let budgetChunk = 0;
    while (i < n) {
      while (i < n && isAsciiWsCode(actual.charCodeAt(i))) {
        i += 1;
        budgetChunk += 1;
        if (budgetChunk >= 256) {
          this.tick(budgetChunk);
          budgetChunk = 0;
        }
      }
      const start = i;
      while (i < n && !isAsciiWsCode(actual.charCodeAt(i))) {
        i += 1;
        budgetChunk += 1;
        if (budgetChunk >= 256) {
          this.tick(budgetChunk);
          budgetChunk = 0;
        }
      }
      if (i - start === m) {
        let matched = true;
        for (let k = 0; k < m; k++) {
          if (!charsEqual(actual[start + k], word[k], ci)) {
            matched = false;
            break;
          }
        }
        if (matched) {
          if (budgetChunk > 0) this.tick(budgetChunk);
          return true;
        }
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    return false;
  }

  _kmpContainsBudgeted(haystack, needle, lps, ci) {
    const n = haystack.length;
    const m = needle.length;
    if (m === 0 || n < m) return false;
    let i = 0;
    let j = 0;
    let budgetChunk = 0;
    while (i < n) {
      budgetChunk += 1;
      if (budgetChunk >= 256) {
        this.tick(budgetChunk);
        budgetChunk = 0;
      }
      if (charsEqual(haystack[i], needle[j], ci)) {
        i += 1;
        j += 1;
        if (j === m) {
          if (budgetChunk > 0) this.tick(budgetChunk);
          return true;
        }
      } else if (j !== 0) {
        j = lps[j - 1];
      } else {
        i += 1;
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    return false;
  }

  matchesPseudo(el, pm) {
    switch (pm.kind) {
      case 'first-child': {
        const p = getParentNode(el);
        return Boolean(p) && this.siblingTableFor(p).index0Based.get(el) === 0;
      }
      case 'last-child': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        return t.index0Based.get(el) === t.totalChildren - 1;
      }
      case 'only-child': {
        const p = getParentNode(el);
        return Boolean(p) && this.siblingTableFor(p).totalChildren === 1;
      }
      case 'first-of-type': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        const pos0 = t.index0Based.get(el);
        return pos0 !== undefined && t.typeIndex1Based[pos0] === 1;
      }
      case 'last-of-type': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        const pos0 = t.index0Based.get(el);
        return pos0 !== undefined && t.typeIndex1Based[pos0] === t.typeTotal[pos0];
      }
      case 'only-of-type': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        const pos0 = t.index0Based.get(el);
        return pos0 !== undefined && t.typeTotal[pos0] === 1;
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
        const pos0 = t.index0Based.get(el);
        if (pos0 === undefined) return false;
        let idx;
        if (pm.ofType) {
          const pos1 = t.typeIndex1Based[pos0];
          const tot = t.typeTotal[pos0];
          idx = pm.fromEnd ? (tot - pos1 + 1) : pos1;
        } else {
          const pos1 = pos0 + 1;
          idx = pm.fromEnd ? (t.totalChildren - pos1 + 1) : pos1;
        }
        return matchesAnPlusB(idx, pm.a, pm.b);
      }
      case 'not':
        return !this.matchesAny(el, pm.inner, false);
      case 'is':
        return this.matchesAny(el, pm.inner, false);
      default:
        return false;
    }
  }
}

function queryDescendants(scopeRoot, selectors, isXml, firstOnly, maxSteps, maxMemoryBytes, deadlineMs) {
  const ctx = new QueryContext(isXml, maxSteps, maxMemoryBytes, deadlineMs);
  const out = [];
  const stack = [];
  const top = getElementChildren(scopeRoot);
  for (let i = top.length - 1; i >= 0; i--) stack.push(top[i]);
  while (stack.length > 0) {
    ctx.tick(1);
    const el = stack.pop();
    if (ctx.matchesAny(el, selectors, true)) {
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
    maxDocsPerCall = MAX_LIVE_DOCS,
    maxDocBytes = MAX_DOC_BYTES,
    maxTotalNodes = MAX_DOC_NODES,
    maxSelectorSteps = MAX_SELECTOR_STEPS,
    maxLiveNodes = MAX_LIVE_NODES,
    maxSelectorMemoryBytes = MAX_SELECTOR_MEMORY_BYTES,
  ) {
    this.maxDocsPerCall = maxDocsPerCall;
    this.maxDocBytes = maxDocBytes;
    this.maxTotalNodes = maxTotalNodes;
    this.maxSelectorSteps = maxSelectorSteps;
    this.maxLiveNodes = maxLiveNodes;
    this.maxSelectorMemoryBytes = maxSelectorMemoryBytes;
    this.nextId = 1;
    this.docs = new Map();
    this.nodes = new Map();
    this.totalNodes = 0;
    this.deadlineMs = Infinity;
    this._xmlParser = new DOMParser();
  }

  get docCount() {
    return this.docs.size;
  }

  get handleCount() {
    return this.nodes.size;
  }

  setCallDeadlineMs(deadlineMs) {
    this.deadlineMs = deadlineMs;
  }

  parseHtml(html) {
    const text = String(html ?? '');
    const remaining = this._checkPreParseLimits(text);
    // WHATWG HTML5 compliant tree construction matching Android Jsoup
    const document = parse5.parse(text);
    const count = this._normalizeAndCountElements(document, true, remaining);
    return this._registerDocument(document, false, count);
  }

  parseXml(xml) {
    const text = String(xml ?? '');
    const remaining = this._checkPreParseLimits(text);
    const document = this._xmlParser.parseFromString(text, 'text/xml');
    const count = this._normalizeAndCountElements(document, false, remaining);
    return this._registerDocument(document, true, count);
  }

  closeDoc(docId) {
    const docEntry = this.docs.get(docId);
    if (!docEntry) return false;
    this.docs.delete(docId);
    for (const hid of docEntry.handleIds) {
      this.nodes.delete(hid);
    }
    docEntry.handleIds.length = 0;
    docEntry.elementToHandleId.clear();
    this.totalNodes = Math.max(0, this.totalNodes - docEntry.elementCount);
    return true;
  }

  selectFirstId(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return -1;
    const parsed = parseSelector(selector);
    const found = queryDescendants(
      entry.el,
      parsed,
      entry.isXml,
      true,
      this.maxSelectorSteps,
      this.maxSelectorMemoryBytes,
      this.deadlineMs,
    );
    if (!found) return -1;
    return this._registerElement(entry.docId, found);
  }

  selectAllIds(nodeId, selector) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const parsed = parseSelector(selector);
    const list = queryDescendants(
      entry.el,
      parsed,
      entry.isXml,
      false,
      this.maxSelectorSteps,
      this.maxSelectorMemoryBytes,
      this.deadlineMs,
    );
    return list.map((el) => this._registerElement(entry.docId, el));
  }

  childrenIds(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const list = getElementChildren(entry.el);
    return list.map((el) => this._registerElement(entry.docId, el));
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
      this._collectXmlTextIterative(entry.el, parts);
    } else {
      this._collectHtmlTextIterative(entry.el, parts);
    }
    return collapseAsciiAndNbspWhitespace(parts.join(''));
  }

  html(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    const parts = [];
    serializeChildrenIterative(entry.el, entry.isXml, parts, this.deadlineMs);
    return parts.join('');
  }

  outerHtml(nodeId) {
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    const parts = [];
    if (entry.isDocument) {
      serializeChildrenIterative(entry.el, entry.isXml, parts, this.deadlineMs);
    } else {
      serializeNodeIterative(entry.el, entry.isXml, parts, this.deadlineMs);
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
    this.docs.clear();
    this.totalNodes = 0;
    this.deadlineMs = Infinity;
  }

  _collectXmlTextIterative(root, parts) {
    const stack = [root];
    let ops = 0;
    while (stack.length > 0) {
      if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
      const node = stack.pop();
      if (!node) continue;
      if (isTextNode(node) || isCDataNode(node)) {
        parts.push(getNodeTextValue(node));
        continue;
      }
      if (!isElementNode(node) && !isDocumentNode(node)) continue;
      const ch = getChildNodes(node);
      for (let i = ch.length - 1; i >= 0; i--) stack.push(ch[i]);
    }
  }

  _collectHtmlTextIterative(root, parts) {
    const SPACE_MARKER = Symbol('space');
    const stack = [root];
    let ops = 0;
    while (stack.length > 0) {
      if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
      const item = stack.pop();
      if (!item) continue;
      if (item === SPACE_MARKER) {
        parts.push(' ');
        continue;
      }
      if (isTextNode(item) || isCDataNode(item)) {
        parts.push(getNodeTextValue(item));
        continue;
      }
      if (isDocumentNode(item)) {
        const ch = getChildNodes(item);
        for (let i = ch.length - 1; i >= 0; i--) stack.push(ch[i]);
        continue;
      }
      if (!isElementNode(item)) continue;
      const tag = getRawTagName(item).toLowerCase();
      if (HTML_SKIP_TEXT_TAGS.has(tag)) continue;
      const isBlock = HTML_BLOCK_TAGS.has(tag);
      if (isBlock) {
        parts.push(' ');
        stack.push(SPACE_MARKER);
      }
      const ch = getChildNodes(item);
      for (let i = ch.length - 1; i >= 0; i--) stack.push(ch[i]);
    }
  }

  _checkPreParseLimits(text) {
    if (this.docs.size >= this.maxDocsPerCall) {
      throw new DomLimitExceededError(`同时存活的文档数超过上限（最多 ${this.maxDocsPerCall} 个）`);
    }
    if (Buffer.byteLength(text, 'utf8') > this.maxDocBytes) {
      throw new DomLimitExceededError(`解析文档超过大小限制（上限 ${Math.round(this.maxDocBytes / (1024 * 1024))} MB）`);
    }
    const liveRem = this.maxLiveNodes - this.totalNodes;
    if (liveRem <= 0) {
      throw new DomLimitExceededError(`同时存活的 DOM 节点总数超过上限（最多 ${this.maxLiveNodes} 个）`);
    }
    const remaining = Math.min(this.maxTotalNodes, liveRem);
    let openTags = 0;
    for (let i = 0; i < text.length - 1; i++) {
      if (text.charCodeAt(i) === 60) {
        const next = text.charCodeAt(i + 1);
        if (next !== 47 && next !== 33 && next !== 63 && next !== 32 && next !== 9 && next !== 10 && next !== 13) {
          openTags += 1;
          if (openTags > remaining) {
            throw new DomLimitExceededError(
              `解析的 DOM 节点总数超过上限（单文档最多 ${this.maxTotalNodes} 个，总计最多 ${this.maxLiveNodes} 个）`,
            );
          }
        }
      }
    }
    return remaining;
  }

  /**
   * Counts strictly Element nodes (excluding `#document` itself, matching Android
   * `PluginDomParser.kt`), and in parse5 HTML trees flattens `<template>`'s
   * `.content.childNodes` directly onto the `<template>` node so `<template>`
   * traversal matches Jsoup.
   */
  _normalizeAndCountElements(doc, isParse5Html, remaining) {
    let count = 0;
    const stack = [];
    const top = getElementChildren(doc);
    for (let i = top.length - 1; i >= 0; i--) stack.push(top[i]);
    while (stack.length > 0) {
      const cur = stack.pop();
      count += 1;
      if ((count & 1023) === 0) checkDeadline(this.deadlineMs);
      if (count > remaining) {
        throw new DomLimitExceededError(
          `解析的 DOM 节点总数超过上限（单文档最多 ${this.maxTotalNodes} 个，总计最多 ${this.maxLiveNodes} 个）`,
        );
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

  _registerDocument(doc, isXml, elementCount) {
    if (this.docs.size >= this.maxDocsPerCall) {
      throw new DomLimitExceededError(`同时存活的文档数超过上限（最多 ${this.maxDocsPerCall} 个）`);
    }
    if (elementCount > this.maxTotalNodes) {
      throw new DomLimitExceededError(`单个文档解析的 DOM 节点总数超过上限（最多 ${this.maxTotalNodes} 个）`);
    }
    if (this.totalNodes + elementCount > this.maxLiveNodes) {
      throw new DomLimitExceededError(`同时存活的 DOM 节点总数超过上限（最多 ${this.maxLiveNodes} 个）`);
    }
    const docId = this.nextId++;
    const elementToHandleId = new Map();
    elementToHandleId.set(doc, docId);
    const docEntry = {
      docId,
      doc,
      isXml,
      elementCount,
      elementToHandleId,
      handleIds: [docId],
    };
    this.docs.set(docId, docEntry);
    this.nodes.set(docId, { docId, el: doc, isXml, isDocument: true });
    this.totalNodes += elementCount;
    return docId;
  }

  _registerElement(docId, el) {
    const docEntry = this.docs.get(docId);
    if (!docEntry) return -1;
    const existing = docEntry.elementToHandleId.get(el);
    if (existing !== undefined) return existing;
    const maxHandles = docEntry.elementCount + 1;
    if (docEntry.elementToHandleId.size >= maxHandles) {
      throw new DomLimitExceededError(`单个文档句柄数超过上限（最多 ${maxHandles} 个）`);
    }
    const id = this.nextId++;
    docEntry.elementToHandleId.set(el, id);
    docEntry.handleIds.push(id);
    this.nodes.set(id, { docId, el, isXml: docEntry.isXml, isDocument: false });
    return id;
  }
}
