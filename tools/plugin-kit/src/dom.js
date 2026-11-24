import { DOMParser } from 'linkedom';
import * as parse5 from 'parse5';

/**
 * §11.1 (S4) Byte-budgeted DOM parser mirroring Android `PluginDomParser.kt`:
 * - Max input text: 2 MB UTF-8
 * - Max single document estimated memory: 24 MB
 * - Max VM-wide live DOM memory (live docs + handle table + active class cache): 48 MB
 * - Max simultaneously live documents per VM: 8
 * - Max serialization output (`html()`, `outerHtml()`, `text()`, `attrsJson()`): 8 MB
 * - Mid-parse streaming byte accounting for ALL node kinds (Elements, Text, Comments,
 *   PIs, Doctypes, CDATA, Attributes) via parse5 custom `treeAdapter` + XML pre-scan,
 *   followed by exact full-tree recount
 * - Per-document deduplicated monotonic handle IDs (never reset on `clear()`)
 * - O(N * S) memoized CSS selector engine with hard syntax caps (4 KB, 32 groups,
 *   32 compounds, nesting 4, value 256, `:nth-*` ±1e6), 16 MB query DP memory budget,
 *   cached `:empty`, in-place short-class scanning (no false-positive cache overflow on
 *   200k classed elements), budgeted loops everywhere, and entry deadline checks
 */
export const MAX_LIVE_DOCS = 8;
export const MAX_DOCS_PER_CALL = MAX_LIVE_DOCS;
export const MAX_DOC_BYTES = 2 * 1024 * 1024;
export const MAX_DOC_MEMORY_BYTES = 24 * 1024 * 1024;
export const MAX_VM_MEMORY_BYTES = 48 * 1024 * 1024;
export const MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
export const MAX_SELECTOR_STEPS = 2_000_000;
export const MAX_SELECTOR_MEMORY_BYTES = 16 * 1024 * 1024;
export const MAX_SELECTOR_BYTES = 4 * 1024;
export const MAX_SELECTOR_GROUPS = 32;
export const MAX_COMPOUNDS_PER_SELECTOR = 32;
export const MAX_PSEUDO_NESTING = 4;
export const MAX_SELECTOR_VALUE_LENGTH = 256;
export const MAX_NTH_ABS = 1_000_000;

// Calibrated per-node byte estimates matching Android PluginDomParser.kt identically
export const DOC_BASE_BYTES = 1024;
export const ELEMENT_BASE_BYTES = 56;
export const CHILD_LIST_BASE_BYTES = 64;
export const CHILD_SLOT_BYTES = 8;
export const ATTRS_CONTAINER_BYTES = 48;
export const ATTR_ENTRY_BYTES = 40;
export const TEXT_NODE_BASE_BYTES = 88;
export const COMMENT_NODE_BASE_BYTES = 88;
export const OTHER_NODE_BASE_BYTES = 160;
export const HANDLE_ENTRY_BYTES = 96;

const MEMO_UNCOMPUTED = 0;
const MEMO_FALSE = 1;
const MEMO_TRUE = 2;
const LONG_CLASS_CACHE_THRESHOLD = 128;

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

function utf8ByteLength(str) {
  return Buffer.byteLength(String(str ?? ''), 'utf8');
}

function charUtf8Bytes(code) {
  if (code < 0x80) return 1;
  if (code < 0x800) return 2;
  if (code >= 0xD800 && code <= 0xDFFF) return 2;
  return 3;
}

class BoundedOutputBuffer {
  constructor(maxBytes, deadlineMs) {
    this.maxBytes = maxBytes;
    this.deadlineMs = deadlineMs;
    this.byteCount = 0;
    this.ops = 0;
    this.parts = [];
  }

  appendAscii(s) {
    const next = this.byteCount + s.length;
    if (next > this.maxBytes) {
      throw new DomLimitExceededError(`DOM 序列化输出超过上限（最多 ${Math.round(this.maxBytes / (1024 * 1024))} MB）`);
    }
    this.byteCount = next;
    this.parts.push(s);
    if ((++this.ops & 4095) === 0) checkDeadline(this.deadlineMs);
  }

  appendString(s) {
    if (!s) return;
    const add = Buffer.byteLength(s, 'utf8');
    const next = this.byteCount + add;
    if (next > this.maxBytes) {
      throw new DomLimitExceededError(`DOM 序列化输出超过上限（最多 ${Math.round(this.maxBytes / (1024 * 1024))} MB）`);
    }
    this.byteCount = next;
    this.parts.push(s);
    if ((++this.ops & 1023) === 0) checkDeadline(this.deadlineMs);
  }

  toString() {
    return this.parts.join('');
  }
}

