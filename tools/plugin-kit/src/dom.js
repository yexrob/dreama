import { DOMParser } from 'linkedom';
import * as parse5 from 'parse5';

/**
 * §11.1 (S4) Byte-budgeted DOM parser mirroring Android `PluginDomParser.kt`:
 * - Max input text: 2 MB UTF-8
 * - Max tag / attribute name length: 256 chars (rejected during parse)
 * - Max single document estimated memory (host model): 32 MB
 * - Max single-VM live DOM memory (live docs + handle table + active class cache): 48 MB
 * - Max process-wide live DOM memory across ALL plugin VMs: 64 MB
 * - Max simultaneously live documents per VM: 8
 * - Max serialization output (`html()`, `outerHtml()`, `text()`, escaped `attrsJson()`): 8 MB
 * - Uses the EXACT SAME Android/Jsoup structural byte model & constants so `d3plugin`
 *   produces matching byte estimates (within ±5%) and identical accept/reject decisions
 *   for normal pages (2 MB XML MPD/RSS/XSPF, table_min/table_pretty/apache2/tracker2,
 *   >90k simple HTML elements) and adversarial inputs alike; additionally enforces a
 *   loose 512 MB V8 actual-memory safety net to protect the developer Node process
 * - Per-document deduplicated monotonic handle IDs (never reset on `clear()`)
 * - O(N * S) memoized CSS selector engine with hard syntax caps (4 KB, 32 groups,
 *   32 compounds, nesting 4, value 256, `:nth-*` ±1e6), O(1)-per-parent `~` scan,
 *   lazy `-of-type` sibling tables, cached `:empty`, in-place short-class scanning,
 *   budgeted loops everywhere, and entry deadline checks
 */
export const MAX_LIVE_DOCS = 8;
export const MAX_DOCS_PER_CALL = MAX_LIVE_DOCS;
export const MAX_DOC_BYTES = 2 * 1024 * 1024;
export const MAX_DOC_MEMORY_BYTES = 32 * 1024 * 1024;
export const MAX_VM_MEMORY_BYTES = 48 * 1024 * 1024;
export const MAX_PROCESS_MEMORY_BYTES = 64 * 1024 * 1024;
export const MAX_V8_SAFETY_MEMORY_BYTES = 512 * 1024 * 1024;
export const MAX_NAME_LENGTH = 256;
export const MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
export const MAX_SELECTOR_STEPS = 2_000_000;
export const MAX_SELECTOR_MEMORY_BYTES = 16 * 1024 * 1024;
export const MAX_SELECTOR_BYTES = 4 * 1024;
export const MAX_SELECTOR_GROUPS = 32;
export const MAX_COMPOUNDS_PER_SELECTOR = 32;
export const MAX_PSEUDO_NESTING = 4;
export const MAX_SELECTOR_VALUE_LENGTH = 256;
export const MAX_NTH_ABS = 1_000_000;

// Host (Android / Jsoup 1.24.1 compacted) structural byte constants — identical to PluginDomParser.kt
export const DOC_BASE_BYTES = 1024;
export const ELEMENT_BASE_BYTES = 36;
export const FORM_ELEMENT_EXTRA_BYTES = 24;
export const CUSTOM_TAG_BASE_BYTES = 32;
export const LEAF_NODE_BASE_BYTES = 28;
export const PI_NODE_BASE_BYTES = 36;
export const XML_DECL_BASE_BYTES = 36;
export const DOCTYPE_BASE_BYTES = 28;
export const OTHER_NODE_BASE_BYTES = 48;
export const HANDLE_ENTRY_BYTES = 184;

// Legacy aliases kept for external callers/tests
export const CHILD_LIST_BASE_BYTES = 48;
export const CHILD_SLOT_BYTES = 4;
export const ATTRS_CONTAINER_BYTES = 56;
export const ATTR_ENTRY_BYTES = 8;
export const TEXT_NODE_BASE_BYTES = LEAF_NODE_BASE_BYTES;
export const COMMENT_NODE_BASE_BYTES = LEAF_NODE_BASE_BYTES;

// Rough V8 actual-memory constants used ONLY for the loose 512 MB Node safety net
const V8_HTML_ELEM_BYTES = 152;
const V8_HTML_CHILD_LIST_BYTES = 140;
const V8_HTML_CHILD_SLOT_BYTES = 8;
const V8_HTML_ATTRS_CONTAINER_BYTES = 120;
const V8_HTML_ATTR_ENTRY_BYTES = 56;
const V8_HTML_TEXT_BYTES = 64;
const V8_HTML_COMMENT_BYTES = 54;
const V8_HTML_OTHER_BYTES = 160;
const V8_XML_ELEM_BYTES = 430;
const V8_XML_ATTRS_CONTAINER_BYTES = 40;
const V8_XML_ATTR_ENTRY_BYTES = 265;
const V8_XML_CDATA_BYTES = 275;
const V8_XML_TEXT_BYTES = 175;
const V8_XML_COMMENT_BYTES = 175;
const V8_XML_OTHER_BYTES = 160;

// Exact Jsoup 1.24.1 TagSet.HtmlTagSet known tags across HTML, SVG, and MathML namespaces
const KNOWN_XHTML_TAGS = new Set([
  '#root', 'a', 'abbr', 'acronym', 'address', 'applet', 'area', 'article', 'aside', 'audio',
  'b', 'base', 'basefont', 'bdi', 'bdo', 'bgsound', 'big', 'blockquote', 'body', 'br', 'button',
  'canvas', 'caption', 'center', 'cite', 'code', 'col', 'colgroup', 'data', 'datalist', 'dd',
  'del', 'details', 'dfn', 'dialog', 'dir', 'div', 'dl', 'dt', 'em', 'embed', 'fieldset',
  'figcaption', 'figure', 'font', 'footer', 'form', 'frame', 'frameset', 'h1', 'h2', 'h3', 'h4',
  'h5', 'h6', 'head', 'header', 'hgroup', 'hr', 'html', 'i', 'iframe', 'img', 'input', 'ins',
  'kbd', 'keygen', 'label', 'legend', 'li', 'link', 'listing', 'main', 'map', 'mark', 'marquee',
  'menu', 'meta', 'meter', 'nav', 'nobr', 'noembed', 'noframes', 'noscript', 'object', 'ol',
  'optgroup', 'option', 'output', 'p', 'param', 'picture', 'plaintext', 'pre', 'progress', 'q',
  'rb', 'rp', 'rt', 'rtc', 'ruby', 's', 'samp', 'script', 'search', 'section', 'select', 'slot',
  'small', 'source', 'span', 'strike', 'strong', 'style', 'sub', 'summary', 'sup', 'table',
  'tbody', 'td', 'template', 'textarea', 'tfoot', 'th', 'thead', 'time', 'title', 'tr', 'track',
  'tt', 'u', 'ul', 'var', 'video', 'wbr', 'xmp',
]);
const KNOWN_SVG_TAGS = new Set(['femerge', 'femergenode', 'script', 'svg', 'text']);
const KNOWN_MATHML_TAGS = new Set(['math', 'mi', 'mn', 'mo', 'msup', 'mtext']);

function isKnownJsoupHtmlTag(tagNameLower, namespaceURI) {
  if (!namespaceURI || namespaceURI === 'http://www.w3.org/1999/xhtml') {
    return KNOWN_XHTML_TAGS.has(tagNameLower);
  }
  if (namespaceURI === 'http://www.w3.org/2000/svg') {
    return KNOWN_SVG_TAGS.has(tagNameLower);
  }
  if (namespaceURI === 'http://www.w3.org/1998/Math/MathML') {
    return KNOWN_MATHML_TAGS.has(tagNameLower);
  }
  return false;
}

function align8(n) {
  return Math.ceil(n / 8) * 8;
}

export function attrsContainerBytes(attrCount) {
  if (attrCount <= 0) return 0;
  // Matches compacted Jsoup Attributes: object (24B) + String[cap] + Object[cap]
  return 24 + 2 * (16 + align8(attrCount * 4));
}

export function childListBytes(childCount) {
  if (childCount <= 0) return 0;
  // Matches compacted Jsoup Element$NodeList (32B) + exact-sized Object[childCount]
  return 32 + 16 + align8(childCount * 4);
}

/**
 * Updates Jsoup's simulated 512-slot `CharacterReader.stringCache` without charging bytes
 * (used for tag names read during lexing so known-tag cache evictions such as `"svg"` vs `"d"`
 * match Android Jsoup accurately).
 */
function touchStringCache(s, stringCache) {
  if (!s || s.length === 0 || s.length > 12) return;
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  }
  stringCache[h & 511] = s;
}

function countCharCode(s, code) {
  let c = 0;
  for (let i = 0; i < s.length; i++) {
    if (s.charCodeAt(i) === code) c += 1;
  }
  return c;
}

/**
 * Computes the fraction of `\n` characters produced by parse5 that originated from `\r\n`
 * (which parse5 normalizes to `\n`, whereas Jsoup 1.24.1 preserves `\r\n` verbatim).
 * Returns 0 immediately for LF-only documents.
 */
function computeHtmlCrlfRatio(text) {
  if (!text.includes('\r')) return 0;
  let crlf = 0;
  let totalOutNl = 0;
  const len = text.length;
  for (let i = 0; i < len; i++) {
    const c = text.charCodeAt(i);
    if (c === 13) {
      totalOutNl += 1;
      if (i + 1 < len && text.charCodeAt(i + 1) === 10) {
        crlf += 1;
        i += 1;
      }
    } else if (c === 10) {
      totalOutNl += 1;
    }
  }
  return totalOutNl > 0 ? (crlf / totalOutNl) : 0;
}

/**
 * Mirrors Jsoup's `CharacterReader.cacheString` (len <= 12, 512-slot Java String.hashCode
 * table) + `PluginDomParser.exactStrBytes` (Latin-1 vs UTF-16 + >512 KB G1GC humongous
 * 1 MB region rounding). Pass `bypassCache = true` for strings produced via Jsoup's
 * `TokenData` / `Tokeniser.unescapeEntities` / HTML character reference decoding (which
 * allocate fresh Strings bypassing `CharacterReader.stringCache`). Pass `extraLen` for
 * `\r` characters stripped by parse5 CRLF normalization that Jsoup retains verbatim.
 */
function exactStrBytesHost(s, stringCache, bypassCache = false, extraLen = 0) {
  if (!s || (s.length === 0 && extraLen <= 0)) return 0;
  const len = s.length;
  const totalLen = len + extraLen;
  if (!bypassCache && extraLen === 0 && totalLen <= 12) {
    let h = 0;
    for (let i = 0; i < len; i++) {
      h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
    }
    const slot = h & 511;
    if (stringCache[slot] === s) return 0;
    stringCache[slot] = s;
  }
  let wide = false;
  for (let i = 0; i < len; i++) {
    if (s.charCodeAt(i) > 0xFF) { wide = true; break; }
  }
  const dataBytes = wide ? totalLen * 2 : totalLen;
  const backingBytes = 16 + dataBytes;
  if (backingBytes > 512 * 1024) {
    const mb = 1024 * 1024;
    return 24 + Math.ceil(backingBytes / mb) * mb;
  }
  return 24 + 16 + align8(dataBytes);
}

const MAX_JSOUP_NESTING_DEPTH = 512;

const MEMO_UNCOMPUTED = 0;
const MEMO_FALSE = 1;
const MEMO_TRUE = 2;
const LONG_CLASS_CACHE_THRESHOLD = 128;
const PROCESS_RESERVE_CHUNK_BYTES = 256 * 1024;

class ProcessDomMemoryLedger {
  constructor() {
    this.totalReservedBytes = 0;
    this.holders = new Set();
    this._registry = typeof FinalizationRegistry === 'function'
      ? new FinalizationRegistry((holder) => {
        this.holders.delete(holder);
        this.releaseAll(holder);
      })
      : null;
  }

  register(parser) {
    const holder = {
      ref: typeof WeakRef === 'function' ? new WeakRef(parser) : null,
      reservedBytes: 0,
    };
    this.holders.add(holder);
    if (this._registry) this._registry.register(parser, holder);
    return holder;
  }

  reapDeadHolders() {
    for (const h of this.holders) {
      if (h.ref && h.ref.deref() === undefined) {
        this.holders.delete(h);
        this.releaseAll(h);
      }
    }
  }

  currentTotalBytes() {
    this.reapDeadHolders();
    return this.totalReservedBytes;
  }

  remainingBytes(limitBytes) {
    return Math.max(0, limitBytes - this.totalReservedBytes);
  }

  tryReserve(holder, delta, limitBytes) {
    if (delta <= 0) return true;
    if (this.totalReservedBytes + delta <= limitBytes) {
      this.totalReservedBytes += delta;
      holder.reservedBytes += delta;
      return true;
    }
    this.reapDeadHolders();
    if (this.totalReservedBytes + delta <= limitBytes) {
      this.totalReservedBytes += delta;
      holder.reservedBytes += delta;
      return true;
    }
    return false;
  }

  release(holder, delta) {
    if (delta <= 0) return;
    const actual = Math.min(holder.reservedBytes, delta);
    if (actual > 0) {
      holder.reservedBytes -= actual;
      this.totalReservedBytes = Math.max(0, this.totalReservedBytes - actual);
    }
  }

  releaseAll(holder) {
    const prev = holder.reservedBytes;
    if (prev > 0) {
      holder.reservedBytes = 0;
      this.totalReservedBytes = Math.max(0, this.totalReservedBytes - prev);
    }
  }
}

const GLOBAL_PROCESS_LEDGER = new ProcessDomMemoryLedger();
const GLOBAL_V8_SAFETY_LEDGER = new ProcessDomMemoryLedger();

export function currentProcessDomMemoryBytes() {
  return GLOBAL_PROCESS_LEDGER.currentTotalBytes();
}

export function currentV8SafetyMemoryBytes() {
  return GLOBAL_V8_SAFETY_LEDGER.currentTotalBytes();
}

function flattenStrV8(s) {
  if (typeof s !== 'string' || s.length < 16) return s;
  return Buffer.from(s, 'utf8').toString('utf8');
}

function strBytesV8(s) {
  if (!s || !s.length) return 0;
  let wide = false;
  for (let i = 0; i < s.length; i++) {
    if (s.charCodeAt(i) > 0xFF) { wide = true; break; }
  }
  const data = wide ? s.length * 2 : s.length;
  return 16 + ((data + 7) & ~7);
}

function asciiLowerFast(s) {
  const str = String(s ?? '');
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    if ((c >= 65 && c <= 90) || c >= 0x80) return str.toLowerCase();
  }
  return str;
}