function collapseAsciiAndNbspWhitespace(raw, maxOutputBytes = MAX_OUTPUT_BYTES, deadlineMs = Infinity) {
  const s = String(raw ?? '');
  const parts = [];
  let chunk = '';
  let inSpace = true;
  let bytes = 0;
  let ops = 0;
  for (let i = 0; i < s.length; i++) {
    if ((++ops & 4095) === 0) checkDeadline(deadlineMs);
    const ch = s.charCodeAt(i);
    const isSpace = ch === 0x20 || ch === 0x09 || ch === 0x0A || ch === 0x0D || ch === 0x0C || ch === 0xA0;
    if (isSpace) {
      if (!inSpace) {
        bytes += 1;
        if (bytes > maxOutputBytes) {
          throw new DomLimitExceededError(`DOM 文本输出超过上限（最多 ${Math.round(maxOutputBytes / (1024 * 1024))} MB）`);
        }
        chunk += ' ';
        inSpace = true;
      }
    } else {
      bytes += charUtf8Bytes(ch);
      if (bytes > maxOutputBytes) {
        throw new DomLimitExceededError(`DOM 文本输出超过上限（最多 ${Math.round(maxOutputBytes / (1024 * 1024))} MB）`);
      }
      chunk += s[i];
      inSpace = false;
    }
    if (chunk.length >= 4096) {
      parts.push(chunk);
      chunk = '';
    }
  }
  if (chunk) parts.push(chunk);
  let out = parts.join('');
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

function getElementAttrWithDeadline(el, name, isXml, deadlineMs) {
  if (!el || isDocumentNode(el)) return null;
  const attrs = getElementAttributes(el);
  let ops = 0;
  if (isXml) {
    for (const a of attrs) {
      if ((++ops & 255) === 0) checkDeadline(deadlineMs);
      if (a.name === name) return a.value ?? '';
    }
    return null;
  }
  const lower = String(name).toLowerCase();
  for (const a of attrs) {
    if ((++ops & 255) === 0) checkDeadline(deadlineMs);
    if (String(a.name).toLowerCase() === lower) return a.value ?? '';
  }
  return null;
}

// Bounded iterative canonical HTML/XML serializer matching Android PluginDomParser.kt bit-for-bit
function appendEscapedHtmlText(s, out) {
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    let rep = null;
    if (c === '&') rep = '&amp;';
    else if (c === '\u00A0') rep = '&nbsp;';
    else if (c === '<') rep = '&lt;';
    else if (c === '>') rep = '&gt;';
    if (rep !== null) {
      if (i > start) out.appendString(s.slice(start, i));
      out.appendAscii(rep);
      start = i + 1;
    } else if (i - start >= 4096) {
      out.appendString(s.slice(start, i + 1));
      start = i + 1;
    }
  }
  if (start < s.length) out.appendString(s.slice(start));
}

function appendEscapedHtmlAttr(s, out) {
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    let rep = null;
    if (c === '&') rep = '&amp;';
    else if (c === '\u00A0') rep = '&nbsp;';
    else if (c === '"') rep = '&quot;';
    if (rep !== null) {
      if (i > start) out.appendString(s.slice(start, i));
      out.appendAscii(rep);
      start = i + 1;
    } else if (i - start >= 4096) {
      out.appendString(s.slice(start, i + 1));
      start = i + 1;
    }
  }
  if (start < s.length) out.appendString(s.slice(start));
}

function appendEscapedXmlText(s, out) {
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    let rep = null;
    if (c === '&') rep = '&amp;';
    else if (c === '<') rep = '&lt;';
    else if (c === '>') rep = '&gt;';
    if (rep !== null) {
      if (i > start) out.appendString(s.slice(start, i));
      out.appendAscii(rep);
      start = i + 1;
    } else if (i - start >= 4096) {
      out.appendString(s.slice(start, i + 1));
      start = i + 1;
    }
  }
  if (start < s.length) out.appendString(s.slice(start));
}

function appendEscapedXmlAttr(s, out) {
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    let rep = null;
    if (c === '&') rep = '&amp;';
    else if (c === '<') rep = '&lt;';
    else if (c === '>') rep = '&gt;';
    else if (c === '"') rep = '&quot;';
    if (rep !== null) {
      if (i > start) out.appendString(s.slice(start, i));
      out.appendAscii(rep);
      start = i + 1;
    } else if (i - start >= 4096) {
      out.appendString(s.slice(start, i + 1));
      start = i + 1;
    }
  }
  if (start < s.length) out.appendString(s.slice(start));
}

function pushSerializedChildren(node, isXml, stack, deadlineMs) {
  const rawParent = !isXml && isElementNode(node) && HTML_RAW_TEXT_TAGS.has(getRawTagName(node).toLowerCase());
  const ch = getChildNodes(node);
  let ops = 0;
  for (let i = ch.length - 1; i >= 0; i--) {
    if ((++ops & 1023) === 0) checkDeadline(deadlineMs);
    const child = ch[i];
    if (rawParent && isTextNode(child)) {
      stack.push({ __rawText: getNodeTextValue(child) });
    } else {
      stack.push(child);
    }
  }
}

function drainSerializeStack(stack, isXml, out, deadlineMs) {
  let ops = 0;
  while (stack.length > 0) {
    if ((++ops & 1023) === 0) checkDeadline(deadlineMs);
    const item = stack.pop();
    if (typeof item === 'string') {
      out.appendString(item);
      continue;
    }
    if (item && typeof item.__rawText === 'string') {
      out.appendString(item.__rawText);
      continue;
    }
    if (isCDataNode(item)) {
      const data = getNodeTextValue(item);
      if (isXml) {
        out.appendAscii('<![CDATA[');
        out.appendString(data);
        out.appendAscii(']]>');
      } else {
        appendEscapedHtmlText(data, out);
      }
      continue;
    }
    if (isTextNode(item)) {
      const data = getNodeTextValue(item);
      if (isXml) appendEscapedXmlText(data, out);
      else appendEscapedHtmlText(data, out);
      continue;
    }
    if (isCommentNode(item)) {
      out.appendAscii('<!--');
      out.appendString(getNodeCommentData(item));
      out.appendAscii('-->');
      continue;
    }
    if (isElementNode(item)) {
      const tag = isXml ? getRawTagName(item) : getRawTagName(item).toLowerCase();
      out.appendAscii('<');
      out.appendString(tag);
      let attrOps = 0;
      for (const a of getElementAttributes(item)) {
        if ((++attrOps & 255) === 0) checkDeadline(deadlineMs);
        const key = isXml ? a.name : String(a.name).toLowerCase();
        const v = a.value ?? '';
        if (!isXml && v === '') {
          out.appendAscii(' ');
          out.appendString(key);
        } else {
          out.appendAscii(' ');
          out.appendString(key);
          out.appendAscii('="');
          if (isXml) appendEscapedXmlAttr(v, out);
          else appendEscapedHtmlAttr(v, out);
          out.appendAscii('"');
        }
      }
      if (isXml) {
        const ch = getChildNodes(item);
        if (ch.length === 0) {
          out.appendAscii('/>');
        } else {
          out.appendAscii('>');
          stack.push(`</${tag}>`);
          pushSerializedChildren(item, true, stack, deadlineMs);
        }
      } else {
        out.appendAscii('>');
        if (!HTML_VOID_TAGS.has(tag)) {
          stack.push(`</${tag}>`);
          pushSerializedChildren(item, false, stack, deadlineMs);
        }
      }
    }
  }
}

function serializeChildrenIterative(node, isXml, out, deadlineMs) {
  const stack = [];
  pushSerializedChildren(node, isXml, stack, deadlineMs);
  drainSerializeStack(stack, isXml, out, deadlineMs);
}