function appendJsonEscapedString(s, out) {
  out.appendAscii('"');
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    const code = s.charCodeAt(i);
    let rep = null;
    if (code === 0x22) rep = '\\"';
    else if (code === 0x5C) rep = '\\\\';
    else if (code === 0x08) rep = '\\b';
    else if (code === 0x0C) rep = '\\f';
    else if (code === 0x0A) rep = '\\n';
    else if (code === 0x0D) rep = '\\r';
    else if (code === 0x09) rep = '\\t';
    else if (code < 0x20) rep = '\\u00' + code.toString(16).padStart(2, '0');
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
  out.appendAscii('"');
}

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
    const isNested = this.nestingDepth > 0;
    const needsMatchStepMemo = compounds.map((_, idx) => {
      if (idx === compounds.length - 1) return isNested;
      const comb = combinators[idx];
      if (comb === '~') return false;
      if (comb === '+') {
        if (isNested) return true;
        for (let k = idx + 1; k < combinators.length; k++) {
          if (combinators[k] !== '+' && combinators[k] !== '~') return true;
        }
        return false;
      }
      return true;
    });
    return { compounds, combinators, isNested, needsMatchStepMemo };
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
    return {
      nsPrefix,
      nsPrefixLower: nsPrefix === null ? null : asciiLowerFast(nsPrefix),
      tagName,
      tagNameLower: tagName === null ? null : asciiLowerFast(tagName),
      ids,
      classes,
      attrs,
      pseudos,
    };
  }

  parseAttrSelector() {
    this.pos += 1;
    this.skipWs();
    const name = this.readIdent();
    const nameLower = asciiLowerFast(name);
    this.skipWs();
    if (this.peek() === ']') {
      this.pos += 1;
      return { name, nameLower, op: 'exists', value: null, caseFlag: null, kmpLps: null };
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
    return { name, nameLower, op, value, caseFlag, kmpLps };
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

function matchesTagPreNormalized(actual, ns, wanted) {
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
    this.lowerCache = new Map();
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

  cachedLower(s) {
    let low = this.lowerCache.get(s);
    if (low !== undefined) return low;
    low = asciiLowerFast(s);
    if (this.lowerCache.size < 2048) this.lowerCache.set(s, low);
    return low;
  }

  getElementAttrBudgeted(el, name, preLowerName = null) {
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
    const lower = preLowerName ?? this.cachedLower(String(name));
    for (let i = 0; i < attrs.length; i++) {
      budgetChunk += 1;
      if (budgetChunk >= 64) {
        this.tick(budgetChunk);
        budgetChunk = 0;
      }
      const ak = attrs[i].name;
      if (ak === lower || this.cachedLower(String(ak)) === lower) {
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
        siblingScanUpTo: null,
        siblingFirstMatch: null,
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

  getSiblingFirstMatch(memo, parent, idx) {
    const row = memo.siblingFirstMatch?.get(parent);
    return row ? row[idx] : -1;
  }

  getSiblingScanUpTo(memo, parent, idx) {
    const row = memo.siblingScanUpTo?.get(parent);
    return row ? row[idx] : 0;
  }

  setSiblingState(memo, parent, idx, scanUpTo, firstMatch) {
    if (!memo.siblingScanUpTo) {
      memo.siblingScanUpTo = new Map();
      memo.siblingFirstMatch = new Map();
    }
    let scanRow = memo.siblingScanUpTo.get(parent);
    let firstRow = memo.siblingFirstMatch.get(parent);
    if (!scanRow || !firstRow) {
      this.chargeMemory(64 + 8 * memo.combRowLen);
      scanRow = new Int32Array(memo.combRowLen);
      firstRow = new Int32Array(memo.combRowLen).fill(-1);
      memo.siblingScanUpTo.set(parent, scanRow);
      memo.siblingFirstMatch.set(parent, firstRow);
    }
    scanRow[idx] = scanUpTo;
    firstRow[idx] = firstMatch;
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
    let index0Based = null;
    if (n > 1) {
      this.chargeMemory(20 * n + 128);
      index0Based = new Map();
      for (let i = 0; i < n; i++) {
        if ((i & 1023) === 0) checkDeadline(this.deadlineMs);
        index0Based.set(children[i], i);
      }
    } else {
      this.chargeMemory(64);
    }
    table = {
      children,
      totalChildren: n,
      index0Based,
      typeIndex1Based: null,
      typeTotal: null,
      indexOf(el) {
        if (n === 0) return -1;
        if (!index0Based) return children[0] === el ? 0 : -1;
        const pos = index0Based.get(el);
        return pos === undefined ? -1 : pos;
      },
    };
    this.siblingTables.set(parent, table);
    return table;
  }

  ensureOfType(table) {
    if (table.typeIndex1Based !== null) return;
    const n = table.totalChildren;
    this.chargeMemory(12 * n + 64);
    const typeIndex1Based = new Int32Array(n);
    const typeTotal = new Int32Array(n);
    const typeCounts = new Map();
    const tags = new Array(n);
    for (let i = 0; i < n; i++) {
      const c = table.children[i];
      this.tick(1);
      const rawTag = getRawTagName(c);
      const tag = this.isXml ? rawTag : this.cachedLower(rawTag);
      const cnt = (typeCounts.get(tag) || 0) + 1;
      typeCounts.set(tag, cnt);
      typeIndex1Based[i] = cnt;
      tags[i] = tag;
    }
    for (let i = 0; i < n; i++) {
      typeTotal[i] = typeCounts.get(tags[i]);
    }
    table.typeIndex1Based = typeIndex1Based;
    table.typeTotal = typeTotal;
  }

  prevElementSiblingFast(el) {
    const p = getParentNode(el);
    if (!p) return null;
    const t = this.siblingTableFor(p);
    const i = t.indexOf(el);
    return i > 0 ? t.children[i - 1] : null;
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
    const shouldMemo = stepCount > 1 && sel.needsMatchStepMemo[idx] && (!isTopLevel || idx < stepCount - 1);
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
    const p = getParentNode(el);
    if (!p) return false;
    const table = this.siblingTableFor(p);
    const pos = table.indexOf(el);
    if (pos <= 0) return false;
    const memo = this.memoFor(sel);
    const first = this.getSiblingFirstMatch(memo, p, idx);
    if (first >= 0) return pos > first;
    let scanned = this.getSiblingScanUpTo(memo, p, idx);
    while (scanned < pos) {
      const sib = table.children[scanned];
      const matched = this.evalMatchStep(sib, sel, idx, false);
      scanned += 1;
      if (matched) {
        this.setSiblingState(memo, p, idx, scanned, scanned - 1);
        return true;
      }
    }
    this.setSiblingState(memo, p, idx, scanned, -1);
    return false;
  }

  matchesCompound(el, comp) {
    if (!isElementNode(el)) return false;
    if (comp.nsPrefix !== null || comp.tagName !== null) {
      const rawTag = getRawTagName(el);
      const actual = this.isXml ? rawTag : this.cachedLower(rawTag);
      const ns = this.isXml ? comp.nsPrefix : comp.nsPrefixLower;
      const wanted = this.isXml ? comp.tagName : comp.tagNameLower;
      if (!matchesTagPreNormalized(actual, ns, wanted)) return false;
    }
    for (const id of comp.ids) {
      const actual = this.getElementAttrBudgeted(el, 'id', 'id');
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
    const actual = this.getElementAttrBudgeted(el, am.name, am.nameLower);
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
        return Boolean(p) && this.siblingTableFor(p).indexOf(el) === 0;
      }
      case 'last-child': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        return t.indexOf(el) === t.totalChildren - 1;
      }
      case 'only-child': {
        const p = getParentNode(el);
        return Boolean(p) && this.siblingTableFor(p).totalChildren === 1;
      }
      case 'first-of-type': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        const pos0 = t.indexOf(el);
        if (pos0 < 0) return false;
        this.ensureOfType(t);
        return t.typeIndex1Based[pos0] === 1;
      }
      case 'last-of-type': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        const pos0 = t.indexOf(el);
        if (pos0 < 0) return false;
        this.ensureOfType(t);
        return t.typeIndex1Based[pos0] === t.typeTotal[pos0];
      }
      case 'only-of-type': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        const pos0 = t.indexOf(el);
        if (pos0 < 0) return false;
        this.ensureOfType(t);
        return t.typeTotal[pos0] === 1;
      }
      case 'empty':
        return this.matchesEmptyCached(el);
      case 'root':
        return isDocumentNode(getParentNode(el));
      case 'nth': {
        const p = getParentNode(el);
        if (!p) return false;
        const t = this.siblingTableFor(p);
        const pos0 = t.indexOf(el);
        if (pos0 < 0) return false;
        let idx;
        if (pm.ofType) {
          this.ensureOfType(t);
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
 * Doctype, Attributes) live during HTML5 parsing using the Android host byte
 * model (against `budgetBytes` / 64 MB process ledger) AND a loose V8 actual
 * memory safety net (default 512 MB) so developer Node processes cannot OOM.
 */
function createBudgetedParse5TreeAdapter(
  budgetBytes,
  deadlineMs,
  processLedger,
  processHolder,
  maxProcessMemoryBytes,
  v8SafetyLedger,
  v8SafetyHolder,
  maxV8SafetyBytes,
  htmlTracker,
) {
  const base = parse5.defaultTreeAdapter;
  let hostBytes = DOC_BASE_BYTES;
  let v8Bytes = DOC_BASE_BYTES;
  let ops = 0;
  let inFlightHostReserved = 0;
  let inFlightV8Reserved = 0;
  let pendingTextHasEntity = false;
  const seenCustomTags = new Set();
  const stringCache = new Array(512);
  const nodeDepth = new WeakMap();
  const crlfRatio = htmlTracker?.crlfRatio ?? 0;

  function getDepth(node) {
    return nodeDepth.get(node) ?? 0;
  }

  function charge(hostAdd, v8Add) {
    hostBytes += hostAdd;
    v8Bytes += v8Add;
    if ((++ops & 255) === 0) checkDeadline(deadlineMs);
    if (hostBytes > budgetBytes) {
      throw new DomLimitExceededError(
        `解析中的 DOM 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
      );
    }
    const needHost = hostBytes - inFlightHostReserved;
    if (needHost >= PROCESS_RESERVE_CHUNK_BYTES) {
      if (!processLedger.tryReserve(processHolder, needHost, maxProcessMemoryBytes)) {
        throw new DomLimitExceededError(
          `进程级全部插件 VM 存活 DOM 总内存超过上限（最多 ${Math.round(maxProcessMemoryBytes / (1024 * 1024))} MB）`,
        );
      }
      inFlightHostReserved += needHost;
    }
    const needV8 = v8Bytes - inFlightV8Reserved;
    if (needV8 >= PROCESS_RESERVE_CHUNK_BYTES) {
      if (!v8SafetyLedger.tryReserve(v8SafetyHolder, needV8, maxV8SafetyBytes)) {
        throw new DomLimitExceededError(
          `开发工具 V8 兜底 DOM 内存超过上限（最多 ${Math.round(maxV8SafetyBytes / (1024 * 1024))} MB）`,
        );
      }
      inFlightV8Reserved += needV8;
    }
  }

  function releaseInFlight() {
    if (inFlightHostReserved > 0) {
      processLedger.release(processHolder, inFlightHostReserved);
      inFlightHostReserved = 0;
    }
    if (inFlightV8Reserved > 0) {
      v8SafetyLedger.release(v8SafetyHolder, inFlightV8Reserved);
      inFlightV8Reserved = 0;
    }
  }

  return {
    ...base,
    _releaseInFlight: releaseInFlight,
    _markNextTextHasEntity() {
      pendingTextHasEntity = true;
    },
    createElement(tagName, namespaceURI, attrs) {
      pendingTextHasEntity = false;
      const tn = String(tagName ?? '');
      if (tn.length > MAX_NAME_LENGTH) {
        throw new DomLimitExceededError(`DOM 标签名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
      }
      const isExcessDepthClone = Boolean(
        htmlTracker && attrs &&
        htmlTracker.getOpenStackDepth() >= MAX_JSOUP_NESTING_DEPTH &&
        htmlTracker.seenTokenAttrArrays.has(attrs),
      );
      if (htmlTracker && attrs) htmlTracker.seenTokenAttrArrays.add(attrs);

      const norm = asciiLowerFast(tn);
      let hostAdd = isExcessDepthClone ? 0 : ELEMENT_BASE_BYTES;
      if (!isExcessDepthClone && norm === 'form') hostAdd += FORM_ELEMENT_EXTRA_BYTES;
      let v8Add = V8_HTML_ELEM_BYTES;
      if (!isExcessDepthClone) {
        if (!isKnownJsoupHtmlTag(norm, namespaceURI)) {
          const tagKey = `${namespaceURI || ''}\u0000${tn}`;
          if (!seenCustomTags.has(tagKey)) {
            seenCustomTags.add(tagKey);
            hostAdd += CUSTOM_TAG_BASE_BYTES + exactStrBytesHost(tn, stringCache);
            if (norm !== tn) hostAdd += exactStrBytesHost(norm, stringCache);
            v8Add += 24 + strBytesV8(tn);
          } else {
            touchStringCache(tn, stringCache);
          }
        } else {
          touchStringCache(tn, stringCache);
        }
      }
      if (attrs && attrs.length > 0) {
        if (!isExcessDepthClone) hostAdd += attrsContainerBytes(attrs.length);
        v8Add += V8_HTML_ATTRS_CONTAINER_BYTES;
        for (let i = 0; i < attrs.length; i++) {
          const a = attrs[i];
          const k = String(a.name ?? '');
          if (k.length > MAX_NAME_LENGTH) {
            throw new DomLimitExceededError(`DOM 属性名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
          }
          const v = String(a.value ?? '');
          if (!isExcessDepthClone) {
            const hadEnt = Boolean(htmlTracker?.entityAttrs.has(a));
            const extraCr = (crlfRatio > 0 && v.includes('\n')) ? Math.round(countCharCode(v, 10) * crlfRatio) : 0;
            hostAdd += exactStrBytesHost(k, stringCache) + exactStrBytesHost(v, stringCache, hadEnt, extraCr);
          }
          v8Add += V8_HTML_ATTR_ENTRY_BYTES + strBytesV8(v) + (k.length > 2 ? Math.min(24, k.length * 2) : 0);
        }
      }
      charge(hostAdd, v8Add);
      const el = base.createElement(tagName, namespaceURI, attrs);
      if (isExcessDepthClone && htmlTracker) htmlTracker.excessDepthClones.add(el);
      return el;
    },
    createCommentNode(data) {
      pendingTextHasEntity = false;
      const d = String(data ?? '');
      const extraCr = (crlfRatio > 0 && d.includes('\n')) ? Math.round(countCharCode(d, 10) * crlfRatio) : 0;
      // Jsoup Token$Comment builds comment data via TokenData (fresh String, bypassing CharacterReader.stringCache)
      charge(
        LEAF_NODE_BASE_BYTES + exactStrBytesHost(d, stringCache, true, extraCr),
        V8_HTML_COMMENT_BYTES + strBytesV8(d),
      );
      return base.createCommentNode(data);
    },
    createTextNode(value) {
      const hadEnt = pendingTextHasEntity;
      pendingTextHasEntity = false;
      const v = String(value ?? '');
      const extraCr = (crlfRatio > 0 && v.includes('\n')) ? Math.round(countCharCode(v, 10) * crlfRatio) : 0;
      charge(
        LEAF_NODE_BASE_BYTES + exactStrBytesHost(v, stringCache, hadEnt, extraCr),
        V8_HTML_TEXT_BYTES + strBytesV8(v),
      );
      const node = base.createTextNode(value);
      if (hadEnt && htmlTracker) htmlTracker.entityTextNodes.add(node);
      return node;
    },
    setDocumentType(document, name, publicId, systemId) {
      pendingTextHasEntity = false;
      const n = String(name ?? '');
      const pub = String(publicId ?? '');
      const sys = String(systemId ?? '');
      const hasPubSys = Boolean(pub || sys);
      const ac = hasPubSys ? 5 : 4;
      charge(
        DOCTYPE_BASE_BYTES + attrsContainerBytes(ac) + exactStrBytesHost(n, stringCache) +
          exactStrBytesHost(pub, stringCache) + exactStrBytesHost(sys, stringCache) +
          (hasPubSys ? exactStrBytesHost('pubsyskey', stringCache, true) : 0),
        V8_HTML_OTHER_BYTES,
      );
      return base.setDocumentType(document, name, publicId, systemId);
    },
    appendChild(parentNode, newNode) {
      const ch = base.getChildNodes(parentNode);
      const isFirst = !ch || ch.length === 0;
      const pDepth = getDepth(parentNode);
      nodeDepth.set(newNode, pDepth + 1);
      const isClone = Boolean(htmlTracker?.excessDepthClones.has(newNode));
      const hostAdd = isClone ? 0 : ((isFirst && pDepth < MAX_JSOUP_NESTING_DEPTH) ? 52 : 4);
      charge(
        hostAdd,
        isFirst ? V8_HTML_CHILD_LIST_BYTES + V8_HTML_CHILD_SLOT_BYTES : V8_HTML_CHILD_SLOT_BYTES,
      );
      return base.appendChild(parentNode, newNode);
    },
    insertBefore(parentNode, newNode, referenceNode) {
      const ch = base.getChildNodes(parentNode);
      const isFirst = !ch || ch.length === 0;
      const pDepth = getDepth(parentNode);
      nodeDepth.set(newNode, pDepth + 1);
      const isClone = Boolean(htmlTracker?.excessDepthClones.has(newNode));
      const hostAdd = isClone ? 0 : ((isFirst && pDepth < MAX_JSOUP_NESTING_DEPTH) ? 52 : 4);
      charge(
        hostAdd,
        isFirst ? V8_HTML_CHILD_LIST_BYTES + V8_HTML_CHILD_SLOT_BYTES : V8_HTML_CHILD_SLOT_BYTES,
      );
      return base.insertBefore(parentNode, newNode, referenceNode);
    },
    detachNode(node) {
      const parent = base.getParentNode(node);
      if (parent && !htmlTracker?.excessDepthClones.has(node)) {
        const ch = base.getChildNodes(parent);
        const pDepth = getDepth(parent);
        const refund = (ch && ch.length === 1 && pDepth < MAX_JSOUP_NESTING_DEPTH) ? 52 : 4;
        hostBytes = Math.max(DOC_BASE_BYTES, hostBytes - refund);
      }
      return base.detachNode(node);
    },
    insertText(parentNode, text) {
      const hadEnt = pendingTextHasEntity;
      pendingTextHasEntity = false;
      const ch = base.getChildNodes(parentNode);
      const prev = ch && ch.length > 0 ? ch[ch.length - 1] : null;
      const isNewTextNode = !prev || !base.isTextNode(prev);
      const t = String(text ?? '');
      const isFirst = !ch || ch.length === 0;
      const extraCr = (crlfRatio > 0 && t.includes('\n')) ? Math.round(countCharCode(t, 10) * crlfRatio) : 0;
      const hostSlotAdd = isNewTextNode ? ((isFirst ? 48 : 0) + 4 + LEAF_NODE_BASE_BYTES) : 0;
      const v8SlotAdd = isNewTextNode
        ? ((isFirst ? V8_HTML_CHILD_LIST_BYTES : 0) + V8_HTML_CHILD_SLOT_BYTES + V8_HTML_TEXT_BYTES)
        : 0;
      let hostTextAdd = t.length + extraCr;
      if (hadEnt) {
        if (isNewTextNode) {
          hostTextAdd = exactStrBytesHost(t, stringCache, true, extraCr);
        } else if (htmlTracker && prev && !htmlTracker.entityTextNodes.has(prev)) {
          hostTextAdd += 48;
        }
      }
      charge(hostSlotAdd + hostTextAdd, v8SlotAdd + t.length);
      base.insertText(parentNode, text);
      if (hadEnt && htmlTracker) {
        const afterCh = base.getChildNodes(parentNode);
        const target = afterCh && afterCh.length > 0 ? afterCh[afterCh.length - 1] : null;
        if (target && base.isTextNode(target)) htmlTracker.entityTextNodes.add(target);
      }
    },
    insertTextBefore(parentNode, text, referenceNode) {
      const hadEnt = pendingTextHasEntity;
      pendingTextHasEntity = false;
      const t = String(text ?? '');
      const extraCr = (crlfRatio > 0 && t.includes('\n')) ? Math.round(countCharCode(t, 10) * crlfRatio) : 0;
      const hostTextAdd = hadEnt ? exactStrBytesHost(t, stringCache, true, extraCr) : (t.length + extraCr);
      charge(LEAF_NODE_BASE_BYTES + 4 + hostTextAdd, V8_HTML_TEXT_BYTES + V8_HTML_CHILD_SLOT_BYTES + t.length);
      base.insertTextBefore(parentNode, text, referenceNode);
      if (hadEnt && htmlTracker) {
        const ch = base.getChildNodes(parentNode);
        if (ch) {
          const refIdx = ch.indexOf(referenceNode);
          const target = refIdx > 0 ? ch[refIdx - 1] : null;
          if (target && base.isTextNode(target)) htmlTracker.entityTextNodes.add(target);
        }
      }
    },
    adoptAttributes(recipient, attrs) {
      if (attrs && attrs.length > 0) {
        let hostAdd = 0;
        let v8Add = 0;
        for (let i = 0; i < attrs.length; i++) {
          const a = attrs[i];
          const k = String(a.name ?? '');
          if (k.length > MAX_NAME_LENGTH) {
            throw new DomLimitExceededError(`DOM 属性名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
          }
          const v = String(a.value ?? '');
          const hadEnt = Boolean(htmlTracker?.entityAttrs.has(a));
          const extraCr = (crlfRatio > 0 && v.includes('\n')) ? Math.round(countCharCode(v, 10) * crlfRatio) : 0;
          hostAdd += 8 + exactStrBytesHost(k, stringCache) + exactStrBytesHost(v, stringCache, hadEnt, extraCr);
          v8Add += V8_HTML_ATTR_ENTRY_BYTES + strBytesV8(v) + (k.length > 2 ? Math.min(24, k.length * 2) : 0);
        }
        charge(hostAdd, v8Add);
      }
      return base.adoptAttributes(recipient, attrs);
    },
  };
}

/**
 * Counts unique attribute keys/values in an `<?xml ...?>` or `<!ELEMENT ...>` declaration
 * body so `linkedom` (which drops XML PIs and `<!NAME>` declarations) matches Jsoup's
 * `XmlDeclaration` byte accounting identically.
 */
function estimateXmlDeclBodyHostBytes(target, body, stringCache, cacheTarget = false) {
  let hostBytes = XML_DECL_BASE_BYTES;
  const seen = new Set();
  seen.add(target);
  let i = 0;
  const len = body.length;
  let uniqueAttrs = 0;
  // Jsoup XmlDeclaration stores `#declaration` -> target plus each parsed attribute
  let attrStrBytes = exactStrBytesHost('#declaration', stringCache) +
    exactStrBytesHost(target, stringCache, !cacheTarget);
  while (i < len) {
    while (i < len && isAsciiWsCode(body.charCodeAt(i))) i += 1;
    if (i >= len) break;
    const kStart = i;
    while (i < len) {
      const c = body.charCodeAt(i);
      if (c === 61 || isAsciiWsCode(c)) break;
      i += 1;
    }
    const key = body.slice(kStart, i);
    if (key.length > MAX_NAME_LENGTH) {
      throw new DomLimitExceededError(`DOM 属性名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
    }
    while (i < len && isAsciiWsCode(body.charCodeAt(i))) i += 1;
    let val = '';
    if (i < len && body.charCodeAt(i) === 61) {
      i += 1;
      while (i < len && isAsciiWsCode(body.charCodeAt(i))) i += 1;
      if (i < len) {
        const q = body.charCodeAt(i);
        if (q === 34 || q === 39) {
          i += 1;
          const vStart = i;
          while (i < len && body.charCodeAt(i) !== q) i += 1;
          val = body.slice(vStart, i);
          if (i < len) i += 1;
        } else {
          const vStart = i;
          while (i < len && !isAsciiWsCode(body.charCodeAt(i))) i += 1;
          val = body.slice(vStart, i);
        }
      }
    }
    if (key.length > 0 && !seen.has(key)) {
      seen.add(key);
      uniqueAttrs += 1;
      attrStrBytes += exactStrBytesHost(key, stringCache) + exactStrBytesHost(val, stringCache);
    }
  }
  if (uniqueAttrs > 0) {
    hostBytes += attrsContainerBytes(1 + uniqueAttrs) + attrStrBytes;
  } else {
    hostBytes += exactStrBytesHost(target, stringCache, !cacheTarget);
  }
  return hostBytes;
}

/**
 * Parses an XML `<!DOCTYPE ...>` declaration starting at `start` (pointing at `<`)
 * directly from source (bypassing linkedom's regex truncation on names containing digits)
 * and computes its exact Jsoup `DocumentType` host-model byte cost.
 */
function scanXmlDoctypeHostBytes(xml, start, len, stringCache) {
  let j = start + 9; // past "<!DOCTYPE"
  let bracketDepth = 0;
  let quote = 0;
  while (j < len) {
    const c = xml.charCodeAt(j);
    if (quote !== 0) {
      if (c === quote) quote = 0;
    } else if (c === 34 || c === 39) {
      quote = c;
    } else if (c === 91) { // '['
      bracketDepth += 1;
    } else if (c === 93 && bracketDepth > 0) { // ']'
      bracketDepth -= 1;
    } else if (c === 62 && bracketDepth === 0) { // '>'
      break;
    }
    j += 1;
  }
  const contentEnd = j;
  const nextIndex = j < len ? j + 1 : len;

  // Parse: <!DOCTYPE name [PUBLIC "pub" "sys" | SYSTEM "sys"] [internalSubset]>
  let p = start + 9;
  while (p < contentEnd && isAsciiWsCode(xml.charCodeAt(p))) p += 1;
  const nameStart = p;
  while (p < contentEnd) {
    const c = xml.charCodeAt(p);
    if (isAsciiWsCode(c) || c === 91 || c === 62) break;
    p += 1;
  }
  const name = xml.slice(nameStart, p);
  while (p < contentEnd && isAsciiWsCode(xml.charCodeAt(p))) p += 1;

  let pubSysKey = '';
  let pub = '';
  let sys = '';
  const kwStart = p;
  while (p < contentEnd && !isAsciiWsCode(xml.charCodeAt(p)) && xml.charCodeAt(p) !== 91) p += 1;
  const kw = xml.slice(kwStart, p).toUpperCase();
  function readQuoted() {
    while (p < contentEnd && isAsciiWsCode(xml.charCodeAt(p))) p += 1;
    if (p >= contentEnd) return '';
    const q = xml.charCodeAt(p);
    if (q !== 34 && q !== 39) return '';
    p += 1;
    const s = p;
    while (p < contentEnd && xml.charCodeAt(p) !== q) p += 1;
    const out = xml.slice(s, p);
    if (p < contentEnd) p += 1;
    return out;
  }
  if (kw === 'PUBLIC') {
    pubSysKey = 'PUBLIC';
    pub = readQuoted();
    sys = readQuoted();
  } else if (kw === 'SYSTEM') {
    pubSysKey = 'SYSTEM';
    sys = readQuoted();
  }

  // Jsoup DocumentType always stores `#doctype`, `name`, `publicId`, `systemId` (4 attrs),
  // plus a fresh `pubsyskey` (via Normalizer.lowerCase("pubSysKey")) when PUBLIC/SYSTEM is present (5 attrs).
  const hasPubSys = pubSysKey.length > 0;
  const ac = hasPubSys ? 5 : 4;
  let bytes = DOCTYPE_BASE_BYTES + attrsContainerBytes(ac) +
    exactStrBytesHost('#doctype', stringCache) +
    exactStrBytesHost('name', stringCache) +
    exactStrBytesHost('publicId', stringCache) +
    exactStrBytesHost('systemId', stringCache) +
    exactStrBytesHost(name, stringCache) +
    exactStrBytesHost(pub, stringCache) +
    exactStrBytesHost(sys, stringCache);
  if (hasPubSys) {
    bytes += exactStrBytesHost('pubsyskey', stringCache, true) + exactStrBytesHost(pubSysKey, stringCache);
  }
  return { nextIndex, bytes };
}

/**
 * Coalesces consecutive XML `#text` sibling nodes produced by `linkedom` (which splits
 * every XML character/entity reference such as `x&amp;x&amp;` into separate `#text` nodes,
 * whereas Jsoup merges adjacent text into a single `TextNode`).
 */
function coalesceXmlTextNodes(doc, deadlineMs) {
  const stack = [doc];
  let ops = 0;
  while (stack.length > 0) {
    if ((++ops & 1023) === 0) checkDeadline(deadlineMs);
    const parent = stack.pop();
    let child = parent.firstChild;
    while (child) {
      if ((++ops & 1023) === 0) checkDeadline(deadlineMs);
      if (child.nodeType === 3) {
        let next = child.nextSibling;
        if (next && next.nodeType === 3) {
          const parts = [String(child.nodeValue ?? '')];
          while (next && next.nodeType === 3) {
            if ((++ops & 1023) === 0) checkDeadline(deadlineMs);
            parts.push(String(next.nodeValue ?? ''));
            const toRemove = next;
            next = next.nextSibling;
            toRemove.remove();
          }
          child.nodeValue = parts.join('');
        }
        child = next;
      } else {
        if (child.nodeType === 1 && child.firstChild) stack.push(child);
        child = child.nextSibling;
      }
    }
  }
}

/**
 * Fast XML pre-scan enforcing `MAX_NAME_LENGTH`, aborting over-budget XML inputs
 * BEFORE `linkedom` allocates an oversized tree (checking both host-model `budgetBytes`
 * and the loose V8 safety net `v8SafetyBudgetBytes`), and computing exact Jsoup host-model
 * bytes for XML declarations, processing instructions, `<!DOCTYPE>` (bypassing linkedom's
 * digit-truncation bug), and `<!ELEMENT / !ATTLIST / !ENTITY / !NOTATION>` declarations
 * that `linkedom` drops.
 */
function preScanXml(xml, budgetBytes, v8SafetyBudgetBytes, deadlineMs) {
  let hostEst = DOC_BASE_BYTES;
  let v8Est = DOC_BASE_BYTES;
  let droppedDeclHostBytes = 0;
  let droppedAtRootCount = 0;
  let droppedInsideElemCount = 0;
  let openElemDepth = 0;
  const stringCache = new Array(512);
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
        const dataLen = Math.max(0, (end >= 0 ? end : len) - (i + 9));
        hostEst += LEAF_NODE_BASE_BYTES + 4 + dataLen;
        v8Est += V8_XML_CDATA_BYTES + dataLen;
        i = stop;
      } else if (xml.startsWith('<!--', i)) {
        const end = xml.indexOf('-->', i + 4);
        const stop = end >= 0 ? end + 3 : len;
        const dataLen = Math.max(0, (end >= 0 ? end : len) - (i + 4));
        hostEst += LEAF_NODE_BASE_BYTES + 4 + dataLen;
        v8Est += V8_XML_COMMENT_BYTES + dataLen;
        i = stop;
      } else if (xml.startsWith('<?', i)) {
        const end = xml.indexOf('?>', i + 2);
        const contentEnd = end >= 0 ? end : len;
        const stop = end >= 0 ? end + 2 : len;
        let tEnd = i + 2;
        while (tEnd < contentEnd && !isAsciiWsCode(xml.charCodeAt(tEnd)) && xml.charCodeAt(tEnd) !== 63) tEnd += 1;
        const target = xml.slice(i + 2, tEnd);
        if (target.length > MAX_NAME_LENGTH) {
          throw new DomLimitExceededError(`XML 处理指令目标名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
        }
        let bodyStart = tEnd;
        while (bodyStart < contentEnd && isAsciiWsCode(xml.charCodeAt(bodyStart))) bodyStart += 1;
        const body = xml.slice(bodyStart, contentEnd);
        let piHost;
        if (target.toLowerCase() === 'xml') {
          piHost = estimateXmlDeclBodyHostBytes(target, body, stringCache, false);
        } else {
          // Jsoup ProcessingInstruction creates fresh target & data Strings via TokenData
          piHost = PI_NODE_BASE_BYTES + exactStrBytesHost(target, stringCache, true) + exactStrBytesHost(body, stringCache, true);
        }
        droppedDeclHostBytes += piHost + 4;
        if (openElemDepth > 0) droppedInsideElemCount += 1;
        else droppedAtRootCount += 1;
        hostEst += piHost + 4;
        v8Est += 16;
        i = stop;
      } else if (i + 1 < len && xml.charCodeAt(i + 1) === 47) { // '</'
        const end = xml.indexOf('>', i + 2);
        if (openElemDepth > 0) openElemDepth -= 1;
        i = end >= 0 ? end + 1 : len;
      } else if (i + 1 < len && xml.charCodeAt(i + 1) === 33) { // '<!'
        const prefix9 = xml.slice(i, i + 9).toUpperCase();
        const after9 = i + 9 < len ? xml.charCodeAt(i + 9) : 62;
        if (prefix9 === '<!DOCTYPE' && (isAsciiWsCode(after9) || after9 === 62 || after9 === 91)) {
          const dt = scanXmlDoctypeHostBytes(xml, i, len, stringCache);
          droppedDeclHostBytes += dt.bytes + 4;
          if (openElemDepth > 0) droppedInsideElemCount += 1;
          else droppedAtRootCount += 1;
          hostEst += dt.bytes + 4;
          v8Est += V8_XML_OTHER_BYTES;
          i = dt.nextIndex;
        } else {
          // Jsoup XmlTreeBuilder parses `<!ELEMENT ...>` / `<!ATTLIST ...>` / `<!ENTITY ...>` /
          // `<!NOTATION ...>` as XmlDeclaration via Comment.asXmlDeclaration(); linkedom drops them.
          const end = xml.indexOf('>', i + 2);
          const contentEnd = end >= 0 ? end : len;
          const stop = end >= 0 ? end + 1 : len;
          const c2 = i + 2 < contentEnd ? xml.charCodeAt(i + 2) : 0;
          const isDeclNameStart = (c2 >= 65 && c2 <= 90) || (c2 >= 97 && c2 <= 122) || c2 === 95 || c2 === 58;
          if (isDeclNameStart) {
            let tEnd = i + 2;
            while (tEnd < contentEnd && !isAsciiWsCode(xml.charCodeAt(tEnd))) tEnd += 1;
            const target = xml.slice(i + 2, tEnd);
            if (target.length > MAX_NAME_LENGTH) {
              throw new DomLimitExceededError(`XML 声明名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
            }
            let bodyStart = tEnd;
            while (bodyStart < contentEnd && isAsciiWsCode(xml.charCodeAt(bodyStart))) bodyStart += 1;
            const body = xml.slice(bodyStart, contentEnd);
            const declHost = estimateXmlDeclBodyHostBytes(target, body, stringCache, true);
            droppedDeclHostBytes += declHost + 4;
            if (openElemDepth > 0) droppedInsideElemCount += 1;
            else droppedAtRootCount += 1;
            hostEst += declHost + 4;
          } else {
            const bogusData = xml.slice(i + 2, contentEnd);
            const bogusHost = LEAF_NODE_BASE_BYTES + exactStrBytesHost(bogusData, stringCache, true);
            droppedDeclHostBytes += bogusHost + 4;
            if (openElemDepth > 0) droppedInsideElemCount += 1;
            else droppedAtRootCount += 1;
            hostEst += bogusHost + 4;
          }
          v8Est += 16;
          i = stop;
        }
      } else {
        hostEst += ELEMENT_BASE_BYTES + 4;
        v8Est += V8_XML_ELEM_BYTES;
        i += 1;
        // Scan tag name
        const tnStart = i;
        while (i < len) {
          const c = xml.charCodeAt(i);
          if (c === 62 || c === 47 || isAsciiWsCode(c)) break;
          i += 1;
        }
        if (i - tnStart > MAX_NAME_LENGTH) {
          throw new DomLimitExceededError(`DOM 标签名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
        }
        // Scan attributes inside tag header respecting quotes (counting unique attr names)
        let attrCount = 0;
        let quote = 0;
        let inAttrName = false;
        let attrNameLen = 0;
        let afterEq = false;
        let selfClosing = false;
        while (i < len) {
          const c = xml.charCodeAt(i);
          if (quote !== 0) {
            if (c === quote) { quote = 0; afterEq = false; }
          } else if (c === 62) {
            i += 1;
            break;
          } else if (c === 34 || c === 39) {
            quote = c;
            inAttrName = false;
            attrNameLen = 0;
            selfClosing = false;
          } else if (c === 61) {
            if (inAttrName) { attrCount += 1; inAttrName = false; attrNameLen = 0; }
            afterEq = true;
            selfClosing = false;
          } else if (isAsciiWsCode(c) || c === 47) {
            if (inAttrName) { attrCount += 1; inAttrName = false; attrNameLen = 0; }
            if (afterEq && c === 47) afterEq = false;
            selfClosing = c === 47;
          } else if (!afterEq) {
            inAttrName = true;
            attrNameLen += 1;
            selfClosing = false;
            if (attrNameLen > MAX_NAME_LENGTH) {
              throw new DomLimitExceededError(`DOM 属性名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
            }
          } else {
            selfClosing = false;
          }
          i += 1;
        }
        if (!selfClosing) openElemDepth += 1;
        if (attrCount > 0) {
          // Conservative lower bound (attrs may contain duplicates; exact deduped count is charged post-parse)
          hostEst += 56;
          v8Est += V8_XML_ATTRS_CONTAINER_BYTES + Math.min(attrCount, 16) * V8_XML_ATTR_ENTRY_BYTES;
        }
      }
      if (hostEst > budgetBytes) {
        throw new DomLimitExceededError(
          `待解析 XML 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
        );
      }
      if (v8Est > v8SafetyBudgetBytes) {
        throw new DomLimitExceededError(
          `开发工具 V8 兜底 DOM 内存超过上限（最多 ${Math.round(v8SafetyBudgetBytes / (1024 * 1024))} MB）`,
        );
      }
    } else {
      const nextLt = xml.indexOf('<', i);
      const stop = nextLt >= 0 ? nextLt : len;
      if (stop > i) {
        hostEst += LEAF_NODE_BASE_BYTES + 4;
        v8Est += V8_XML_TEXT_BYTES + (stop - i);
        if (hostEst > budgetBytes) {
          throw new DomLimitExceededError(
            `待解析 XML 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
          );
        }
        if (v8Est > v8SafetyBudgetBytes) {
          throw new DomLimitExceededError(
            `开发工具 V8 兜底 DOM 内存超过上限（最多 ${Math.round(v8SafetyBudgetBytes / (1024 * 1024))} MB）`,
          );
        }
      }
      i = stop;
    }
  }
  return { droppedDeclHostBytes, droppedAtRootCount, droppedInsideElemCount };
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
    maxProcessMemoryBytes = MAX_PROCESS_MEMORY_BYTES,
    maxV8SafetyMemoryBytes = MAX_V8_SAFETY_MEMORY_BYTES,
  ) {
    if (maxDocsPerCall && typeof maxDocsPerCall === 'object') {
      const opts = maxDocsPerCall;
      this.maxDocsPerCall = opts.maxDocsPerCall ?? MAX_LIVE_DOCS;
      this.maxDocBytes = opts.maxDocBytes ?? MAX_DOC_BYTES;
      this.maxDocMemoryBytes = opts.maxDocMemoryBytes ?? MAX_DOC_MEMORY_BYTES;
      this.maxSelectorSteps = opts.maxSelectorSteps ?? MAX_SELECTOR_STEPS;
      this.maxVmMemoryBytes = opts.maxVmMemoryBytes ?? MAX_VM_MEMORY_BYTES;
      this.maxSelectorMemoryBytes = opts.maxSelectorMemoryBytes ?? MAX_SELECTOR_MEMORY_BYTES;
      this.maxOutputBytes = opts.maxOutputBytes ?? MAX_OUTPUT_BYTES;
      this.maxProcessMemoryBytes = opts.maxProcessMemoryBytes ?? Math.max(MAX_PROCESS_MEMORY_BYTES, this.maxVmMemoryBytes);
      this.maxV8SafetyMemoryBytes = opts.maxV8SafetyMemoryBytes ?? MAX_V8_SAFETY_MEMORY_BYTES;
    } else {
      this.maxDocsPerCall = maxDocsPerCall;
      this.maxDocBytes = maxDocBytes;
      this.maxDocMemoryBytes = maxDocMemoryBytes;
      this.maxSelectorSteps = maxSelectorSteps;
      this.maxVmMemoryBytes = maxVmMemoryBytes;
      this.maxSelectorMemoryBytes = maxSelectorMemoryBytes;
      this.maxOutputBytes = maxOutputBytes;
      this.maxProcessMemoryBytes = Math.max(maxProcessMemoryBytes, maxVmMemoryBytes);
      this.maxV8SafetyMemoryBytes = maxV8SafetyMemoryBytes;
    }
    this._processLedger = GLOBAL_PROCESS_LEDGER;
    this._processHolder = this._processLedger.register(this);
    this._v8SafetyLedger = GLOBAL_V8_SAFETY_LEDGER;
    this._v8SafetyHolder = this._v8SafetyLedger.register(this);
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
    const { budgetBytes, v8SafetyBudgetBytes } = this._checkPreParseLimits(text);
    const entityAttrs = new WeakSet();
    const entityTextNodes = new WeakSet();
    const seenTokenAttrArrays = new WeakSet();
    const excessDepthClones = new WeakSet();
    const crlfRatio = computeHtmlCrlfRatio(text);
    let parserRef = null;
    const htmlTracker = {
      entityAttrs,
      entityTextNodes,
      seenTokenAttrArrays,
      excessDepthClones,
      crlfRatio,
      getOpenStackDepth: () => (parserRef?.openElements?.stackTop ?? 0) + 1,
    };
    const adapter = createBudgetedParse5TreeAdapter(
      budgetBytes,
      this.deadlineMs,
      this._processLedger,
      this._processHolder,
      this.maxProcessMemoryBytes,
      this._v8SafetyLedger,
      this._v8SafetyHolder,
      this.maxV8SafetyMemoryBytes,
      htmlTracker,
    );
    let document;
    let stats;
    try {
      const parser = new parse5.Parser({ treeAdapter: adapter });
      parserRef = parser;
      if (text.includes('&') && typeof parser.tokenizer?.entityDecoder?.emitCodePoint === 'function') {
        const tok = parser.tokenizer;
        const dec = tok.entityDecoder;
        const origEmit = dec.emitCodePoint;
        dec.emitCodePoint = function patchedEmitCodePoint(cp, consumed) {
          if (tok._isCharacterReferenceInAttribute()) {
            if (tok.currentAttr) entityAttrs.add(tok.currentAttr);
          } else {
            adapter._markNextTextHasEntity();
          }
          return origEmit.call(this, cp, consumed);
        };
      }
      parser.tokenizer.write(text, true);
      document = parser.document;
      stats = this._normalizeAndEstimateDoc(document, true, budgetBytes, v8SafetyBudgetBytes, null, htmlTracker);
    } finally {
      adapter._releaseInFlight();
    }
    return this._registerDocument(document, false, stats.elementCount, stats.estimatedBytes, stats.v8EstimatedBytes);
  }

  parseXml(xml) {
    checkDeadline(this.deadlineMs);
    const text = String(xml ?? '');
    const { budgetBytes, v8SafetyBudgetBytes } = this._checkPreParseLimits(text);
    const pre = preScanXml(text, budgetBytes, v8SafetyBudgetBytes, this.deadlineMs);
    const document = this._xmlParser.parseFromString(text, 'text/xml');
    coalesceXmlTextNodes(document, this.deadlineMs);
    const stats = this._normalizeAndEstimateDoc(document, false, budgetBytes, v8SafetyBudgetBytes, pre, null);
    return this._registerDocument(document, true, stats.elementCount, stats.estimatedBytes, stats.v8EstimatedBytes);
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
    this._processLedger.release(this._processHolder, docEntry.estimatedBytes + docEntry.handleTableBytes);
    this._v8SafetyLedger.release(this._v8SafetyHolder, docEntry.v8EstimatedBytes);
    return true;
  }

  selectFirstId(nodeId, selector) {
    checkDeadline(this.deadlineMs);
    const entry = this.nodes.get(nodeId);
    if (!entry) return -1;
    const parsed = parseSelector(selector);
    const remVm = Math.max(0, this.maxVmMemoryBytes - (this.vmDocBytes + this.vmHandleBytes));
    const remProc = this._processLedger.remainingBytes(this.maxProcessMemoryBytes);
    const found = queryDescendants(
      entry.el,
      parsed,
      entry.isXml,
      true,
      this.maxSelectorSteps,
      this.maxSelectorMemoryBytes,
      Math.min(remVm, remProc),
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
    const remProc = this._processLedger.remainingBytes(this.maxProcessMemoryBytes);
    const list = queryDescendants(
      entry.el,
      parsed,
      entry.isXml,
      false,
      this.maxSelectorSteps,
      this.maxSelectorMemoryBytes,
      Math.min(remVm, remProc),
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
    return entry.isXml ? raw : asciiLowerFast(raw);
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
    if (!entry || entry.isDocument) return '{}';
    const attrs = getElementAttributes(entry.el);
    if (attrs.length === 0) return '{}';
    const out = new BoundedOutputBuffer(this.maxOutputBytes, this.deadlineMs);
    out.appendAscii('{');
    let first = true;
    let ops = 0;
    for (let i = 0; i < attrs.length; i++) {
      if ((++ops & 255) === 0) checkDeadline(this.deadlineMs);
      if (!first) out.appendAscii(',');
      first = false;
      const a = attrs[i];
      const key = entry.isXml ? String(a.name ?? '') : asciiLowerFast(a.name);
      const val = String(a.value ?? '');
      appendJsonEscapedString(key, out);
      out.appendAscii(':');
      appendJsonEscapedString(val, out);
    }
    out.appendAscii('}');
    return out.toString();
  }

  /**
   * Releases all live documents, handles, and process-wide DOM memory quota.
   * Deliberately does NOT reset `nextId` so stale handles never collide.
   */
  clear() {
    this.nodes.clear();
    this.docs.clear();
    this.totalNodes = 0;
    this.vmDocBytes = 0;
    this.vmHandleBytes = 0;
    this._processLedger.releaseAll(this._processHolder);
    this._v8SafetyLedger.releaseAll(this._v8SafetyHolder);
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
      const tag = asciiLowerFast(getRawTagName(item));
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
    let procRem = this._processLedger.remainingBytes(this.maxProcessMemoryBytes) - HANDLE_ENTRY_BYTES;
    if (procRem < DOC_BASE_BYTES) {
      procRem = this.maxProcessMemoryBytes - this._processLedger.currentTotalBytes() - HANDLE_ENTRY_BYTES;
      if (procRem < DOC_BASE_BYTES) {
        throw new DomLimitExceededError(
          `进程级全部插件 VM 存活 DOM 总内存超过上限（最多 ${Math.round(this.maxProcessMemoryBytes / (1024 * 1024))} MB）`,
        );
      }
    }
    let v8Rem = this._v8SafetyLedger.remainingBytes(this.maxV8SafetyMemoryBytes);
    if (v8Rem < DOC_BASE_BYTES) {
      v8Rem = this.maxV8SafetyMemoryBytes - this._v8SafetyLedger.currentTotalBytes();
      if (v8Rem < DOC_BASE_BYTES) {
        throw new DomLimitExceededError(
          `开发工具 V8 兜底 DOM 内存超过上限（最多 ${Math.round(this.maxV8SafetyMemoryBytes / (1024 * 1024))} MB）`,
        );
      }
    }
    return {
      budgetBytes: Math.min(this.maxDocMemoryBytes, vmRem, procRem),
      v8SafetyBudgetBytes: v8Rem,
    };
  }

  /**
   * Flattens `<template>.content` and long V8 `ConsString` values in parse5 trees,
   * enforces `MAX_NAME_LENGTH`, and computes exact Android-host-equivalent byte
   * estimates across ALL node kinds (plus a rough V8 byte estimate for the 512 MB
   * Node safety net).
   */
  _normalizeAndEstimateDoc(doc, isParse5Html, budgetBytes, v8SafetyBudgetBytes, xmlPre = null, htmlTracker = null) {
    const isXmlDoc = !isParse5Html;
    const crlfRatio = htmlTracker?.crlfRatio ?? 0;
    const droppedDeclHostBytes = xmlPre?.droppedDeclHostBytes ?? 0;
    let hostBytes = DOC_BASE_BYTES + droppedDeclHostBytes;
    let v8Bytes = DOC_BASE_BYTES;
    let elementCount = 0;
    const seenCustomTags = new Set();
    const stringCache = new Array(512);
    const stack = [];
    const depthStack = [];
    const rootChildren = getChildNodes(doc);
    let xmlRootNonDtCount = 0;
    if (rootChildren.length > 0) {
      if (isXmlDoc) {
        for (let i = 0; i < rootChildren.length; i++) {
          if (rootChildren[i].nodeType !== 10) xmlRootNonDtCount += 1;
        }
        if (xmlRootNonDtCount > 0) {
          hostBytes += childListBytes(xmlRootNonDtCount);
        } else if ((xmlPre?.droppedAtRootCount ?? 0) > 0) {
          hostBytes += 48; // Element$NodeList header (slots already counted in preScanXml)
        }
      } else {
        hostBytes += childListBytes(rootChildren.length);
        v8Bytes += V8_HTML_CHILD_LIST_BYTES + V8_HTML_CHILD_SLOT_BYTES * rootChildren.length;
      }
      for (let i = rootChildren.length - 1; i >= 0; i--) {
        stack.push(rootChildren[i]);
        depthStack.push(1);
      }
    } else if (isXmlDoc && (xmlPre?.droppedAtRootCount ?? 0) > 0) {
      hostBytes += 48;
    }
    let pendingElemDeclHeader = isXmlDoc && (xmlPre?.droppedInsideElemCount ?? 0) > 0;
    let ops = 0;
    while (stack.length > 0) {
      if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
      const cur = stack.pop();
      const depth = depthStack.pop();
      if (!cur) continue;
      if (typeof cur === 'string') {
        // Closing tag sentinel: mirror Jsoup's `</tag>` CharacterReader.cacheString update
        touchStringCache(cur, stringCache);
        continue;
      }
      if (isElementNode(cur)) {
        elementCount += 1;
        const tn = getRawTagName(cur);
        if (tn.length > MAX_NAME_LENGTH) {
          throw new DomLimitExceededError(`DOM 标签名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
        }
        if (isParse5Html && cur.content && Array.isArray(cur.content.childNodes) && cur.content.childNodes.length > 0) {
          const tmplChildren = cur.content.childNodes;
          for (const tc of tmplChildren) tc.parentNode = cur;
          cur.childNodes = tmplChildren;
          cur.content = null;
        }
        const isClone = Boolean(htmlTracker?.excessDepthClones.has(cur));
        const ch = getChildNodes(cur);
        if (isClone) {
          // Adoption-agency formatting clone at depth >= 512 that Jsoup never creates due to setMaxDepth(512).
          // Keep V8 safety accounting intact, skip host element/list overhead, and flatten children into slots.
          v8Bytes += V8_HTML_ELEM_BYTES;
          if (ch.length > 0) {
            v8Bytes += V8_HTML_CHILD_LIST_BYTES + V8_HTML_CHILD_SLOT_BYTES * ch.length;
            hostBytes += (ch.length - 1) * 4;
            const nextDepth = depth + 1;
            for (let i = ch.length - 1; i >= 0; i--) {
              stack.push(ch[i]);
              depthStack.push(nextDepth);
            }
          } else {
            hostBytes = Math.max(DOC_BASE_BYTES, hostBytes - 4);
          }
          continue;
        }

        const norm = asciiLowerFast(tn);
        hostBytes += ELEMENT_BASE_BYTES;
        if (!isXmlDoc && norm === 'form') hostBytes += FORM_ELEMENT_EXTRA_BYTES;

        // Custom tag accounting: XML has empty TagSet (every distinct tag is custom);
        // HTML uses Jsoup's exact TagSet.HtmlTagSet known tags.
        // Only touch stringCache when NOT charging a brand-new custom tag name, so first-seen
        // custom tags are not pre-cached into returning 0 bytes.
        if (isXmlDoc) {
          if (!seenCustomTags.has(tn)) {
            seenCustomTags.add(tn);
            hostBytes += CUSTOM_TAG_BASE_BYTES + exactStrBytesHost(tn, stringCache);
            if (norm !== tn) hostBytes += exactStrBytesHost(norm, stringCache);
          } else {
            touchStringCache(tn, stringCache);
          }
          v8Bytes += V8_XML_ELEM_BYTES;
        } else {
          if (!isKnownJsoupHtmlTag(norm, cur.namespaceURI)) {
            const tagKey = `${cur.namespaceURI || ''}\u0000${tn}`;
            if (!seenCustomTags.has(tagKey)) {
              seenCustomTags.add(tagKey);
              hostBytes += CUSTOM_TAG_BASE_BYTES + exactStrBytesHost(tn, stringCache);
              if (norm !== tn) hostBytes += exactStrBytesHost(norm, stringCache);
            } else {
              touchStringCache(tn, stringCache);
            }
          } else {
            touchStringCache(tn, stringCache);
          }
          v8Bytes += V8_HTML_ELEM_BYTES;
        }

        const attrs = getElementAttributes(cur);
        if (attrs.length > 0) {
          hostBytes += attrsContainerBytes(attrs.length);
          v8Bytes += isXmlDoc ? V8_XML_ATTRS_CONTAINER_BYTES : V8_HTML_ATTRS_CONTAINER_BYTES;
          for (let i = 0; i < attrs.length; i++) {
            if ((++ops & 1023) === 0) checkDeadline(this.deadlineMs);
            const a = attrs[i];
            const k = String(a.name ?? '');
            if (k.length > MAX_NAME_LENGTH) {
              throw new DomLimitExceededError(`DOM 属性名超过长度上限（最多 ${MAX_NAME_LENGTH} 字符）`);
            }
            if (!isXmlDoc && typeof a.value === 'string' && a.value.length >= 16) {
              a.value = flattenStrV8(a.value);
            }
            const v = String(a.value ?? '');
            const hadEnt = Boolean(htmlTracker?.entityAttrs.has(a));
            const extraCr = (!isXmlDoc && crlfRatio > 0 && v.includes('\n')) ? Math.round(countCharCode(v, 10) * crlfRatio) : 0;
            hostBytes += exactStrBytesHost(k, stringCache) + exactStrBytesHost(v, stringCache, hadEnt, extraCr);
            if (isXmlDoc) {
              v8Bytes += V8_XML_ATTR_ENTRY_BYTES + strBytesV8(k) + strBytesV8(v);
            } else {
              v8Bytes += V8_HTML_ATTR_ENTRY_BYTES + strBytesV8(v) + (k.length > 2 ? Math.min(24, k.length * 2) : 0);
            }
          }
        }
        if (ch.length > 0) {
          // Jsoup caps parser nesting at MAX_JSOUP_NESTING_DEPTH (512): pure nested element chains
          // flatten into siblings (4B/slot, no NodeList), while elements holding text/other children
          // still retain a NodeList for their non-chain children.
          if (!isXmlDoc && depth >= MAX_JSOUP_NESTING_DEPTH) {
            if (ch.length === 1 && isElementNode(ch[0])) {
              hostBytes += 4;
            } else if (isElementNode(ch[ch.length - 1])) {
              hostBytes += childListBytes(ch.length - 1) + 4;
            } else {
              hostBytes += childListBytes(ch.length);
            }
          } else {
            hostBytes += childListBytes(ch.length);
          }
          if (!isXmlDoc) v8Bytes += V8_HTML_CHILD_LIST_BYTES + V8_HTML_CHILD_SLOT_BYTES * ch.length;
          // Push closing-tag cache sentinel BEFORE children so it runs right after children finish
          if (tn.length <= 12 && (isXmlDoc || !HTML_VOID_TAGS.has(norm))) {
            stack.push(tn);
            depthStack.push(depth);
          }
          const nextDepth = depth + 1;
          for (let i = ch.length - 1; i >= 0; i--) {
            stack.push(ch[i]);
            depthStack.push(nextDepth);
          }
        } else if (pendingElemDeclHeader && depth === 1) {
          hostBytes += 48; // Root XML element held only dropped PIs/DOCTYPEs/<!ELEMENT>s
          pendingElemDeclHeader = false;
        }
      } else if (isCDataNode(cur)) {
        const v = getNodeTextValue(cur);
        hostBytes += LEAF_NODE_BASE_BYTES + exactStrBytesHost(v, stringCache);
        v8Bytes += (isXmlDoc ? V8_XML_CDATA_BYTES : 80) + strBytesV8(v);
      } else if (isTextNode(cur)) {
        if (!isXmlDoc && typeof cur.value === 'string' && cur.value.length >= 16) {
          cur.value = flattenStrV8(cur.value);
        }
        const v = getNodeTextValue(cur);
        // Jsoup unescapeEntities() / HTML character reference decoding allocates a fresh String
        const bypass = isXmlDoc
          ? (v.includes('&') || v.includes('<'))
          : Boolean(htmlTracker?.entityTextNodes.has(cur));
        const extraCr = (!isXmlDoc && crlfRatio > 0 && v.includes('\n')) ? Math.round(countCharCode(v, 10) * crlfRatio) : 0;
        hostBytes += LEAF_NODE_BASE_BYTES + exactStrBytesHost(v, stringCache, bypass, extraCr);
        v8Bytes += (isXmlDoc ? V8_XML_TEXT_BYTES : V8_HTML_TEXT_BYTES) + strBytesV8(v);
      } else if (isCommentNode(cur)) {
        if (!isXmlDoc && typeof cur.data === 'string' && cur.data.length >= 16) {
          cur.data = flattenStrV8(cur.data);
        }
        const d = getNodeCommentData(cur);
        const extraCr = (!isXmlDoc && crlfRatio > 0 && d.includes('\n')) ? Math.round(countCharCode(d, 10) * crlfRatio) : 0;
        // Jsoup Token$Comment builds comment data via TokenData (fresh String, bypassing stringCache)
        hostBytes += LEAF_NODE_BASE_BYTES + exactStrBytesHost(d, stringCache, true, extraCr);
        v8Bytes += (isXmlDoc ? V8_XML_COMMENT_BYTES : V8_HTML_COMMENT_BYTES) + strBytesV8(d);
      } else if (cur.nodeType === 10 || cur.nodeName === '#documentType') {
        if (!isXmlDoc) {
          const name = String(cur.name ?? '');
          const pub = String(cur.publicId ?? '');
          const sys = String(cur.systemId ?? '');
          // Jsoup DocumentType always stores `#doctype`, `name`, `publicId`, `systemId` (4 attrs),
          // plus fresh `pubsyskey` when PUBLIC/SYSTEM is present (5 attrs).
          const hasPubSys = Boolean(pub || sys);
          const ac = hasPubSys ? 5 : 4;
          hostBytes += DOCTYPE_BASE_BYTES + attrsContainerBytes(ac) +
            exactStrBytesHost('#doctype', stringCache) +
            exactStrBytesHost('name', stringCache) +
            exactStrBytesHost('publicId', stringCache) +
            exactStrBytesHost('systemId', stringCache) +
            exactStrBytesHost(name, stringCache) +
            exactStrBytesHost(pub, stringCache) +
            exactStrBytesHost(sys, stringCache);
          if (hasPubSys) {
            hostBytes += exactStrBytesHost('pubsyskey', stringCache, true) + exactStrBytesHost(pub ? 'PUBLIC' : 'SYSTEM', stringCache);
          }
        }
        v8Bytes += isXmlDoc ? V8_XML_OTHER_BYTES : V8_HTML_OTHER_BYTES;
      } else {
        hostBytes += OTHER_NODE_BASE_BYTES;
        v8Bytes += isXmlDoc ? V8_XML_OTHER_BYTES : V8_HTML_OTHER_BYTES;
      }
      if (hostBytes > budgetBytes) {
        throw new DomLimitExceededError(
          `解析后的 DOM 估算内存超过上限（上限 ${Math.round(budgetBytes / (1024 * 1024))} MB）`,
        );
      }
      if (v8Bytes > v8SafetyBudgetBytes) {
        throw new DomLimitExceededError(
          `开发工具 V8 兜底 DOM 内存超过上限（最多 ${Math.round(this.maxV8SafetyMemoryBytes / (1024 * 1024))} MB）`,
        );
      }
    }
    return { elementCount, estimatedBytes: hostBytes, v8EstimatedBytes: v8Bytes };
  }

  _registerDocument(doc, isXml, elementCount, estimatedBytes, v8EstimatedBytes = 0) {
    if (this.docs.size >= this.maxDocsPerCall) {
      throw new DomLimitExceededError(`同时存活的文档数超过上限（最多 ${this.maxDocsPerCall} 个）`);
    }
    if (estimatedBytes > this.maxDocMemoryBytes) {
      throw new DomLimitExceededError(
        `单个文档估算内存超过上限（最多 ${Math.round(this.maxDocMemoryBytes / (1024 * 1024))} MB）`,
      );
    }
    const addedTotal = estimatedBytes + HANDLE_ENTRY_BYTES;
    if (this.vmDocBytes + this.vmHandleBytes + addedTotal > this.maxVmMemoryBytes) {
      throw new DomLimitExceededError(
        `VM 存活 DOM 总内存超过上限（最多 ${Math.round(this.maxVmMemoryBytes / (1024 * 1024))} MB）`,
      );
    }
    if (!this._processLedger.tryReserve(this._processHolder, addedTotal, this.maxProcessMemoryBytes)) {
      throw new DomLimitExceededError(
        `进程级全部插件 VM 存活 DOM 总内存超过上限（最多 ${Math.round(this.maxProcessMemoryBytes / (1024 * 1024))} MB）`,
      );
    }
    if (!this._v8SafetyLedger.tryReserve(this._v8SafetyHolder, v8EstimatedBytes, this.maxV8SafetyMemoryBytes)) {
      this._processLedger.release(this._processHolder, addedTotal);
      throw new DomLimitExceededError(
        `开发工具 V8 兜底 DOM 内存超过上限（最多 ${Math.round(this.maxV8SafetyMemoryBytes / (1024 * 1024))} MB）`,
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
      v8EstimatedBytes,
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
    if (!this._processLedger.tryReserve(this._processHolder, HANDLE_ENTRY_BYTES, this.maxProcessMemoryBytes)) {
      throw new DomLimitExceededError(
        `进程级全部插件 VM 存活 DOM 与句柄表总内存超过上限（最多 ${Math.round(this.maxProcessMemoryBytes / (1024 * 1024))} MB）`,
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