function serializeNodeIterative(node, isXml, out, deadlineMs) {
  const stack = [node];
  drainSerializeStack(stack, isXml, out, deadlineMs);
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
  constructor(isXml, maxSteps, maxMemoryBytes, maxClassCacheBytes, deadlineMs) {
    this.isXml = isXml;
    this.maxSteps = maxSteps;
    this.maxMemoryBytes = maxMemoryBytes;
    this.maxClassCacheBytes = maxClassCacheBytes;
    this.deadlineMs = deadlineMs;
    this.steps = 0;
    this.memoryBytes = 0;
    this.classCacheBytes = 0;
    this.memos = new Map();
    this.siblingTables = new Map();
    this.classTokenCache = new Map();
    this.emptyCache = new Map();
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

  chargeClassCacheMemory(bytes) {
    if (bytes <= 0) return;
    this.chargeMemory(bytes);
    this.classCacheBytes += bytes;
    if (this.classCacheBytes > this.maxClassCacheBytes) {
      throw new DomLimitExceededError('VM 存活 DOM 与类名缓存总内存超过上限');
    }
  }

  getElementAttrBudgeted(el, name) {
    if (!el || isDocumentNode(el)) return null;
    const attrs = getElementAttributes(el);
    if (attrs.length === 0) {
      this.tick(1);
      return null;
    }
    let budgetChunk = 0;
    if (this.isXml) {
      for (let i = 0; i < attrs.length; i++) {
        budgetChunk += 1;
        if (budgetChunk >= 64) {
          this.tick(budgetChunk);
          budgetChunk = 0;
        }
        if (attrs[i].name === name) {
          if (budgetChunk > 0) this.tick(budgetChunk);
          return attrs[i].value ?? '';
        }
      }
      if (budgetChunk > 0) this.tick(budgetChunk);
      return null;
    }
    const lower = String(name).toLowerCase();
    for (let i = 0; i < attrs.length; i++) {
      budgetChunk += 1;
      if (budgetChunk >= 64) {
        this.tick(budgetChunk);
        budgetChunk = 0;
      }
      if (String(attrs[i].name).toLowerCase() === lower) {
        if (budgetChunk > 0) this.tick(budgetChunk);
        return attrs[i].value ?? '';
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    return null;
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
    const raw = getChildNodes(parent);
    const children = [];
    let scanChunk = 0;
    for (let i = 0; i < raw.length; i++) {
      scanChunk += 1;
      if (scanChunk >= 256) {
        this.tick(scanChunk);
        scanChunk = 0;
      }
      if (isElementNode(raw[i])) children.push(raw[i]);
    }
    if (scanChunk > 0) this.tick(scanChunk);

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

  matchesClasses(el, wantedClasses) {
    const cached = this.classTokenCache.get(el);
    if (cached !== undefined) {
      for (const c of wantedClasses) {
        this.tick(1);
        if (!cached.has(c)) return false;
      }
      return true;
    }
    const clsAttr = this.getElementAttrBudgeted(el, 'class');
    if (!clsAttr) return false;
    if (clsAttr.length <= LONG_CLASS_CACHE_THRESHOLD) {
      this.tick(1);
      return this._matchesAllClassesInPlace(clsAttr, wantedClasses);
    }
    const tokens = new Set();
    const len = clsAttr.length;
    let i = 0;
    let budgetChunk = 0;
    let uniqueTokenBytes = 64;
    let tokenCount = 0;
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
        tokenCount += 1;
        const tok = clsAttr.slice(start, i);
        if (!tokens.has(tok)) {
          tokens.add(tok);
          uniqueTokenBytes += 32 + tok.length * 2;
          if (uniqueTokenBytes >= 4096) {
            this.chargeClassCacheMemory(uniqueTokenBytes);
            uniqueTokenBytes = 0;
          }
        }
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    if (tokenCount > 1) {
      if (uniqueTokenBytes > 0) this.chargeClassCacheMemory(uniqueTokenBytes);
      this.classTokenCache.set(el, tokens);
    }
    for (const c of wantedClasses) {
      this.tick(1);
      if (!tokens.has(c)) return false;
    }
    return true;
  }

  _matchesAllClassesInPlace(clsAttr, wantedClasses) {
    const n = clsAttr.length;
    let charsScanned = 0;
    for (let k = 0; k < wantedClasses.length; k++) {
      if (k > 0) this.tick(1);
      const target = wantedClasses[k];
      const m = target.length;
      let found = false;
      let i = 0;
      while (i < n) {
        while (i < n && isAsciiWsCode(clsAttr.charCodeAt(i))) i += 1;
        const start = i;
        while (i < n && !isAsciiWsCode(clsAttr.charCodeAt(i))) i += 1;
        if (i - start === m && clsAttr.startsWith(target, start)) {
          found = true;
          break;
        }
      }
      charsScanned += i;
      if (charsScanned >= 32) {
        this.tick(charsScanned >> 5);
        charsScanned &= 31;
      }
      if (!found) return false;
    }
    return true;
  }

  matchesEmptyCached(el) {
    const ch = getChildNodes(el);
    if (ch.length === 0) return true;
    const cached = this.emptyCache.get(el);
    if (cached !== undefined) return cached;
    let isEmpty = true;
    let budgetChunk = 0;
    for (let i = 0; i < ch.length; i++) {
      budgetChunk += 1;
      if (budgetChunk >= 256) {
        this.tick(budgetChunk);
        budgetChunk = 0;
      }
      const n = ch[i];
      if (isElementNode(n) || ((isTextNode(n) || isCDataNode(n)) && getNodeTextValue(n).length > 0)) {
        isEmpty = false;
        break;
      }
    }
    if (budgetChunk > 0) this.tick(budgetChunk);
    this.chargeMemory(32);
    this.emptyCache.set(el, isEmpty);
    return isEmpty;
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
      const actual = this.getElementAttrBudgeted(el, 'id');
      if (actual === null || !this._equalsBudgeted(actual, id, false)) return false;
    }
    if (comp.classes.length > 0) {
      if (!this.matchesClasses(el, comp.classes)) return false;
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
    const actual = this.getElementAttrBudgeted(el, am.name);
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
        return this.matchesEmptyCached(el);
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

function pushChildElementsBudgeted(parent, stack, ctx) {
  const raw = getChildNodes(parent);
  let nonElemChunk = 0;
  for (let i = raw.length - 1; i >= 0; i--) {
    const child = raw[i];
    if (isElementNode(child)) {
      stack.push(child);
    } else {
      nonElemChunk += 1;
      if (nonElemChunk >= 256) {
        ctx.tick(nonElemChunk);
        nonElemChunk = 0;
      }
    }
  }
  if (nonElemChunk > 0) ctx.tick(nonElemChunk);
}

function queryDescendants(scopeRoot, selectors, isXml, firstOnly, maxSteps, maxMemoryBytes, maxClassCacheBytes, deadlineMs) {
  checkDeadline(deadlineMs);
  const ctx = new QueryContext(isXml, maxSteps, maxMemoryBytes, maxClassCacheBytes, deadlineMs);
  const out = [];
  const stack = [];
  pushChildElementsBudgeted(scopeRoot, stack, ctx);
  while (stack.length > 0) {
    ctx.tick(1);
    const el = stack.pop();
    if (ctx.matchesAny(el, selectors, true)) {
      if (firstOnly) return el;
      out.push(el);
    }
    pushChildElementsBudgeted(el, stack, ctx);
  }
  return firstOnly ? null : out;
}

/**
 * Custom parse5 TreeAdapter that accounts ALL nodes (Elements, Text, Comments,
 * Doctype, Attributes) live during HTML5 parsing and aborts immediately when
 * estimated bytes cross `budgetBytes` or the call deadline expires.
 */
function createBudgetedParse5TreeAdapter(budgetBytes, deadlineMs) {
  const base = parse5.defaultTreeAdapter;
  let bytes = DOC_BASE_BYTES;
  let ops = 0;

  function charge(add) {
    bytes += add;
    if ((++ops & 1023) === 0) checkDeadline(deadlineMs);
    if (bytes > budgetBytes) {
      throw new DomLimitExceededError(
        `解析中的 DOM 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
      );
    }
  }

  return {
    ...base,
    createElement(tagName, namespaceURI, attrs) {
      let add = ELEMENT_BASE_BYTES;
      if (attrs && attrs.length > 0) {
        add += ATTRS_CONTAINER_BYTES;
        for (let i = 0; i < attrs.length; i++) {
          const a = attrs[i];
          add += ATTR_ENTRY_BYTES + 2 * ((a.name ? a.name.length : 0) + (a.value ? a.value.length : 0));
        }
      }
      charge(add);
      return base.createElement(tagName, namespaceURI, attrs);
    },
    createCommentNode(data) {
      charge(COMMENT_NODE_BASE_BYTES + 2 * (data ? data.length : 0));
      return base.createCommentNode(data);
    },
    createTextNode(value) {
      charge(TEXT_NODE_BASE_BYTES + 2 * (value ? value.length : 0));
      return base.createTextNode(value);
    },
    setDocumentType(document, name, publicId, systemId) {
      charge(OTHER_NODE_BASE_BYTES + 2 * ((name?.length || 0) + (publicId?.length || 0) + (systemId?.length || 0)));
      return base.setDocumentType(document, name, publicId, systemId);
    },
    appendChild(parentNode, newNode) {
      const ch = base.getChildNodes(parentNode);
      charge(ch && ch.length === 0 ? CHILD_LIST_BASE_BYTES + CHILD_SLOT_BYTES : CHILD_SLOT_BYTES);
      return base.appendChild(parentNode, newNode);
    },
    insertBefore(parentNode, newNode, referenceNode) {
      charge(CHILD_SLOT_BYTES);
      return base.insertBefore(parentNode, newNode, referenceNode);
    },
    insertText(parentNode, text) {
      const ch = base.getChildNodes(parentNode);
      const prev = ch && ch.length > 0 ? ch[ch.length - 1] : null;
      const isNewTextNode = !prev || !base.isTextNode(prev);
      charge((isNewTextNode ? (TEXT_NODE_BASE_BYTES + CHILD_SLOT_BYTES) : 0) + 2 * (text ? text.length : 0));
      return base.insertText(parentNode, text);
    },
    insertTextBefore(parentNode, text, referenceNode) {
      charge(TEXT_NODE_BASE_BYTES + CHILD_SLOT_BYTES + 2 * (text ? text.length : 0));
      return base.insertTextBefore(parentNode, text, referenceNode);
    },
    adoptAttributes(recipient, attrs) {
      if (attrs && attrs.length > 0) {
        let add = 0;
        for (let i = 0; i < attrs.length; i++) {
          const a = attrs[i];
          add += ATTR_ENTRY_BYTES + 2 * ((a.name ? a.name.length : 0) + (a.value ? a.value.length : 0));
        }
        charge(add);
      }
      return base.adoptAttributes(recipient, attrs);
    },
  };
}

/**
 * Fast XML-aware lower-bound pre-scan skipping `<![CDATA[...]]>` and quoted
 * attribute values so huge comment / PI / element XML floods abort BEFORE
 * `linkedom` allocates an oversized tree.
 */
function preScanXmlLowerBound(xml, budgetBytes, deadlineMs) {
  let est = DOC_BASE_BYTES;
  const len = xml.length;
  let i = 0;
  let ops = 0;
  while (i < len) {
    if ((++ops & 4095) === 0) checkDeadline(deadlineMs);
    const ch = xml.charCodeAt(i);
    if (ch === 60) { // '<'
      if (xml.startsWith('<![CDATA[', i)) {
        const end = xml.indexOf(']]>', i + 9);
        const stop = end >= 0 ? end + 3 : len;
        est += TEXT_NODE_BASE_BYTES + CHILD_SLOT_BYTES + 2 * Math.max(0, (end >= 0 ? end : len) - (i + 9));
        i = stop;
      } else if (xml.startsWith('<!--', i)) {
        const end = xml.indexOf('-->', i + 4);
        const stop = end >= 0 ? end + 3 : len;
        est += COMMENT_NODE_BASE_BYTES + CHILD_SLOT_BYTES + 2 * Math.max(0, (end >= 0 ? end : len) - (i + 4));
        i = stop;
      } else if (xml.startsWith('<?', i)) {
        const end = xml.indexOf('?>', i + 2);
        const stop = end >= 0 ? end + 2 : len;
        est += OTHER_NODE_BASE_BYTES + CHILD_SLOT_BYTES;
        i = stop;
      } else if (i + 1 < len && xml.charCodeAt(i + 1) === 47) { // '</'
        const end = xml.indexOf('>', i + 2);
        i = end >= 0 ? end + 1 : len;
      } else if (i + 1 < len && xml.charCodeAt(i + 1) === 33) { // '<!'
        const end = xml.indexOf('>', i + 2);
        est += COMMENT_NODE_BASE_BYTES + CHILD_SLOT_BYTES;
        i = end >= 0 ? end + 1 : len;
      } else {
        est += ELEMENT_BASE_BYTES + CHILD_SLOT_BYTES;
        i += 1;
        // Skip tag header respecting quoted attributes
        let quote = 0;
        while (i < len) {
          const c = xml.charCodeAt(i);
          if (quote !== 0) {
            if (c === quote) quote = 0;
          } else if (c === 34 || c === 39) {
            quote = c;
          } else if (c === 62) {
            i += 1;
            break;
          }
          i += 1;
        }
      }
      if (est > budgetBytes) {
        throw new DomLimitExceededError(
          `待解析 XML 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
        );
      }
    } else {
      const nextLt = xml.indexOf('<', i);
      const stop = nextLt >= 0 ? nextLt : len;
      if (stop > i) {
        est += TEXT_NODE_BASE_BYTES + CHILD_SLOT_BYTES + 2 * (stop - i);
        if (est > budgetBytes) {
          throw new DomLimitExceededError(
            `待解析 XML 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
          );
        }
      }
      i = stop;
    }
  }
}

export class PluginDomParser {
  constructor(
    maxDocsPerCall = MAX_LIVE_DOCS,
    maxDocBytes = MAX_DOC_BYTES,
    maxDocMemoryBytes = MAX_DOC_MEMORY_BYTES,
    maxSelectorSteps = MAX_SELECTOR_STEPS,
    maxVmMemoryBytes = MAX_VM_MEMORY_BYTES,
    maxSelectorMemoryBytes = MAX_SELECTOR_MEMORY_BYTES,
    maxOutputBytes = MAX_OUTPUT_BYTES,
  ) {
    this.maxDocsPerCall = maxDocsPerCall;
    this.maxDocBytes = maxDocBytes;
    this.maxDocMemoryBytes = maxDocMemoryBytes;
    this.maxSelectorSteps = maxSelectorSteps;
    this.maxVmMemoryBytes = maxVmMemoryBytes;
    this.maxSelectorMemoryBytes = maxSelectorMemoryBytes;
    this.maxOutputBytes = maxOutputBytes;
    this.nextId = 1;
    this.docs = new Map();
    this.nodes = new Map();
    this.totalNodes = 0;
    this.vmDocBytes = 0;
    this.vmHandleBytes = 0;
    this.deadlineMs = Infinity;
    this._xmlParser = new DOMParser();
  }

  get docCount() {
    return this.docs.size;
  }

  get handleCount() {
    return this.nodes.size;
  }

  get currentEstimatedDomBytes() {
    return this.vmDocBytes;
  }

  get currentVmMemoryBytes() {
    return this.vmDocBytes + this.vmHandleBytes;
  }

  setCallDeadlineMs(deadlineMs) {
    this.deadlineMs = deadlineMs;
  }

  parseHtml(html) {
    checkDeadline(this.deadlineMs);
    const text = String(html ?? '');
    const budgetBytes = this._checkPreParseLimits(text);
    const adapter = createBudgetedParse5TreeAdapter(budgetBytes, this.deadlineMs);
    const document = parse5.parse(text, { treeAdapter: adapter });
    const { elementCount, estimatedBytes } = this._normalizeAndEstimateDoc(document, true, budgetBytes);
    return this._registerDocument(document, false, elementCount, estimatedBytes);
  }

  parseXml(xml) {
    checkDeadline(this.deadlineMs);
    const text = String(xml ?? '');
    const budgetBytes = this._checkPreParseLimits(text);
    preScanXmlLowerBound(text, budgetBytes, this.deadlineMs);
    const document = this._xmlParser.parseFromString(text, 'text/xml');
    const { elementCount, estimatedBytes } = this._normalizeAndEstimateDoc(document, false, budgetBytes);
    return this._registerDocument(document, true, elementCount, estimatedBytes);
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
    this.vmDocBytes = Math.max(0, this.vmDocBytes - docEntry.estimatedBytes);
    this.vmHandleBytes = Math.max(0, this.vmHandleBytes - docEntry.handleTableBytes);
    return true;
  }

  selectFirstId(nodeId, selector) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return -1;
    const parsed = parseSelector(selector);
    const remVm = Math.max(0, this.maxVmMemoryBytes - (this.vmDocBytes + this.vmHandleBytes));
    const found = queryDescendants(
      entry.el,
      parsed,
      entry.isXml,
      true,
      this.maxSelectorSteps,
      this.maxSelectorMemoryBytes,
      remVm,
      this.deadlineMs,
    );
    if (!found) return -1;
    return this._registerElement(entry.docId, found);
  }

  selectAllIds(nodeId, selector) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const parsed = parseSelector(selector);
    const remVm = Math.max(0, this.maxVmMemoryBytes - (this.vmDocBytes + this.vmHandleBytes));
    const list = queryDescendants(
      entry.el,
      parsed,
      entry.isXml,
      false,
      this.maxSelectorSteps,
      this.maxSelectorMemoryBytes,
      remVm,
      this.deadlineMs,
    );
    const out = new Array(list.length);
    for (let i = 0; i < list.length; i++) {
      if ((i & 1023) === 0) checkDeadline(this.deadlineMs);
      out[i] = this._registerElement(entry.docId, list[i]);
    }
    return out;
  }

  childrenIds(nodeId) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return [];
    const raw = getChildNodes(entry.el);
    const out = [];
    for (let i = 0; i < raw.length; i++) {
      if ((i & 1023) === 0) checkDeadline(this.deadlineMs);
      if (isElementNode(raw[i])) {
        out.push(this._registerElement(entry.docId, raw[i]));
      }
    }
    return out;
  }

  tag(nodeId) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    if (entry.isDocument) return '#document';
    const raw = getRawTagName(entry.el);
    return entry.isXml ? raw : raw.toLowerCase();
  }

  text(nodeId) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    const out = new BoundedOutputBuffer(this.maxOutputBytes, this.deadlineMs);
    if (entry.isXml) {
      this._collectXmlTextIterative(entry.el, out);
    } else {
      this._collectHtmlTextIterative(entry.el, out);
    }
    return collapseAsciiAndNbspWhitespace(out.toString(), this.maxOutputBytes, this.deadlineMs);
  }

  html(nodeId) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    const out = new BoundedOutputBuffer(this.maxOutputBytes, this.deadlineMs);
    serializeChildrenIterative(entry.el, entry.isXml, out, this.deadlineMs);
    return out.toString();
  }

  outerHtml(nodeId) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return '';
    const out = new BoundedOutputBuffer(this.maxOutputBytes, this.deadlineMs);
    if (entry.isDocument) {
      serializeChildrenIterative(entry.el, entry.isXml, out, this.deadlineMs);
    } else {
      serializeNodeIterative(entry.el, entry.isXml, out, this.deadlineMs);
    }
    return out.toString();
  }

  attr(nodeId, name) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry || entry.isDocument) return null;
    return getElementAttrWithDeadline(entry.el, String(name), entry.isXml, this.deadlineMs);
  }

  attrsJson(nodeId) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    const attrs = {};
    let bytes = 2;
    let ops = 0;
    if (entry && !entry.isDocument) {
      for (const a of getElementAttributes(entry.el)) {
        if ((++ops & 255) === 0) checkDeadline(this.deadlineMs);
        const key = entry.isXml ? a.name : String(a.name).toLowerCase();
        const val = a.value ?? '';
        bytes += utf8ByteLength(key) + utf8ByteLength(val) + 6;
        if (bytes > this.maxOutputBytes) {
          throw new DomLimitExceededError(`DOM 属性序列化输出超过上限（最多 ${Math.round(this.maxOutputBytes / (1024 * 1024))} MB）`);
        }
        attrs[key] = val;
      }
    }
    return JSON.stringify(attrs);
  }

  /**
   * Releases all live documents and handles.
   * Deliberately does NOT reset `nextId` so stale handles never collide.
   */
  clear() {
    this.nodes.clear();
    this.docs.clear();
    this.totalNodes = 0;
    this.vmDocBytes = 0;
    this.vmHandleBytes = 0;
    this.deadlineMs = Infinity;
  }

  _allocateHandleId() {
    const id = this.nextId++;
    if (!Number.isSafeInteger(id) || id <= 0) {
      throw new DomLimitExceededError('DOM 句柄编号超过安全范围');
    }
    return id;
  }

  _collectXmlTextIterative(root, out) {
    const stack = [root];
    let ops = 0;
    while (stack.length > 0) {
      if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
      const node = stack.pop();
      if (!node) continue;
      if (isTextNode(node) || isCDataNode(node)) {
        out.appendString(getNodeTextValue(node));
        continue;
      }
      if (!isElementNode(node) && !isDocumentNode(node)) continue;
      const ch = getChildNodes(node);
      for (let i = ch.length - 1; i >= 0; i--) {
        if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
        stack.push(ch[i]);
      }
    }
  }

  _collectHtmlTextIterative(root, out) {
    const SPACE_MARKER = Symbol('space');
    const stack = [root];
    let ops = 0;
    while (stack.length > 0) {
      if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
      const item = stack.pop();
      if (!item) continue;
      if (item === SPACE_MARKER) {
        out.appendAscii(' ');
        continue;
      }
      if (isTextNode(item) || isCDataNode(item)) {
        out.appendString(getNodeTextValue(item));
        continue;
      }
      if (isDocumentNode(item)) {
        const ch = getChildNodes(item);
        for (let i = ch.length - 1; i >= 0; i--) {
          if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
          stack.push(ch[i]);
        }
        continue;
      }
      if (!isElementNode(item)) continue;
      const tag = getRawTagName(item).toLowerCase();
      if (HTML_SKIP_TEXT_TAGS.has(tag)) continue;
      const isBlock = HTML_BLOCK_TAGS.has(tag);
      if (isBlock) {
        out.appendAscii(' ');
        stack.push(SPACE_MARKER);
      }
      const ch = getChildNodes(item);
      for (let i = ch.length - 1; i >= 0; i--) {
        if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
        stack.push(ch[i]);
      }
    }
  }

  _checkPreParseLimits(text) {
    if (this.docs.size >= this.maxDocsPerCall) {
      throw new DomLimitExceededError(`同时存活的文档数超过上限（最多 ${this.maxDocsPerCall} 个）`);
    }
    if (Buffer.byteLength(text, 'utf8') > this.maxDocBytes) {
      throw new DomLimitExceededError(`解析文档超过大小限制（上限 ${Math.round(this.maxDocBytes / (1024 * 1024))} MB）`);
    }
    const vmRem = this.maxVmMemoryBytes - (this.vmDocBytes + this.vmHandleBytes) - HANDLE_ENTRY_BYTES;
    if (vmRem < DOC_BASE_BYTES) {
      throw new DomLimitExceededError(`VM 存活 DOM 总内存超过上限（最多 ${Math.round(this.maxVmMemoryBytes / (1024 * 1024))} MB）`);
    }
    return Math.min(this.maxDocMemoryBytes, vmRem);
  }

  /**
   * Flattens `<template>.content` in parse5 trees and computes exact calibrated
   * estimated bytes across ALL node kinds (Elements, Text, Comments, CDATA, PIs,
   * Doctype, Attributes), enforcing `budgetBytes`.
   */
  _normalizeAndEstimateDoc(doc, isParse5Html, budgetBytes) {
    let bytes = DOC_BASE_BYTES;
    let elementCount = 0;
    const stack = [];
    const rootChildren = getChildNodes(doc);
    if (rootChildren.length > 0) {
      bytes += CHILD_LIST_BASE_BYTES + CHILD_SLOT_BYTES * rootChildren.length;
      for (let i = rootChildren.length - 1; i >= 0; i--) stack.push(rootChildren[i]);
    }
    let ops = 0;
    while (stack.length > 0) {
      if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
      const cur = stack.pop();
      if (!cur) continue;
      if (isElementNode(cur)) {
        elementCount += 1;
        bytes += ELEMENT_BASE_BYTES;
        if (isParse5Html && cur.content && Array.isArray(cur.content.childNodes) && cur.content.childNodes.length > 0) {
          const tmplChildren = cur.content.childNodes;
          for (const tc of tmplChildren) tc.parentNode = cur;
          cur.childNodes = tmplChildren;
          cur.content = null;
        }
        const attrs = getElementAttributes(cur);
        if (attrs.length > 0) {
          bytes += ATTRS_CONTAINER_BYTES;
          for (let i = 0; i < attrs.length; i++) {
            if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
            const a = attrs[i];
            bytes += ATTR_ENTRY_BYTES + 2 * ((a.name ? String(a.name).length : 0) + (a.value ? String(a.value).length : 0));
          }
        }
        const ch = getChildNodes(cur);
        if (ch.length > 0) {
          bytes += CHILD_LIST_BASE_BYTES + CHILD_SLOT_BYTES * ch.length;
          for (let i = ch.length - 1; i >= 0; i--) stack.push(ch[i]);
        }
      } else if (isTextNode(cur) || isCDataNode(cur)) {
        bytes += TEXT_NODE_BASE_BYTES + 2 * getNodeTextValue(cur).length;
      } else if (isCommentNode(cur)) {
        bytes += COMMENT_NODE_BASE_BYTES + 2 * getNodeCommentData(cur).length;
      } else {
        const extra = (cur.name ? String(cur.name).length : 0) +
          (cur.data ? String(cur.data).length : 0) +
          (cur.publicId ? String(cur.publicId).length : 0) +
          (cur.systemId ? String(cur.systemId).length : 0);
        bytes += OTHER_NODE_BASE_BYTES + 2 * extra;
      }
      if (bytes > budgetBytes) {
        throw new DomLimitExceededError(
          `解析后的 DOM 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
        );
      }
    }
    return { elementCount, estimatedBytes: bytes };
  }

  _registerDocument(doc, isXml, elementCount, estimatedBytes) {
    if (this.docs.size >= this.maxDocsPerCall) {
      throw new DomLimitExceededError(`同时存活的文档数超过上限（最多 ${this.maxDocsPerCall} 个）`);
    }
    if (estimatedBytes > this.maxDocMemoryBytes) {
      throw new DomLimitExceededError(
        `单个文档估算内存超过上限（最多 ${Math.round(this.maxDocMemoryBytes / (1024 * 1024))} MB）`,
      );
    }
    if (this.vmDocBytes + this.vmHandleBytes + estimatedBytes + HANDLE_ENTRY_BYTES > this.maxVmMemoryBytes) {
      throw new DomLimitExceededError(
        `VM 存活 DOM 总内存超过上限（最多 ${Math.round(this.maxVmMemoryBytes / (1024 * 1024))} MB）`,
      );
    }
    const docId = this._allocateHandleId();
    const elementToHandleId = new Map();
    elementToHandleId.set(doc, docId);
    const docEntry = {
      docId,
      doc,
      isXml,
      elementCount,
      estimatedBytes,
      handleTableBytes: HANDLE_ENTRY_BYTES,
      elementToHandleId,
      handleIds: [docId],
    };
    this.docs.set(docId, docEntry);
    this.nodes.set(docId, { docId, el: doc, isXml, isDocument: true });
    this.totalNodes += elementCount;
    this.vmDocBytes += estimatedBytes;
    this.vmHandleBytes += HANDLE_ENTRY_BYTES;
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
    if (this.vmDocBytes + this.vmHandleBytes + HANDLE_ENTRY_BYTES > this.maxVmMemoryBytes) {
      throw new DomLimitExceededError(
        `VM 存活 DOM 与句柄表总内存超过上限（最多 ${Math.round(this.maxVmMemoryBytes / (1024 * 1024))} MB）`,
      );
    }
    const id = this._allocateHandleId();
    docEntry.elementToHandleId.set(el, id);
    docEntry.handleIds.push(id);
    docEntry.handleTableBytes += HANDLE_ENTRY_BYTES;
    this.vmHandleBytes += HANDLE_ENTRY_BYTES;
    this.nodes.set(id, { docId, el, isXml: docEntry.isXml, isDocument: false });
    return id;
  }
}
