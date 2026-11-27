import {
  createDesignerElement,
  type DesignerElement,
  type DesignerElementKind,
  type DesignerScreen,
} from './apkDesignerEngine';
import {
  importCodeToDesignerScreen,
  type CodeImportContext,
} from './codeToDesignerEngine';
import type { ProjectFile } from './studioEngine';

export interface FullAppImportBundle {
  screens: DesignerScreen[];
  sourcePath: string;
  totalElements: number;
  counts: {
    pages: number;
    tabs: number;
    dialogs: number;
    activities: number;
  };
  notes: string[];
}

const SCREEN_WIDTH = 390;
const BASE_HEIGHT = 800;
const STATUS_BAR_H = 34;

let fullUid = 1;
function nextId(prefix: string): string {
  fullUid += 1;
  return `fa-${prefix}-${fullUid}`;
}

/* -------------------------------------------------------------------------- */
/* LOW-LEVEL LEXING & BALANCED READING HELPERS                                */
/* -------------------------------------------------------------------------- */

function skipStrOrComment(src: string, idx: number): number {
  if (src.startsWith('//', idx)) {
    const nl = src.indexOf('\n', idx + 2);
    return nl === -1 ? src.length : nl + 1;
  }
  if (src.startsWith('/*', idx)) {
    const end = src.indexOf('*/', idx + 2);
    return end === -1 ? src.length : end + 2;
  }
  if (src.startsWith('"""', idx)) {
    const end = src.indexOf('"""', idx + 3);
    return end === -1 ? src.length : end + 3;
  }
  if (src[idx] === '"') {
    let i = idx + 1;
    while (i < src.length) {
      if (src[i] === '\\') i += 2;
      else if (src[i] === '"') return i + 1;
      else i += 1;
    }
    return src.length;
  }
  if (src[idx] === "'") {
    let i = idx + 1;
    while (i < src.length) {
      if (src[i] === '\\') i += 2;
      else if (src[i] === "'") return i + 1;
      else i += 1;
    }
    return src.length;
  }
  return idx;
}

function readBalanced(
  src: string,
  openIdx: number,
  openCh: string,
  closeCh: string
): { text: string; nextIdx: number } {
  let depth = 0;
  let i = openIdx;
  while (i < src.length) {
    const sk = skipStrOrComment(src, i);
    if (sk !== i) {
      i = sk;
      continue;
    }
    if (src[i] === openCh) depth += 1;
    else if (src[i] === closeCh) {
      depth -= 1;
      if (depth === 0) {
        return { text: src.slice(openIdx + 1, i), nextIdx: i + 1 };
      }
    }
    i += 1;
  }
  return { text: src.slice(openIdx + 1), nextIdx: src.length };
}

function splitTopLevel(str: string, sep = ','): string[] {
  const out: string[] = [];
  let start = 0;
  let p = 0;
  let b = 0;
  let k = 0;
  let i = 0;
  while (i < str.length) {
    const sk = skipStrOrComment(str, i);
    if (sk !== i) {
      i = sk;
      continue;
    }
    const ch = str[i];
    if (ch === '(') p += 1;
    else if (ch === ')') p -= 1;
    else if (ch === '{') b += 1;
    else if (ch === '}') b -= 1;
    else if (ch === '[') k += 1;
    else if (ch === ']') k -= 1;
    else if (ch === sep && p === 0 && b === 0 && k === 0) {
      out.push(str.slice(start, i).trim());
      start = i + 1;
    }
    i += 1;
  }
  const tail = str.slice(start).trim();
  if (tail) out.push(tail);
  return out;
}

function splitKotlinStatements(block: string): string[] {
  const stmts: string[] = [];
  let start = 0;
  let p = 0;
  let b = 0;
  let k = 0;
  let i = 0;
  while (i < block.length) {
    const sk = skipStrOrComment(block, i);
    if (sk !== i) {
      i = sk;
      continue;
    }
    const ch = block[i];
    if (ch === '(') p += 1;
    else if (ch === ')') p = Math.max(0, p - 1);
    else if (ch === '{') b += 1;
    else if (ch === '}') b = Math.max(0, b - 1);
    else if (ch === '[') k += 1;
    else if (ch === ']') k = Math.max(0, k - 1);
    else if ((ch === '\n' || ch === ';') && p === 0 && b === 0 && k === 0) {
      const piece = block.slice(start, i).trim();
      if (piece && !piece.startsWith('//')) {
        stmts.push(piece);
      }
      start = i + 1;
    }
    i += 1;
  }
  const tail = block.slice(start).trim();
  if (tail && !tail.startsWith('//')) stmts.push(tail);
  return stmts;
}

function argbToCss(hex: string): string {
  const clean = hex.trim().replace(/^#/, '').replace(/^0x/i, '');
  if (/^[0-9a-fA-F]{6}$/.test(clean)) return `#${clean.toUpperCase()}`;
  if (/^[0-9a-fA-F]{8}$/.test(clean)) {
    const a = parseInt(clean.slice(0, 2), 16);
    if (a === 0) return 'transparent';
    return `#${clean.slice(2).toUpperCase()}`;
  }
  return '#3E8790';
}

function isLightHex(hex: string): boolean {
  if (!hex || !hex.startsWith('#') || hex.length !== 7) return false;
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return 0.299 * r + 0.587 * g + 0.114 * b > 165;
}

function resolveKotlinColor(expr: string | undefined): string | undefined {
  if (!expr) return undefined;
  const trimmed = expr.trim();
  if (!trimmed) return undefined;
  // Handle ternary `if (...) colorA else colorB` -> pick dark-theme / primary branch
  const ifMatch = trimmed.match(/^if\s*\([^)]*\)\s*([^\s]+(?:\([^)]*\))?)\s+else\s+([^\s]+(?:\([^)]*\))?)/);
  if (ifMatch) {
    return resolveKotlinColor(ifMatch[1]) ?? resolveKotlinColor(ifMatch[2]);
  }
  const hex8 = trimmed.match(/0x([0-9a-fA-F]{8})(?:\.toInt\(\))?/);
  if (hex8) return argbToCss(hex8[1]);
  const parseC = trimmed.match(/Color\.parseColor\(\s*"(#[0-9a-fA-F]{3,8})"\s*\)/);
  if (parseC) return argbToCss(parseC[1]);
  if (/Color\.WHITE\b/.test(trimmed)) return '#FFFFFF';
  if (/Color\.BLACK\b/.test(trimmed)) return '#000000';
  if (/Color\.TRANSPARENT\b/.test(trimmed)) return 'transparent';
  if (/Color\.GRAY\b|Color\.LTGRAY\b/.test(trimmed)) return '#9BB0BC';
  if (/^#[0-9a-fA-F]{6,8}$/.test(trimmed)) return argbToCss(trimmed);
  return undefined;
}

function extractFirstString(expr: string | undefined): string | undefined {
  if (!expr) return undefined;
  const trimmed = expr.trim();
  // Handle `if (...) "A" else "B"`
  const ifStr = trimmed.match(/if\s*\([^)]*\)\s*"((?:[^"\\]|\\.)*)"\s+else\s+"((?:[^"\\]|\\.)*)"/);
  if (ifStr) {
    return ifStr[1].replace(/\\n/g, '\n').replace(/\\"/g, '"');
  }
  // Handle prefs.getString("key", "Default text")
  const prefStr = trimmed.match(/getString\(\s*"[^"]*"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)/);
  if (prefStr) {
    return prefStr[1].replace(/\\n/g, '\n').replace(/\\"/g, '"');
  }
  const m = trimmed.match(/"((?:[^"\\]|\\.)*)"/);
  if (!m) return undefined;
  return m[1]
    .replace(/\\n/g, '\n')
    .replace(/\\"/g, '"')
    .replace(/\$\{[^}]+\}/g, '…')
    .replace(/\$[A-Za-z_][A-Za-z0-9_]*/g, '…');
}

function parseIntArg(expr: string | undefined): number | undefined {
  if (!expr) return undefined;
  const dpMatch = expr.match(/dp\(\s*(-?\d+(?:\.\d+)?)\s*\)/);
  if (dpMatch) return Math.round(parseFloat(dpMatch[1]));
  const num = expr.match(/(-?\d+(?:\.\d+)?)f?/);
  return num ? Math.round(parseFloat(num[1])) : undefined;
}

interface ParsedDrawable {
  fillColor?: string;
  strokeColor?: string;
  strokeWidth?: number;
  borderRadius?: number;
  isOval?: boolean;
}

function parseKotlinBackground(expr: string): ParsedDrawable {
  const res: ParsedDrawable = {};
  const trimmed = expr.trim();

  // premiumGradient(start, end, radius)
  const pgIdx = trimmed.indexOf('premiumGradient(');
  if (pgIdx !== -1) {
    const bal = readBalanced(trimmed, pgIdx + 'premiumGradient'.length, '(', ')');
    const args = splitTopLevel(bal.text);
    const c1 = resolveKotlinColor(args[0]);
    const c2 = resolveKotlinColor(args[1]);
    const rad = parseIntArg(args[2]);
    res.fillColor = c1 ?? c2;
    if (rad !== undefined) res.borderRadius = rad;
    return res;
  }

  // roundedSurface(color, radius)
  const rsIdx = trimmed.indexOf('roundedSurface(');
  if (rsIdx !== -1) {
    const bal = readBalanced(trimmed, rsIdx + 'roundedSurface'.length, '(', ')');
    const args = splitTopLevel(bal.text);
    res.fillColor = resolveKotlinColor(args[0]);
    const rad = parseIntArg(args[1]);
    if (rad !== undefined) res.borderRadius = rad;
    return res;
  }

  // GradientDrawable(...)
  if (trimmed.includes('GradientDrawable')) {
    const colorsMatch = trimmed.match(/intArrayOf\s*\(([^)]+)\)/);
    if (colorsMatch) {
      const parts = splitTopLevel(colorsMatch[1]);
      res.fillColor = resolveKotlinColor(parts[0]);
    }
    const setColorMatch = trimmed.match(/setColor\s*\(([^)]+)\)/);
    if (setColorMatch) {
      res.fillColor = resolveKotlinColor(setColorMatch[1]);
    }
    const radiusMatch = trimmed.match(/cornerRadius\s*=\s*([^\n;]+)/);
    if (radiusMatch) {
      res.borderRadius = parseIntArg(radiusMatch[1]);
    }
    const strokeIdx = trimmed.indexOf('setStroke(');
    if (strokeIdx !== -1) {
      const sBal = readBalanced(trimmed, strokeIdx + 'setStroke'.length, '(', ')');
      const sArgs = splitTopLevel(sBal.text);
      res.strokeWidth = Math.max(1, parseIntArg(sArgs[0]) ?? 1);
      res.strokeColor = resolveKotlinColor(sArgs[1]);
    }
    if (/GradientDrawable\.OVAL/.test(trimmed)) {
      res.isOval = true;
      res.borderRadius = 999;
    }
    return res;
  }

  const plainColor = resolveKotlinColor(trimmed);
  if (plainColor) res.fillColor = plainColor;
  return res;
}

function parseGravity(expr: string): 'left' | 'center' | 'right' | undefined {
  if (/CENTER_HORIZONTAL|\bCENTER\b/.test(expr) && !/START|LEFT/.test(expr)) return 'center';
  if (/END|RIGHT/.test(expr)) return 'right';
  if (/START|LEFT/.test(expr)) return 'left';
  return undefined;
}

/* -------------------------------------------------------------------------- */
/* VIRTUAL PROGRAMMATIC KOTLIN VIEW GRAPH                                     */
/* -------------------------------------------------------------------------- */

interface KtViewNode {
  id: string;
  varName?: string;
  kind:
    | 'container'
    | 'card'
    | 'text'
    | 'button'
    | 'input'
    | 'spinner'
    | 'progress'
    | 'image'
    | 'switch'
    | 'spacer'
    | 'menuTile'
    | 'accordionTile'
    | 'navTab';
  orientation?: 'vertical' | 'horizontal';
  text?: string;
  subtitle?: string;
  symbol?: string;
  hint?: string;
  fontSize?: number;
  textColor?: string;
  bold?: boolean;
  textAlign?: 'left' | 'center' | 'right';
  fillColor?: string;
  strokeColor?: string;
  strokeWidth?: number;
  borderRadius?: number;
  isOval?: boolean;
  elevation?: number;
  padTop: number;
  padRight: number;
  padBottom: number;
  padLeft: number;
  width?: number;
  height?: number;
  weight?: number;
  marginBottom?: number;
  marginLeft?: number;
  progress?: number;
  checked?: boolean;
  children: KtViewNode[];
}

interface HelperDefaults {
  cardFill: string;
  cardRadius: number;
  cardPad: number;
  primaryBtnFill: string;
  primaryBtnText: string;
  secondaryBtnFill: string;
  secondaryBtnText: string;
  btnRadius: number;
  btnHeight: number;
  smallBtnFill: string;
  smallBtnText: string;
  menuTileFill: string;
  menuTileRadius: number;
  menuTileHeight: number;
  menuTileIconColor: string;
  menuTileTextColor: string;
  sectionFill: string;
  sectionRadius: number;
  navBtnFill: string;
  navBtnActiveFill: string;
  navBtnText: string;
}

function extractHelperDefaults(source: string): HelperDefaults {
  const defaults: HelperDefaults = {
    cardFill: '#2B3742',
    cardRadius: 18,
    cardPad: 14,
    primaryBtnFill: '#568B91',
    primaryBtnText: '#FFFFFF',
    secondaryBtnFill: '#344653',
    secondaryBtnText: '#DCE5E9',
    btnRadius: 12,
    btnHeight: 46,
    smallBtnFill: '#344653',
    smallBtnText: '#EEF4F6',
    menuTileFill: '#2B3742',
    menuTileRadius: 14,
    menuTileHeight: 58,
    menuTileIconColor: '#91B5B5',
    menuTileTextColor: '#E2E8ED',
    sectionFill: '#263640',
    sectionRadius: 14,
    navBtnFill: '#344653',
    navBtnActiveFill: '#568B91',
    navBtnText: '#E2E8ED',
  };

  // Inspect card() body
  const cardFn = extractFunctionBody(source, 'card');
  if (cardFn) {
    const bg = cardFn.match(/background\s*=\s*([^\n]+)/);
    if (bg) {
      const parsed = parseKotlinBackground(bg[1]);
      if (parsed.fillColor) defaults.cardFill = parsed.fillColor;
      if (parsed.borderRadius !== undefined) defaults.cardRadius = parsed.borderRadius;
    }
    const pad = cardFn.match(/setPadding\s*\(\s*([^)]+)\)/);
    if (pad) {
      const p0 = parseIntArg(splitTopLevel(pad[1])[0]);
      if (p0 !== undefined) defaults.cardPad = p0;
    }
  }

  // Inspect actionButton() body
  const actFn = extractFunctionBody(source, 'actionButton');
  if (actFn) {
    const colorLine = actFn.match(/setColor\s*\(\s*if\s*\([^)]*\)\s*(0x[0-9a-fA-F]{8})[^e]*else\s*(0x[0-9a-fA-F]{8})/);
    if (colorLine) {
      defaults.primaryBtnFill = argbToCss(colorLine[1]);
      defaults.secondaryBtnFill = argbToCss(colorLine[2]);
    }
    const textLine = actFn.match(/setTextColor\s*\(\s*if\s*\([^)]*\)\s*(0x[0-9a-fA-F]{8})[^e]*else\s*(0x[0-9a-fA-F]{8})/);
    if (textLine) {
      defaults.primaryBtnText = argbToCss(textLine[1]);
      defaults.secondaryBtnText = argbToCss(textLine[2]);
    }
    const rad = actFn.match(/cornerRadius\s*=\s*([^\n;]+)/);
    if (rad) {
      const r = parseIntArg(rad[1]);
      if (r !== undefined) defaults.btnRadius = r;
    }
  }

  // Inspect welcomeMenuItem() body
  const menuFn = extractFunctionBody(source, 'welcomeMenuItem');
  if (menuFn) {
    const bg = menuFn.match(/background\s*=\s*([^\n]+)/);
    if (bg) {
      const parsed = parseKotlinBackground(bg[1]);
      if (parsed.fillColor) defaults.menuTileFill = parsed.fillColor;
      if (parsed.borderRadius !== undefined) defaults.menuTileRadius = parsed.borderRadius;
    }
  }

  // Inspect workspaceSection() body
  const secFn = extractFunctionBody(source, 'workspaceSection');
  if (secFn) {
    const bg = secFn.match(/background\s*=\s*([^\n]+)/);
    if (bg) {
      const parsed = parseKotlinBackground(bg[1]);
      if (parsed.fillColor) defaults.sectionFill = parsed.fillColor;
      if (parsed.borderRadius !== undefined) defaults.sectionRadius = parsed.borderRadius;
    }
  }

  return defaults;
}

function extractFunctionBody(source: string, fnName: string): string | null {
  const re = new RegExp(`\\bfun\\s+${fnName}\\s*\\(`);
  const m = re.exec(source);
  if (!m) return null;
  const openParen = m.index + m[0].length - 1;
  const { nextIdx } = readBalanced(source, openParen, '(', ')');
  let cursor = nextIdx;
  while (cursor < source.length && source[cursor] !== '{' && source[cursor] !== '=') {
    cursor += 1;
  }
  if (source[cursor] === '=') {
    // Expression-bodied function: scan until matching brace or end of chained `.apply { ... }`
    let c = cursor + 1;
    while (c < source.length && /\s/.test(source[c])) c += 1;
    let end = c;
    let sawBrace = false;
    while (end < source.length) {
      const sk = skipStrOrComment(source, end);
      if (sk !== end) {
        end = sk;
        continue;
      }
      if (source[end] === '(') {
        end = readBalanced(source, end, '(', ')').nextIdx;
        continue;
      }
      if (source[end] === '{') {
        sawBrace = true;
        end = readBalanced(source, end, '{', '}').nextIdx;
        // Could continue with `.apply { ... }`
        let look = end;
        while (look < source.length && /\s/.test(source[look])) look += 1;
        if (source[look] === '.') {
          end = look;
          continue;
        }
        break;
      }
      if (source[end] === '\n' && sawBrace) break;
      end += 1;
    }
    return source.slice(c, end);
  }
  if (source[cursor] === '{') {
    return readBalanced(source, cursor, '{', '}').text;
  }
  return null;
}

function makeKtNode(kind: KtViewNode['kind'], overrides: Partial<KtViewNode> = {}): KtViewNode {
  return {
    id: nextId(kind),
    kind,
    padTop: 0,
    padRight: 0,
    padBottom: 0,
    padLeft: 0,
    children: [],
    ...overrides,
  };
}

function applyPropertyBlock(
  node: KtViewNode,
  body: string,
  env: Map<string, KtViewNode>,
  defaults: HelperDefaults,
  fullSource: string
) {
  const stmts = splitKotlinStatements(body);
  for (const stmt of stmts) {
    if (/^orientation\s*=/.test(stmt)) {
      node.orientation = /HORIZONTAL|= 0\b/.test(stmt) ? 'horizontal' : 'vertical';
      continue;
    }
    if (/^(?:this\.)?text\s*=/.test(stmt)) {
      const t = extractFirstString(stmt.slice(stmt.indexOf('=') + 1));
      if (t !== undefined) node.text = t;
      continue;
    }
    if (/^hint\s*=/.test(stmt)) {
      const h = extractFirstString(stmt.slice(stmt.indexOf('=') + 1));
      if (h !== undefined) node.hint = h;
      continue;
    }
    if (/^setText\s*\(/.test(stmt)) {
      const t = extractFirstString(stmt);
      if (t !== undefined) node.text = t;
      continue;
    }
    if (/^textSize\s*=/.test(stmt)) {
      const n = parseIntArg(stmt.slice(stmt.indexOf('=') + 1));
      if (n !== undefined) node.fontSize = n;
      continue;
    }
    if (/^setTextColor\s*\(/.test(stmt)) {
      const open = stmt.indexOf('(');
      const arg = readBalanced(stmt, open, '(', ')').text;
      const c = resolveKotlinColor(arg);
      if (c) node.textColor = c;
      continue;
    }
    if (/^setBackgroundColor\s*\(/.test(stmt)) {
      const open = stmt.indexOf('(');
      const arg = readBalanced(stmt, open, '(', ')').text;
      const c = resolveKotlinColor(arg);
      if (c) node.fillColor = c;
      continue;
    }
    if (/^background\s*=/.test(stmt)) {
      const parsed = parseKotlinBackground(stmt.slice(stmt.indexOf('=') + 1));
      if (parsed.fillColor) node.fillColor = parsed.fillColor;
      if (parsed.strokeColor) node.strokeColor = parsed.strokeColor;
      if (parsed.strokeWidth !== undefined) node.strokeWidth = parsed.strokeWidth;
      if (parsed.borderRadius !== undefined) node.borderRadius = parsed.borderRadius;
      if (parsed.isOval) node.isOval = true;
      continue;
    }
    if (/^setPadding\s*\(/.test(stmt)) {
      const open = stmt.indexOf('(');
      const args = splitTopLevel(readBalanced(stmt, open, '(', ')').text);
      node.padLeft = parseIntArg(args[0]) ?? node.padLeft;
      node.padTop = parseIntArg(args[1]) ?? node.padTop;
      node.padRight = parseIntArg(args[2]) ?? node.padRight;
      node.padBottom = parseIntArg(args[3]) ?? node.padBottom;
      continue;
    }
    if (/^gravity\s*=/.test(stmt)) {
      const g = parseGravity(stmt.slice(stmt.indexOf('=') + 1));
      if (g) node.textAlign = g;
      continue;
    }
    if (/^elevation\s*=/.test(stmt)) {
      const el = parseIntArg(stmt.slice(stmt.indexOf('=') + 1));
      if (el !== undefined) node.elevation = el;
      continue;
    }
    if (/^minWidth\s*=/.test(stmt)) {
      const w = parseIntArg(stmt.slice(stmt.indexOf('=') + 1));
      if (w !== undefined) node.width = Math.max(node.width ?? 0, w);
      continue;
    }
    if (/^minHeight\s*=/.test(stmt)) {
      const h = parseIntArg(stmt.slice(stmt.indexOf('=') + 1));
      if (h !== undefined) node.height = Math.max(node.height ?? 0, h);
      continue;
    }
    if (/^progressTintList\s*=/.test(stmt)) {
      const c = resolveKotlinColor(stmt);
      if (c) node.strokeColor = c;
      continue;
    }
    if (/^progress\s*=/.test(stmt)) {
      const p = parseIntArg(stmt.slice(stmt.indexOf('=') + 1));
      if (p !== undefined) node.progress = p;
      continue;
    }
    if (/Typeface\.BOLD|DEFAULT_BOLD/.test(stmt)) {
      node.bold = true;
      continue;
    }
    if (/^layoutParams\s*=/.test(stmt)) {
      applyLayoutParamsExpr(node, stmt.slice(stmt.indexOf('=') + 1));
      continue;
    }
    // Nested `addView(...)` directly inside `.apply { ... }`
    if (/^addView\s*\(/.test(stmt)) {
      const open = stmt.indexOf('(');
      const args = splitTopLevel(readBalanced(stmt, open, '(', ')').text);
      const child = evalViewExpr(args[0], env, defaults, fullSource);
      if (child) {
        if (args[1]) applyLayoutParamsExpr(child, args[1]);
        node.children.push(child);
      }
      continue;
    }
  }
}

function applyLayoutParamsExpr(node: KtViewNode, lpExpr: string) {
  const lpIdx = lpExpr.indexOf('LayoutParams(');
  if (lpIdx !== -1) {
    const bal = readBalanced(lpExpr, lpIdx + 'LayoutParams'.length, '(', ')');
    const args = splitTopLevel(bal.text);
    const w = parseIntArg(args[0]);
    const h = parseIntArg(args[1]);
    if (w !== undefined && w > 0) node.width = w;
    if (h !== undefined && h > 0) node.height = h;
    if (args[2]) {
      const wt = parseFloat(args[2].replace(/f$/i, ''));
      if (Number.isFinite(wt) && wt > 0) node.weight = wt;
    }
  }
  const bm = lpExpr.match(/bottomMargin\s*=\s*([^\n;]+)/);
  if (bm) node.marginBottom = parseIntArg(bm[1]);
  const lm = lpExpr.match(/leftMargin\s*=\s*([^\n;]+)/);
  if (lm) node.marginLeft = parseIntArg(lm[1]);
}

function evalViewExpr(
  rawExpr: string | undefined,
  env: Map<string, KtViewNode>,
  defaults: HelperDefaults,
  fullSource: string
): KtViewNode | null {
  if (!rawExpr) return null;
  const expr = rawExpr.trim();
  if (!expr) return null;

  // 1. Variable reference
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(expr) && env.has(expr)) {
    return env.get(expr)!;
  }

  // Separate optional trailing `.apply { ... }`
  let baseExpr = expr;
  let applyBody = '';
  const applyIdx = findTopLevelApply(expr);
  if (applyIdx !== -1) {
    baseExpr = expr.slice(0, applyIdx).trim();
    const braceIdx = expr.indexOf('{', applyIdx);
    if (braceIdx !== -1) {
      applyBody = readBalanced(expr, braceIdx, '{', '}').text;
    }
  }

  let node: KtViewNode | null = null;

  // 2. Helper functions
  if (/^card\s*\(/.test(baseExpr)) {
    node = makeKtNode('card', {
      orientation: 'vertical',
      fillColor: defaults.cardFill,
      borderRadius: defaults.cardRadius,
      padTop: defaults.cardPad,
      padRight: defaults.cardPad,
      padBottom: defaults.cardPad,
      padLeft: defaults.cardPad,
      elevation: 3,
    });
  } else if (/^label\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const args = splitTopLevel(bal.text);
    const text = extractFirstString(args[0]) ?? '';
    const size = parseIntArg(args[1]) ?? 14;
    const color = resolveKotlinColor(args[2]);
    const bold = args[3]?.trim() === 'true' || args[2]?.trim() === 'true';
    node = makeKtNode('text', {
      text,
      fontSize: size,
      textColor: color,
      bold,
      padTop: 2,
      padBottom: 2,
    });
  } else if (/^actionButton\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const args = splitTopLevel(bal.text);
    const text = extractFirstString(args[0]) ?? 'Action';
    const primary = args[1]?.trim() === 'true';
    node = makeKtNode('button', {
      text,
      fontSize: 14,
      bold: true,
      fillColor: primary ? defaults.primaryBtnFill : defaults.secondaryBtnFill,
      textColor: primary ? defaults.primaryBtnText : defaults.secondaryBtnText,
      borderRadius: defaults.btnRadius,
      height: defaults.btnHeight,
      textAlign: 'center',
    });
  } else if (/^(?:smallButton|deviceButton)\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const args = splitTopLevel(bal.text);
    const text = extractFirstString(args[0]) ?? 'Button';
    node = makeKtNode('button', {
      text,
      fontSize: 12,
      bold: true,
      fillColor: defaults.smallBtnFill,
      textColor: defaults.smallBtnText,
      borderRadius: 10,
      height: 40,
      textAlign: 'center',
    });
  } else if (/^welcomeMenuItem\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const args = splitTopLevel(bal.text);
    const symbol = extractFirstString(args[0]) ?? '•';
    const title = extractFirstString(args[1]) ?? 'Menu Item';
    node = makeKtNode('menuTile', {
      symbol,
      text: title,
      fillColor: defaults.menuTileFill,
      textColor: defaults.menuTileTextColor,
      strokeColor: defaults.menuTileIconColor,
      borderRadius: defaults.menuTileRadius,
      height: defaults.menuTileHeight,
    });
  } else if (/^navButton\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const args = splitTopLevel(bal.text);
    const symbol = extractFirstString(args[0]) ?? '•';
    const caption = extractFirstString(args[1]) ?? 'Tab';
    node = makeKtNode('navTab', {
      symbol,
      text: caption,
      fillColor: defaults.navBtnFill,
      textColor: defaults.navBtnText,
      borderRadius: 13,
      height: 54,
    });
  } else if (/^heroText\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const text = extractFirstString(bal.text) ?? '';
    node = makeKtNode('text', {
      text,
      fontSize: 15,
      textColor: '#315E70',
      bold: false,
      textAlign: 'center',
      width: text.length <= 2 ? 14 : Math.max(46, text.length * 10),
    });
  } else if (/^heroActionArrow\s*\(/.test(baseExpr)) {
    node = makeKtNode('button', {
      text: '→',
      fontSize: 22,
      bold: true,
      fillColor: '#3E8790',
      textColor: '#FFFFFF',
      strokeColor: '#75AEB1',
      strokeWidth: 1,
      borderRadius: 16,
      width: 54,
      height: 40,
      textAlign: 'center',
    });
  } else if (/^space\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const h = parseIntArg(bal.text) ?? 8;
    node = makeKtNode('spacer', { height: h, width: h });
  } else if (/^spin\s*\(/.test(baseExpr)) {
    const bal = readBalanced(baseExpr, baseExpr.indexOf('('), '(', ')');
    const firstOpt = extractFirstString(bal.text) ?? 'Select option';
    node = makeKtNode('spinner', {
      text: `${firstOpt}  ▾`,
      height: 44,
    });
  } else if (/^(?:android\.widget\.)?LinearLayout\s*\(/.test(baseExpr)) {
    node = makeKtNode('container', { orientation: 'vertical' });
  } else if (/^(?:android\.widget\.)?(?:ScrollView|HorizontalScrollView|FrameLayout)\s*\(/.test(baseExpr)) {
    node = makeKtNode('container', { orientation: 'vertical' });
  } else if (/^(?:android\.widget\.)?TextView\s*\(/.test(baseExpr)) {
    node = makeKtNode('text', { text: '', fontSize: 14 });
  } else if (/^(?:android\.widget\.)?Button\s*\(/.test(baseExpr)) {
    node = makeKtNode('button', {
      text: 'Button',
      fontSize: 14,
      bold: true,
      fillColor: defaults.secondaryBtnFill,
      textColor: '#FFFFFF',
      borderRadius: 10,
      height: 44,
      textAlign: 'center',
    });
  } else if (/^(?:android\.widget\.)?EditText\s*\(/.test(baseExpr)) {
    node = makeKtNode('input', { hint: 'Enter text…', height: 46 });
  } else if (/^(?:android\.widget\.)?Spinner\s*\(/.test(baseExpr)) {
    node = makeKtNode('spinner', { text: 'Select option  ▾', height: 44 });
  } else if (/^(?:android\.widget\.)?ProgressBar\s*\(/.test(baseExpr)) {
    node = makeKtNode('progress', { height: 10, progress: 55, strokeColor: '#78A5A8' });
  } else if (/^(?:android\.widget\.)?ImageView\s*\(/.test(baseExpr)) {
    node = makeKtNode('image', { text: 'App Icon', width: 56, height: 56, borderRadius: 14 });
  } else if (/^(?:android\.widget\.)?(?:Switch|CheckBox)\s*\(/.test(baseExpr)) {
    node = makeKtNode('switch', { text: 'Option', height: 44 });
  } else if (/^(?:android\.view\.)?View\s*\(/.test(baseExpr)) {
    node = makeKtNode('spacer', { height: 8 });
  }

  if (node && applyBody) {
    applyPropertyBlock(node, applyBody, env, defaults, fullSource);
  }

  return node;
}

function findTopLevelApply(expr: string): number {
  let p = 0;
  let b = 0;
  let i = 0;
  let lastApply = -1;
  while (i < expr.length) {
    const sk = skipStrOrComment(expr, i);
    if (sk !== i) {
      i = sk;
      continue;
    }
    const ch = expr[i];
    if (ch === '(') p += 1;
    else if (ch === ')') p = Math.max(0, p - 1);
    else if (ch === '{') b += 1;
    else if (ch === '}') b = Math.max(0, b - 1);
    else if (p === 0 && b === 0 && expr.startsWith('.apply', i)) {
      const after = expr[i + 6];
      if (!after || /[\s{]/.test(after)) {
        lastApply = i;
      }
    }
    i += 1;
  }
  return lastApply;
}

interface ParsedBlockResult {
  env: Map<string, KtViewNode>;
  pageOrder: string[];
  rootVar?: string;
}

function evaluateKotlinUiBlock(
  block: string,
  defaults: HelperDefaults,
  fullSource: string
): ParsedBlockResult {
  const env = new Map<string, KtViewNode>();
  const pageOrder: string[] = [];
  let rootVar: string | undefined;

  // Strip local helper `fun ... { ... }` bodies before statement evaluation so helper definitions aren't executed as top-level views
  const cleaned = stripLocalFunDefs(block);
  const stmts = splitKotlinStatements(cleaned);

  for (const stmt of stmts) {
    // Skip control flow blocks we don't want to misinterpret except `if (landscapeMode) { ... } else { ... }` where we prefer portrait (else) branch!
    if (/^if\s*\(\s*landscapeMode\s*\)/.test(stmt)) {
      const elseIdx = stmt.lastIndexOf('else');
      if (elseIdx !== -1) {
        const brace = stmt.indexOf('{', elseIdx);
        if (brace !== -1) {
          const portraitBody = readBalanced(stmt, brace, '{', '}').text;
          const sub = evaluateKotlinUiBlock(portraitBody, defaults, fullSource);
          for (const [k, v] of sub.env.entries()) env.set(k, v);
        }
      }
      continue;
    }

    // 1. Destructuring `val (homeScroll, homeContent) = page()`
    const pairMatch = stmt.match(/^(?:val|var)\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)\s*=\s*page\s*\(\s*\)/);
    if (pairMatch) {
      const scrollName = pairMatch[1];
      const contentName = pairMatch[2];
      const node = makeKtNode('container', { varName: contentName, orientation: 'vertical' });
      env.set(scrollName, node);
      env.set(contentName, node);
      continue;
    }

    // 2. `val pages = listOf(homeScroll, editorScroll, ...)`
    const pagesMatch = stmt.match(/^(?:val|var)\s+pages\s*=\s*listOf\s*\(([^)]+)\)/);
    if (pagesMatch) {
      for (const item of splitTopLevel(pagesMatch[1])) {
        if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(item)) pageOrder.push(item);
      }
      continue;
    }

    // 3. `setContentView(root)`
    const scv = stmt.match(/^setContentView\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)/);
    if (scv) {
      rootVar = scv[1];
      continue;
    }

    // 4. `workspaceSection(title, subtitle, contentVar, expanded?, parentVar?)`
    if (/^workspaceSection\s*\(/.test(stmt)) {
      const bal = readBalanced(stmt, stmt.indexOf('('), '(', ')');
      const args = splitTopLevel(bal.text);
      const secTitle = extractFirstString(args[0]) ?? 'Section';
      const secSub = extractFirstString(args[1]) ?? '';
      const contentVar = args[2]?.trim();
      const parentVar = args[4]?.trim() || 'editorContent';
      const contentNode = contentVar ? env.get(contentVar) : undefined;
      const parentNode = env.get(parentVar);
      const tile = makeKtNode('accordionTile', {
        text: secTitle,
        subtitle: secSub,
        fillColor: defaults.sectionFill,
        borderRadius: defaults.sectionRadius,
        padTop: 10,
        padRight: 10,
        padBottom: 10,
        padLeft: 10,
        children: contentNode ? [contentNode] : [],
      });
      if (parentNode) parentNode.children.push(tile);
      continue;
    }

    // 5. `addPageBackHeader(targetVar, title)`
    if (/^addPageBackHeader\s*\(/.test(stmt)) {
      const bal = readBalanced(stmt, stmt.indexOf('('), '(', ')');
      const args = splitTopLevel(bal.text);
      const targetVar = args[0]?.trim();
      const title = extractFirstString(args[1]) ?? 'Page';
      const target = targetVar ? env.get(targetVar) : undefined;
      if (target) {
        const header = makeKtNode('container', {
          orientation: 'horizontal',
          padTop: 2,
          padBottom: 8,
          children: [
            makeKtNode('button', {
              text: '‹',
              fontSize: 22,
              bold: true,
              fillColor: '#344653',
              textColor: '#E2E8ED',
              borderRadius: 12,
              width: 44,
              height: 40,
              textAlign: 'center',
            }),
            makeKtNode('text', {
              text: title,
              fontSize: 17,
              textColor: '#E2E8ED',
              bold: true,
              weight: 1,
              marginLeft: 10,
            }),
          ],
        });
        target.children.unshift(header);
      }
      continue;
    }

    // 6. `addToolbarTile(expr)` / `title("...")` / `nav("...")` local shorthand calls
    if (/^addToolbarTile\s*\(/.test(stmt)) {
      const bal = readBalanced(stmt, stmt.indexOf('('), '(', ')');
      const child = evalViewExpr(bal.text, env, defaults, fullSource);
      const toolbar = env.get('toolbar');
      if (child && toolbar) toolbar.children.push(child);
      continue;
    }
    if (/^title\s*\(/.test(stmt)) {
      const bal = readBalanced(stmt, stmt.indexOf('('), '(', ')');
      const t = extractFirstString(bal.text);
      const box = env.get('box');
      if (t && box) {
        box.children.push(makeKtNode('text', { text: t, fontSize: 12, textColor: '#9AA7FF', bold: true }));
      }
      continue;
    }

    // 7. `listOf(a, b, c).forEach { target.addView(it) }`
    const listLoop = stmt.match(/^listOf\s*\(([^)]+)\)\.forEach\s*\{([\s\S]*)\}$/);
    if (listLoop) {
      const items = splitTopLevel(listLoop[1]);
      const addTarget = listLoop[2].match(/([A-Za-z_][A-Za-z0-9_]*)\.addView\s*\(\s*it\b/);
      if (addTarget && env.has(addTarget[1])) {
        const target = env.get(addTarget[1])!;
        for (const item of items) {
          const child = env.get(item.trim());
          if (child) target.children.push(child);
        }
      }
      continue;
    }

    // 8. `val/var name = expr` or `name = expr`
    const assignMatch = stmt.match(/^(?:(?:val|var)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([\s\S]+)$/);
    if (assignMatch && !assignMatch[1].includes('.')) {
      const vName = assignMatch[1];
      const rhs = assignMatch[2].trim();
      // Direct alias `contentRoot = root`
      if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(rhs) && env.has(rhs)) {
        env.set(vName, env.get(rhs)!);
        continue;
      }
      const created = evalViewExpr(rhs, env, defaults, fullSource);
      if (created) {
        created.varName = vName;
        env.set(vName, created);
      }
      continue;
    }

    // 9. `parent.addView(childExpr, lp?)` or `parent?.let { outer.addView(it) }`
    const letAdd = stmt.match(/^([A-Za-z_][A-Za-z0-9_]*)\?\.let\s*\{\s*([A-Za-z_][A-Za-z0-9_]*)\.addView\s*\(\s*it\s*\)\s*\}$/);
    if (letAdd) {
      const child = env.get(letAdd[1]);
      const parent = env.get(letAdd[2]);
      if (child && parent) parent.children.push(child);
      continue;
    }

    const addViewMatch = stmt.match(/^([A-Za-z_][A-Za-z0-9_]*)\??\.addView\s*\(/);
    if (addViewMatch) {
      const parentName = addViewMatch[1];
      const parent = env.get(parentName);
      if (parent) {
        const open = stmt.indexOf('(');
        const args = splitTopLevel(readBalanced(stmt, open, '(', ')').text);
        const child = evalViewExpr(args[0], env, defaults, fullSource);
        if (child) {
          if (args[1]) applyLayoutParamsExpr(child, args[1]);
          parent.children.push(child);
        }
      }
      continue;
    }

    // 10. Post-creation property mutation: `foo.background = ...`, `foo.gravity = ...`, `foo.setTextColor(...)`
    const propMut = stmt.match(/^([A-Za-z_][A-Za-z0-9_]*)\.([\s\S]+)$/);
    if (propMut && env.has(propMut[1])) {
      applyPropertyBlock(env.get(propMut[1])!, propMut[2], env, defaults, fullSource);
      continue;
    }
  }

  return { env, pageOrder, rootVar };
}

function stripLocalFunDefs(block: string): string {
  let out = '';
  let i = 0;
  while (i < block.length) {
    const sk = skipStrOrComment(block, i);
    if (sk !== i) {
      out += block.slice(i, sk);
      i = sk;
      continue;
    }
    if (
      block.startsWith('fun ', i) &&
      (i === 0 || /[\s;{}]/.test(block[i - 1]))
    ) {
      const paren = block.indexOf('(', i);
      if (paren !== -1) {
        const afterParams = readBalanced(block, paren, '(', ')').nextIdx;
        let c = afterParams;
        while (c < block.length && block[c] !== '{' && block[c] !== '=' && block[c] !== '\n') c += 1;
        if (block[c] === '{') {
          i = readBalanced(block, c, '{', '}').nextIdx;
          continue;
        }
        if (block[c] === '=') {
          const nl = block.indexOf('\n', c);
          i = nl === -1 ? block.length : nl + 1;
          continue;
        }
      }
    }
    out += block[i];
    i += 1;
  }
  return out;
}

/* -------------------------------------------------------------------------- */
/* LAYOUT VIRTUAL KT VIEW GRAPH -> EDITABLE DESIGNER ELEMENTS                 */
/* -------------------------------------------------------------------------- */

function layoutKtTreeToScreen(params: {
  name: string;
  screenBg: string;
  topChrome?: KtViewNode | null;
  bodyRoot: KtViewNode;
  bottomTabs?: { labels: string[]; activeIndex: number; fillColor?: string } | null;
  sourceFilePath: string;
  sourceSymbol: string;
  screenCategory: DesignerScreen['screenCategory'];
}): DesignerScreen {
  const { name, screenBg, topChrome, bodyRoot, bottomTabs, sourceFilePath, sourceSymbol, screenCategory } = params;
  const elements: DesignerElement[] = [];
  const defaultText = isLightHex(screenBg) ? '#142029' : '#EEF4F6';

  const makeEl = (kind: DesignerElementKind, overrides: Partial<DesignerElement>): DesignerElement => {
    const base = createDesignerElement(kind, SCREEN_WIDTH, BASE_HEIGHT, elements.length);
    return { ...base, id: nextId(kind), ...overrides };
  };

  let maxBottomY = STATUS_BAR_H + 40;

  const layoutNode = (
    node: KtViewNode,
    x: number,
    y: number,
    availW: number,
    parentTextFallback: string
  ): number => {
    if (node.kind === 'spacer') {
      return Math.min(28, Math.max(4, node.height ?? 8));
    }

    if (node.kind === 'menuTile') {
      const h = node.height ?? 56;
      const fill = node.fillColor ?? '#2B3742';
      const tileCard = makeEl('card', {
        name: node.text ?? 'Menu Tile',
        x: Math.round(x),
        y: Math.round(y),
        width: Math.round(availW),
        height: h,
        fillColor: fill,
        strokeColor: '#364652',
        strokeWidth: 1,
        borderRadius: node.borderRadius ?? 14,
        shadow: 2,
      });
      elements.push(tileCard);

      // Symbol icon
      elements.push(
        makeEl('text', {
          name: `${node.text} Icon`,
          x: Math.round(x + 12),
          y: Math.round(y + Math.floor((h - 28) / 2)),
          width: 34,
          height: 28,
          text: node.symbol ?? '•',
          fontSize: 18,
          fontWeight: '700',
          textColor: node.strokeColor ?? '#91B5B5',
          textAlign: 'center',
          fillColor: 'transparent',
        })
      );

      // Title
      elements.push(
        makeEl('heading', {
          name: node.text ?? 'Tile Title',
          x: Math.round(x + 52),
          y: Math.round(y + Math.floor((h - 24) / 2)),
          width: Math.max(80, Math.round(availW - 86)),
          height: 24,
          text: node.text ?? 'Menu Item',
          fontSize: 15,
          fontWeight: '600',
          textColor: node.textColor ?? '#E2E8ED',
          textAlign: 'left',
          fillColor: 'transparent',
        })
      );

      // Chevron ›
      elements.push(
        makeEl('text', {
          name: `${node.text} Arrow`,
          x: Math.round(x + availW - 30),
          y: Math.round(y + Math.floor((h - 26) / 2)),
          width: 20,
          height: 26,
          text: '›',
          fontSize: 20,
          fontWeight: '400',
          textColor: '#91A5B0',
          textAlign: 'center',
          fillColor: 'transparent',
        })
      );

      maxBottomY = Math.max(maxBottomY, y + h);
      return h + 8;
    }

    if (node.kind === 'accordionTile') {
      const fill = node.fillColor ?? '#263640';
      const secCard = makeEl('card', {
        name: node.text ?? 'Section Tile',
        x: Math.round(x),
        y: Math.round(y),
        width: Math.round(availW),
        height: 80,
        fillColor: fill,
        strokeColor: '#364A58',
        strokeWidth: 1,
        borderRadius: node.borderRadius ?? 14,
        shadow: 2,
      });
      elements.push(secCard);

      const innerX = x + 12;
      const innerW = Math.max(60, availW - 24);
      let cy = y + 10;

      // Section Header Title
      elements.push(
        makeEl('heading', {
          name: node.text ?? 'Section Title',
          x: Math.round(innerX),
          y: Math.round(cy),
          width: Math.round(innerW - 28),
          height: 22,
          text: node.text ?? 'SECTION',
          fontSize: 14,
          fontWeight: '700',
          textColor: '#E6F0F3',
          textAlign: 'left',
          fillColor: 'transparent',
        })
      );
      elements.push(
        makeEl('text', {
          name: 'Chevron',
          x: Math.round(x + availW - 34),
          y: Math.round(cy),
          width: 24,
          height: 24,
          text: '⌃',
          fontSize: 18,
          fontWeight: '700',
          textColor: '#9CC5C3',
          textAlign: 'center',
          fillColor: 'transparent',
        })
      );
      cy += 22;

      if (node.subtitle) {
        elements.push(
          makeEl('text', {
            name: `${node.text} Subtitle`,
            x: Math.round(innerX),
            y: Math.round(cy),
            width: Math.round(innerW - 20),
            height: 18,
            text: node.subtitle,
            fontSize: 11,
            fontWeight: '400',
            textColor: '#A8BAC3',
            textAlign: 'left',
            fillColor: 'transparent',
          })
        );
        cy += 22;
      }

      for (const child of node.children) {
        cy += layoutNode(child, innerX, cy, innerW, '#E6F0F3');
      }

      secCard.height = Math.max(64, Math.round(cy - y + 10));
      maxBottomY = Math.max(maxBottomY, y + secCard.height);
      return secCard.height + 10;
    }

    if (node.kind === 'card' || node.kind === 'container') {
      const hasVisualBg =
        node.kind === 'card' || Boolean(node.fillColor && node.fillColor !== 'transparent');
      const cardFill = node.fillColor ?? (node.kind === 'card' ? '#2B3742' : 'transparent');
      const nextTextFallback =
        hasVisualBg && cardFill !== 'transparent'
          ? isLightHex(cardFill)
            ? '#17232B'
            : '#EEF4F6'
          : parentTextFallback;

      let bgEl: DesignerElement | null = null;
      if (hasVisualBg) {
        bgEl = makeEl('card', {
          name: node.varName ?? (node.kind === 'card' ? 'Card' : 'Panel'),
          x: Math.round(x),
          y: Math.round(y),
          width: Math.round(availW),
          height: 80,
          fillColor: cardFill,
          strokeColor: node.strokeColor ?? (isLightHex(cardFill) ? '#D9E5E9' : '#354957'),
          strokeWidth: node.strokeWidth ?? 1,
          borderRadius: node.borderRadius ?? 16,
          shadow: node.elevation ?? 3,
        });
        elements.push(bgEl);
      }

      const padL = hasVisualBg ? Math.max(12, node.padLeft) : node.padLeft;
      const padR = hasVisualBg ? Math.max(12, node.padRight) : node.padRight;
      const padT = hasVisualBg ? Math.max(10, node.padTop) : node.padTop;
      const padB = hasVisualBg ? Math.max(10, node.padBottom) : node.padBottom;

      const innerX = x + padL;
      const innerW = Math.max(40, availW - padL - padR);

      if (node.orientation === 'horizontal' && node.children.length > 0) {
        const visibleChildren = node.children.filter((c) => c.kind !== 'spacer');
        const hasWeightedSibling = visibleChildren.some((c) => (c.weight ?? 0) > 0);

        const resolvedWidth = (c: KtViewNode): number | undefined => {
          if (c.width) return c.width;
          if (hasWeightedSibling && !c.weight && (c.kind === 'text' || c.kind === 'button')) {
            const labelLen = (c.text ?? '').trim().length;
            const fs = c.fontSize ?? 12;
            const padH = Math.max(12, c.padLeft + c.padRight);
            return Math.max(44, Math.min(116, Math.round(labelLen * fs * 0.68 + padH + 14)));
          }
          return undefined;
        };

        const fixedTotalW = visibleChildren.reduce((acc, c) => acc + (resolvedWidth(c) ?? 0), 0);
        const flexChildren = visibleChildren.filter((c) => resolvedWidth(c) === undefined);
        const totalWeight = flexChildren.reduce((acc, c) => acc + (c.weight ?? 1), 0);
        const gaps = Math.max(0, visibleChildren.length - 1) * 6;
        const remainingW = Math.max(40, innerW - fixedTotalW - gaps);

        // If all children have fixed widths and total width is smaller than innerW & container is centered, center the row!
        const allFixed = flexChildren.length === 0 && fixedTotalW + gaps < innerW;
        let cx =
          allFixed && node.textAlign === 'center'
            ? innerX + Math.round((innerW - (fixedTotalW + gaps)) / 2)
            : innerX;

        let maxH = 0;
        for (const child of node.children) {
          if (child.kind === 'spacer') {
            cx += Math.min(12, child.width ?? 6);
            continue;
          }
          const wrapW = resolvedWidth(child);
          const slotW =
            wrapW ??
            Math.max(
              38,
              Math.round((remainingW * (child.weight ?? 1)) / Math.max(1, totalWeight))
            );
          const childNode =
            wrapW && !child.width && child.fillColor && child.fillColor !== 'transparent'
              ? { ...child, textAlign: child.textAlign ?? ('center' as const) }
              : child;
          const ch = layoutNode(childNode, cx, y + padT, Math.min(slotW, innerW), nextTextFallback);
          maxH = Math.max(maxH, ch);
          cx += slotW + 6;
        }
        const totalH = Math.max(36, maxH + padT + padB);
        if (bgEl) bgEl.height = totalH;
        maxBottomY = Math.max(maxBottomY, y + totalH);
        return totalH + (hasVisualBg ? 10 : 4);
      }

      let cy = y + padT;
      for (const child of node.children) {
        const childW = child.width ? Math.min(child.width, innerW) : innerW;
        const childX =
          child.width && (node.textAlign === 'center' || child.textAlign === 'center')
            ? innerX + Math.round((innerW - childW) / 2)
            : innerX;
        const usedH = layoutNode(child, childX, cy, childW, nextTextFallback);
        cy += usedH;
      }

      const totalH = Math.max(hasVisualBg ? 52 : 0, Math.round(cy - y + padB));
      if (bgEl) bgEl.height = totalH;
      maxBottomY = Math.max(maxBottomY, y + totalH);
      return totalH + (hasVisualBg ? 10 : 4);
    }

    if (node.kind === 'text') {
      const label = (node.text ?? '').trim();
      if (!label) return 0;
      const fs = Math.min(30, Math.max(10, node.fontSize ?? 14));
      const w = node.width ? Math.min(node.width, availW) : availW;
      const lineCount = label
        .split('\n')
        .reduce((acc, line) => acc + Math.max(1, Math.ceil(line.length / Math.max(8, Math.floor(w / (fs * 0.56))))), 0);
      const padV = node.padTop + node.padBottom;
      const h = node.height ?? Math.max(22, Math.round(lineCount * fs * 1.42 + padV + 6));
      const isHeading = fs >= 17 || Boolean(node.bold);
      const hasBg = Boolean(node.fillColor && node.fillColor !== 'transparent');
      const kind: DesignerElementKind = node.isOval
        ? 'circle'
        : isHeading
        ? 'heading'
        : 'text';

      elements.push(
        makeEl(kind, {
          name: node.varName ?? label.slice(0, 22),
          x: Math.round(x),
          y: Math.round(y),
          width: Math.round(w),
          height: Math.round(h),
          text: label,
          fontSize: fs,
          fontWeight: node.bold ? '700' : '400',
          textColor: node.textColor ?? parentTextFallback,
          textAlign: node.textAlign ?? (hasBg && w <= 90 ? 'center' : 'left'),
          fillColor: hasBg ? node.fillColor! : 'transparent',
          strokeColor: node.strokeColor ?? 'transparent',
          strokeWidth: node.strokeWidth ?? 0,
          borderRadius: node.isOval ? 999 : node.borderRadius ?? (hasBg ? 12 : 0),
        })
      );
      maxBottomY = Math.max(maxBottomY, y + h);
      return h + 4;
    }

    if (node.kind === 'button' || node.kind === 'navTab') {
      const w = node.width ? Math.min(node.width, availW) : availW;
      const h = node.height ?? 44;
      const label = node.symbol ? `${node.symbol} ${node.text ?? ''}`.trim() : node.text ?? 'Button';
      elements.push(
        makeEl('button', {
          name: node.varName ?? label.slice(0, 22),
          x: Math.round(x),
          y: Math.round(y),
          width: Math.round(w),
          height: Math.round(h),
          text: label,
          fontSize: Math.min(24, Math.max(11, node.fontSize ?? 14)),
          fontWeight: node.bold ? '700' : '600',
          fillColor: node.fillColor ?? '#3E8790',
          textColor: node.textColor ?? '#FFFFFF',
          strokeColor: node.strokeColor ?? 'transparent',
          strokeWidth: node.strokeWidth ?? 0,
          borderRadius: node.borderRadius ?? 12,
          textAlign: node.textAlign ?? 'center',
          shadow: node.elevation ?? 2,
        })
      );
      maxBottomY = Math.max(maxBottomY, y + h);
      return h + 6;
    }

    if (node.kind === 'input' || node.kind === 'spinner') {
      const w = node.width ? Math.min(node.width, availW) : availW;
      const h = node.height ?? 44;
      elements.push(
        makeEl('input', {
          name: node.varName ?? (node.hint ?? node.text ?? 'Field').slice(0, 22),
          x: Math.round(x),
          y: Math.round(y),
          width: Math.round(w),
          height: Math.round(h),
          text: node.kind === 'spinner' ? node.text ?? 'Select ▾' : node.text ?? '',
          placeholder: node.hint ?? node.text ?? 'Enter value',
          fontSize: 13,
          textColor: '#EEF4F6',
          fillColor: '#141D25',
          strokeColor: '#344A5B',
          strokeWidth: 1,
          borderRadius: 10,
          textAlign: 'left',
        })
      );
      maxBottomY = Math.max(maxBottomY, y + h);
      return h + 8;
    }

    if (node.kind === 'progress') {
      const w = node.width ? Math.min(node.width, availW) : availW;
      const h = Math.max(8, node.height ?? 10);
      elements.push(
        makeEl('progress', {
          name: node.varName ?? 'Progress',
          x: Math.round(x),
          y: Math.round(y),
          width: Math.round(w),
          height: h,
          fillColor: '#1E2C37',
          strokeColor: node.strokeColor ?? '#78A5A8',
          progress: node.progress ?? 60,
        })
      );
      maxBottomY = Math.max(maxBottomY, y + h);
      return h + 8;
    }

    if (node.kind === 'image') {
      const w = node.width ? Math.min(node.width, availW) : 56;
      const h = node.height ?? 56;
      elements.push(
        makeEl('image', {
          name: node.varName ?? 'Image',
          x: Math.round(x),
          y: Math.round(y),
          width: Math.round(w),
          height: Math.round(h),
          text: node.text ?? 'Icon',
          fillColor: node.fillColor ?? '#2A3A47',
          borderRadius: node.isOval ? 999 : node.borderRadius ?? 12,
        })
      );
      maxBottomY = Math.max(maxBottomY, y + h);
      return h + 8;
    }

    if (node.kind === 'switch') {
      const h = node.height ?? 44;
      elements.push(
        makeEl('switch', {
          name: node.varName ?? 'Toggle',
          x: Math.round(x),
          y: Math.round(y),
          width: Math.round(availW),
          height: h,
          text: node.text ?? 'Option',
          textColor: parentTextFallback,
          fillColor: '#1D2B36',
          checked: Boolean(node.checked),
        })
      );
      maxBottomY = Math.max(maxBottomY, y + h);
      return h + 8;
    }

    return 0;
  };

  let curY = STATUS_BAR_H + 6;
  if (topChrome) {
    curY += layoutNode(topChrome, 12, curY, SCREEN_WIDTH - 24, defaultText);
    curY += 4;
  }

  curY += layoutNode(bodyRoot, 12, curY, SCREEN_WIDTH - 24, defaultText);

  let finalHeight = Math.max(BASE_HEIGHT, Math.round(Math.max(maxBottomY, curY) + (bottomTabs ? 88 : 36)));
  finalHeight = Math.min(2600, finalHeight);

  if (bottomTabs && bottomTabs.labels.length > 0) {
    const activeLabel = bottomTabs.labels[bottomTabs.activeIndex] ?? bottomTabs.labels[0];
    elements.push(
      makeEl('bottomNav', {
        name: 'Bottom Tab Bar',
        x: 8,
        y: finalHeight - 68,
        width: SCREEN_WIDTH - 16,
        height: 60,
        fillColor: bottomTabs.fillColor ?? '#273640',
        strokeColor: '#364A58',
        strokeWidth: 1,
        borderRadius: 16,
        text: bottomTabs.labels.join('|'),
        placeholder: activeLabel,
        textColor: '#E2E8ED',
      })
    );
  }

  return {
    id: nextId('screen'),
    name,
    width: SCREEN_WIDTH,
    height: finalHeight,
    backgroundColor: screenBg,
    elements,
    sourceFilePath,
    sourceSymbol,
    screenCategory,
  };
}

/* -------------------------------------------------------------------------- */
/* DIALOG / WINDOW EXTRACTOR (AlertDialog.Builder)                            */
/* -------------------------------------------------------------------------- */

function extractKotlinDialogScreens(
  source: string,
  filePath: string,
  defaults: HelperDefaults,
  screenBg: string
): DesignerScreen[] {
  const dialogs: DesignerScreen[] = [];
  const seenTitles = new Set<string>();
  const needle = 'AlertDialog.Builder';
  let idx = 0;

  while (idx < source.length && dialogs.length < 10) {
    const sk = skipStrOrComment(source, idx);
    if (sk !== idx) {
      idx = sk;
      continue;
    }
    if (!source.startsWith(needle, idx)) {
      idx += 1;
      continue;
    }
    const afterNeedle = idx + needle.length;
    let openParen = afterNeedle;
    while (openParen < source.length && /\s/.test(source[openParen])) openParen += 1;
    if (source[openParen] !== '(') {
      idx += needle.length;
      continue;
    }
    const startIdx = idx;
    idx = openParen + 1;
    // Read chained builder calls until `.show()` or `.create()`
    let cursor = startIdx;
    let chainEnd = Math.min(source.length, startIdx + 1800);
    while (cursor < chainEnd) {
      const sk = skipStrOrComment(source, cursor);
      if (sk !== cursor) {
        cursor = sk;
        continue;
      }
      if (source.startsWith('.show()', cursor) || source.startsWith('.create()', cursor)) {
        chainEnd = cursor + 9;
        break;
      }
      cursor += 1;
    }
    const chain = source.slice(startIdx, chainEnd);

    const titleIdx = chain.indexOf('.setTitle(');
    let dialogTitle = 'Dialog Window';
    if (titleIdx !== -1) {
      const tArg = readBalanced(chain, titleIdx + '.setTitle'.length, '(', ')').text;
      dialogTitle = extractFirstString(tArg) ?? dialogTitle;
    }
    // Skip dynamic interpolation titles that are just filenames or duplicates
    const cleanKey = dialogTitle.toLowerCase().trim();
    if (!dialogTitle || dialogTitle === '…' || seenTitles.has(cleanKey)) continue;
    seenTitles.add(cleanKey);

    let dialogMessage: string | undefined;
    const msgIdx = chain.indexOf('.setMessage(');
    if (msgIdx !== -1) {
      const mArg = readBalanced(chain, msgIdx + '.setMessage'.length, '(', ')').text;
      dialogMessage = extractFirstString(mArg);
    }

    let positiveBtn: string | undefined;
    const posIdx = chain.indexOf('.setPositiveButton(');
    if (posIdx !== -1) {
      const pArg = readBalanced(chain, posIdx + '.setPositiveButton'.length, '(', ')').text;
      positiveBtn = extractFirstString(splitTopLevel(pArg)[0]);
    }

    let negativeBtn: string | undefined;
    const negIdx = chain.indexOf('.setNegativeButton(');
    if (negIdx !== -1) {
      const nArg = readBalanced(chain, negIdx + '.setNegativeButton'.length, '(', ')').text;
      negativeBtn = extractFirstString(splitTopLevel(nArg)[0]);
    }

    let itemLabels: string[] = [];
    const itemsIdx = chain.indexOf('.setItems(');
    if (itemsIdx !== -1) {
      const iArg = readBalanced(chain, itemsIdx + '.setItems'.length, '(', ')').text;
      const arrPart = splitTopLevel(iArg)[0] ?? '';
      const rawItems = [...arrPart.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1]);
      if (rawItems.length > 0) itemLabels = rawItems;
    }

    // If `.setView(varName)` exists, inspect the enclosing block BEFORE AlertDialog.Builder to capture form fields!
    let customFormNode: KtViewNode | null = null;
    const viewIdx = chain.indexOf('.setView(');
    if (viewIdx !== -1) {
      const vName = readBalanced(chain, viewIdx + '.setView'.length, '(', ')').text.trim();
      // Walk backward to enclosing `{`
      let braceSearch = startIdx - 1;
      let depth = 0;
      while (braceSearch >= 0) {
        if (source[braceSearch] === '}') depth += 1;
        else if (source[braceSearch] === '{') {
          if (depth === 0) break;
          depth -= 1;
        }
        braceSearch -= 1;
      }
      if (braceSearch >= 0) {
        const prelude = source.slice(braceSearch + 1, startIdx);
        const evaluated = evaluateKotlinUiBlock(prelude, defaults, source);
        const target = evaluated.env.get(vName) ?? evaluated.env.get('box');
        if (target) {
          customFormNode =
            target.children.length === 1 && target.children[0].kind === 'container'
              ? target.children[0]
              : target;
        }
      }
    }

    // Build dialog card layout
    const dialogBody = makeKtNode('container', { orientation: 'vertical', padTop: 24 });
    const modalCard = makeKtNode('card', {
      varName: 'DialogWindow',
      orientation: 'vertical',
      fillColor: '#1E2A34',
      strokeColor: '#3B5263',
      strokeWidth: 1,
      borderRadius: 20,
      padTop: 18,
      padRight: 16,
      padBottom: 16,
      padLeft: 16,
      elevation: 14,
    });

    modalCard.children.push(
      makeKtNode('text', {
        text: dialogTitle,
        fontSize: 18,
        bold: true,
        textColor: '#EEF4F6',
      })
    );

    if (dialogMessage) {
      modalCard.children.push(
        makeKtNode('text', {
          text: dialogMessage,
          fontSize: 13,
          textColor: '#B6C5CE',
        })
      );
    }

    if (customFormNode) {
      if (customFormNode.kind === 'container' || customFormNode.kind === 'card') {
        modalCard.children.push(...customFormNode.children);
      } else {
        modalCard.children.push(customFormNode);
      }
    }

    for (const label of itemLabels) {
      modalCard.children.push(
        makeKtNode('menuTile', {
          symbol: '›',
          text: label,
          fillColor: '#273642',
          textColor: '#E2E8ED',
          strokeColor: '#78A5A8',
          borderRadius: 12,
          height: 46,
        })
      );
    }

    if (positiveBtn || negativeBtn) {
      const btnRow = makeKtNode('container', { orientation: 'horizontal', padTop: 10 });
      if (negativeBtn) {
        btnRow.children.push(
          makeKtNode('button', {
            text: negativeBtn,
            fillColor: '#2E404D',
            textColor: '#DCE5E9',
            borderRadius: 12,
            height: 42,
            weight: 1,
          })
        );
      }
      if (positiveBtn) {
        btnRow.children.push(
          makeKtNode('button', {
            text: positiveBtn,
            fillColor: defaults.primaryBtnFill,
            textColor: '#FFFFFF',
            borderRadius: 12,
            height: 42,
            weight: 1,
          })
        );
      }
      modalCard.children.push(btnRow);
    }

    dialogBody.children.push(modalCard);

    dialogs.push(
      layoutKtTreeToScreen({
        name: `Dialog: ${dialogTitle.slice(0, 34)}`,
        screenBg,
        bodyRoot: dialogBody,
        sourceFilePath: filePath,
        sourceSymbol: `dialog:${dialogTitle}`,
        screenCategory: 'dialog',
      })
    );
  }

  return dialogs;
}

/* -------------------------------------------------------------------------- */
/* EXTRACT ALL PAGES, TABS, TILES & WINDOWS FROM PROGRAMMATIC KOTLIN FILE     */
/* -------------------------------------------------------------------------- */

function prettifyPageName(varName: string, bodyNode: KtViewNode, tabLabel?: string): string {
  // Check if a page back header was prepended (first child horizontal container with back ‹ + title text)
  const first = bodyNode.children[0];
  if (first && first.orientation === 'horizontal' && first.children.length >= 2) {
    const titleChild = first.children.find((c) => c.kind === 'text' && c.text && c.text !== '‹');
    if (titleChild?.text) return titleChild.text;
  }
  if (tabLabel) {
    const cleaned = tabLabel.replace(/^[^\w]+/, '').trim();
    if (cleaned) return cleaned;
  }
  const clean = varName
    .replace(/(Scroll|Content|Page|Screen|Layout|Container)$/i, '')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .trim();
  if (!clean) return 'Main Page';
  return clean.charAt(0).toUpperCase() + clean.slice(1);
}

function extractProgrammaticKotlinScreens(ctx: CodeImportContext): DesignerScreen[] {
  const source = ctx.content;
  const fileName = ctx.filePath.split('/').pop()?.replace(/\.(kt|java)$/i, '') ?? 'Activity';
  const defaults = extractHelperDefaults(source);
  const screens: DesignerScreen[] = [];

  // 1. Find primary screen-building function (`buildScreen`, `showEditor`, `onCreate`)
  const mainBody =
    extractFunctionBody(source, 'buildScreen') ??
    extractFunctionBody(source, 'showEditor') ??
    extractFunctionBody(source, 'onCreate');

  let rootBg = '#202A33';

  if (mainBody) {
    const evaluated = evaluateKotlinUiBlock(mainBody, defaults, source);
    const rootNode =
      (evaluated.rootVar ? evaluated.env.get(evaluated.rootVar) : undefined) ??
      evaluated.env.get('root');
    if (rootNode?.fillColor && rootNode.fillColor !== 'transparent') {
      rootBg = rootNode.fillColor;
    }

    const appBar = evaluated.env.get('appBar') ?? null;
    const tabBar = evaluated.env.get('tabBar') ?? null;
    const tabLabels: string[] = [];
    if (tabBar) {
      for (const ch of tabBar.children) {
        const lbl = ch.symbol ? `${ch.symbol} ${ch.text ?? ''}`.trim() : ch.text ?? '';
        if (lbl) tabLabels.push(lbl);
      }
    }

    if (evaluated.pageOrder.length > 0) {
      // Multi-page / multi-tab app!
      evaluated.pageOrder.forEach((pageVar, idx) => {
        let pageNode = evaluated.env.get(pageVar);
        if (!pageNode) return;
        // Unwrap transparent single-child ScrollView wrappers (e.g. previewScroll -> previewContent)
        while (
          pageNode.kind === 'container' &&
          !pageNode.fillColor &&
          pageNode.children.length === 1 &&
          pageNode.children[0].kind === 'container'
        ) {
          pageNode = pageNode.children[0];
        }
        const tabLabel = tabLabels[idx];
        const pageTitle =
          idx === 0 && /home/i.test(pageVar)
            ? 'Home'
            : prettifyPageName(pageVar, pageNode, tabLabel);

        screens.push(
          layoutKtTreeToScreen({
            name: pageTitle,
            screenBg: rootBg,
            topChrome: appBar,
            bodyRoot: pageNode,
            bottomTabs:
              tabLabels.length > 0
                ? { labels: tabLabels, activeIndex: idx, fillColor: tabBar?.fillColor }
                : null,
            sourceFilePath: ctx.filePath,
            sourceSymbol: pageVar,
            screenCategory: tabLabels.length > 0 ? 'tab' : 'page',
          })
        );
      });
    } else if (rootNode && rootNode.children.length > 0) {
      // Single-page programmatic activity (e.g. ProjectEditorActivity, LivePreviewActivity)
      screens.push(
        layoutKtTreeToScreen({
          name: fileName.replace(/Activity$/, '') || fileName,
          screenBg: rootBg,
          bodyRoot: rootNode,
          sourceFilePath: ctx.filePath,
          sourceSymbol: 'root',
          screenCategory: 'activity',
        })
      );
    }
  }

  // 2. Scan additional screen/page builder functions (`buildEnvironmentSetupPage`, `build*Page`, `build*Screen`)
  const extraPageRe = /\bfun\s+(build[A-Za-z0-9_]*(?:Page|Screen|View|Panel))\s*\(\s*\)/g;
  let pm: RegExpExecArray | null;
  while ((pm = extraPageRe.exec(source)) !== null) {
    const fnName = pm[1];
    if (fnName === 'buildScreen') continue;
    const body = extractFunctionBody(source, fnName);
    if (!body) continue;
    const evaluated = evaluateKotlinUiBlock(body, defaults, source);
    const bodyNode = evaluated.env.get('body') ?? evaluated.env.get('root') ?? evaluated.env.get('box');
    if (!bodyNode || bodyNode.children.length === 0) continue;
    const bg = bodyNode.fillColor && bodyNode.fillColor !== 'transparent' ? bodyNode.fillColor : rootBg;
    // Clear bg on bodyNode itself so it doesn't render as a double card
    const cleanBody: KtViewNode = { ...bodyNode, fillColor: undefined };
    const niceName = fnName
      .replace(/^build/, '')
      .replace(/(Page|Screen|View)$/, '')
      .replace(/([a-z])([A-Z])/g, '$1 $2')
      .trim();
    screens.push(
      layoutKtTreeToScreen({
        name: niceName || fnName,
        screenBg: bg,
        bodyRoot: cleanBody,
        sourceFilePath: ctx.filePath,
        sourceSymbol: fnName,
        screenCategory: 'page',
      })
    );
  }

  // 3. Extract all AlertDialog windows
  const dialogs = extractKotlinDialogScreens(source, ctx.filePath, defaults, rootBg);
  screens.push(...dialogs);

  return screens;
}

/* -------------------------------------------------------------------------- */
/* MULTI-SCREEN JETPACK COMPOSE EXTRACTOR                                     */
/* -------------------------------------------------------------------------- */

function extractAllComposeScreens(ctx: CodeImportContext): DesignerScreen[] {
  const source = ctx.content;
  const screens: DesignerScreen[] = [];
  const fnRe = /@Composable[\s\S]{0,120}?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*\{/g;
  let m: RegExpExecArray | null;
  const foundFns: Array<{ name: string; fullBlock: string }> = [];

  while ((m = fnRe.exec(source)) !== null) {
    const fnName = m[1];
    if (/Theme$/.test(fnName)) continue;
    const openBrace = m.index + m[0].length - 1;
    const { text: body } = readBalanced(source, openBrace, '{', '}');
    const widgetHits = (
      body.match(
        /\b(Scaffold|Column|Row|Box|Surface|Card|TopAppBar|Text|Button|OutlinedTextField|Switch|Image|AlertDialog)\s*\(/g
      ) ?? []
    ).length;
    if (widgetHits < 2) continue;
    foundFns.push({
      name: fnName,
      fullBlock: `@Composable\nfun ${fnName}() {\n${body}\n}`,
    });
  }

  // Preserve color/string definitions from the top of the file for each isolated Composable
  const topDeclarations = source
    .split('\n')
    .filter((line) => /^\s*(val|var)\s+[A-Za-z_].*=.*(Color\(|")/.test(line))
    .join('\n');

  if (foundFns.length <= 1) {
    const single = importCodeToDesignerScreen(ctx);
    if (single.importedCount > 0) {
      screens.push({
        ...single.screen,
        screenCategory: 'page',
        sourceSymbol: foundFns[0]?.name ?? single.screen.name,
      });
    }
  } else {
    for (const fn of foundFns) {
      const res = importCodeToDesignerScreen({
        ...ctx,
        content: `${topDeclarations}\n\n${fn.fullBlock}`,
      });
      if (res.importedCount > 0) {
        const isDialog = /Dialog|Sheet|Modal|Popup/i.test(fn.name);
        const isTab = /Tab|Page/i.test(fn.name);
        screens.push({
          ...res.screen,
          name: fn.name,
          screenCategory: isDialog ? 'dialog' : isTab ? 'tab' : 'page',
          sourceSymbol: fn.name,
        });
      }
    }
  }

  // Extract inline Compose `AlertDialog(...)` windows
  const dlgRe = /\bAlertDialog\s*\(/g;
  let dm: RegExpExecArray | null;
  let dlgCount = 0;
  while ((dm = dlgRe.exec(source)) !== null && dlgCount < 6) {
    const open = dm.index + dm[0].length - 1;
    const args = readBalanced(source, open, '(', ')').text;
    const titleMatch = args.match(/title\s*=\s*\{([\s\S]*?)\}/);
    const textMatch = args.match(/text\s*=\s*\{([\s\S]*?)\}/);
    const title = extractFirstString(titleMatch?.[1]) ?? `Dialog ${dlgCount + 1}`;
    const msg = extractFirstString(textMatch?.[1]) ?? '';
    const confirmMatch = args.match(/confirmButton\s*=\s*\{([\s\S]*?)\}/);
    const confirmLbl = extractFirstString(confirmMatch?.[1]) ?? 'Confirm';
    const dismissMatch = args.match(/dismissButton\s*=\s*\{([\s\S]*?)\}/);
    const dismissLbl = extractFirstString(dismissMatch?.[1]);

    const body = makeKtNode('container', { orientation: 'vertical', padTop: 40 });
    const card = makeKtNode('card', {
      orientation: 'vertical',
      fillColor: '#1E2A34',
      strokeColor: '#3B5263',
      strokeWidth: 1,
      borderRadius: 20,
      padTop: 18,
      padRight: 16,
      padBottom: 16,
      padLeft: 16,
    });
    card.children.push(makeKtNode('text', { text: title, fontSize: 18, bold: true, textColor: '#EEF4F6' }));
    if (msg) card.children.push(makeKtNode('text', { text: msg, fontSize: 13, textColor: '#B6C5CE' }));
    const row = makeKtNode('container', { orientation: 'horizontal', padTop: 12 });
    if (dismissLbl) {
      row.children.push(makeKtNode('button', { text: dismissLbl, fillColor: '#2E404D', textColor: '#DCE5E9', height: 42, weight: 1 }));
    }
    row.children.push(makeKtNode('button', { text: confirmLbl, fillColor: '#3E8790', textColor: '#FFFFFF', height: 42, weight: 1 }));
    card.children.push(row);
    body.children.push(card);

    screens.push(
      layoutKtTreeToScreen({
        name: `Dialog: ${title.slice(0, 24)}`,
        screenBg: '#10171F',
        bodyRoot: body,
        sourceFilePath: ctx.filePath,
        sourceSymbol: `compose-dialog:${title}`,
        screenCategory: 'dialog',
      })
    );
    dlgCount += 1;
  }

  return screens;
}

/* -------------------------------------------------------------------------- */
/* PUBLIC API: SINGLE-FILE & FULL-PROJECT IMPORTERS                           */
/* -------------------------------------------------------------------------- */

function summarizeBundle(screens: DesignerScreen[], sourcePath: string, notes: string[]): FullAppImportBundle {
  const counts = { pages: 0, tabs: 0, dialogs: 0, activities: 0 };
  let totalElements = 0;
  for (const s of screens) {
    totalElements += s.elements.length;
    if (s.screenCategory === 'tab') counts.tabs += 1;
    else if (s.screenCategory === 'dialog') counts.dialogs += 1;
    else if (s.screenCategory === 'activity') counts.activities += 1;
    else counts.pages += 1;
  }
  return { screens, sourcePath, totalElements, counts, notes };
}

export function importAllScreensFromFile(ctx: CodeImportContext): FullAppImportBundle {
  const lower = ctx.filePath.toLowerCase();

  // 1. Android XML Layout
  if (lower.endsWith('.xml')) {
    const res = importCodeToDesignerScreen(ctx);
    const screen: DesignerScreen = {
      ...res.screen,
      screenCategory: 'xml',
      sourceSymbol: ctx.filePath.split('/').pop() ?? 'layout.xml',
    };
    return summarizeBundle(res.importedCount > 0 ? [screen] : [], ctx.filePath, res.notes);
  }

  // 2. Kotlin / Java files
  if (lower.endsWith('.kt') || lower.endsWith('.java')) {
    const hasCompose =
      /(?:^|\n)\s*@Composable\b/.test(ctx.content) ||
      (/\bsetContent\s*\{/.test(ctx.content) && /\b(Column|Row|Box|Scaffold|Surface|Text|Button)\s*\(/.test(ctx.content));

    if (hasCompose) {
      const composeScreens = extractAllComposeScreens(ctx);
      if (composeScreens.length > 0) {
        return summarizeBundle(composeScreens, ctx.filePath, [
          `Extracted ${composeScreens.length} Jetpack Compose screens/windows from ${ctx.filePath.split('/').pop()}.`,
        ]);
      }
    }

    // Check `setContentView(R.layout.xxx)`
    const xmlRef = ctx.content.match(/setContentView\s*\(\s*R\.layout\.([A-Za-z0-9_]+)\s*\)/);
    if (xmlRef && ctx.projectFiles) {
      const targetXml = ctx.projectFiles.find((f) =>
        f.path.toLowerCase().endsWith(`/layout/${xmlRef[1].toLowerCase()}.xml`)
      );
      if (targetXml) {
        const xmlRes = importCodeToDesignerScreen({
          ...ctx,
          filePath: targetXml.path,
          content: targetXml.content,
        });
        const dialogs = extractKotlinDialogScreens(
          ctx.content,
          ctx.filePath,
          extractHelperDefaults(ctx.content),
          xmlRes.screen.backgroundColor
        );
        const list: DesignerScreen[] = [
          { ...xmlRes.screen, screenCategory: 'xml', sourceSymbol: xmlRef[1] },
          ...dialogs,
        ];
        return summarizeBundle(list, ctx.filePath, [
          `Followed R.layout.${xmlRef[1]} and extracted ${list.length} screens/dialogs.`,
        ]);
      }
    }

    // Deep programmatic Kotlin / Java Android Views graph
    const ktScreens = extractProgrammaticKotlinScreens(ctx);
    if (ktScreens.length > 0) {
      return summarizeBundle(ktScreens, ctx.filePath, [
        `Extracted ${ktScreens.length} pages, tabs, tiles, and dialog windows from ${ctx.filePath.split('/').pop()}.`,
      ]);
    }

    // Fallback to standard single-screen importer
    const fallback = importCodeToDesignerScreen(ctx);
    return summarizeBundle(
      fallback.importedCount > 0 ? [{ ...fallback.screen, screenCategory: 'activity' }] : [],
      ctx.filePath,
      fallback.notes
    );
  }

  return summarizeBundle([], ctx.filePath, ['Select a .kt, .java, or layout .xml file to extract UI screens.']);
}

export function importFullProjectToDesigner(
  projectFiles: ProjectFile[],
  preferredActivePath?: string
): FullAppImportBundle {
  // Rank UI files: preferred active file / MainActivity.kt first, then other *Activity.kt, then Compose .kt, then layout .xml
  const candidates = projectFiles
    .filter((f) => {
      const low = f.path.toLowerCase();
      if (low.includes('/test/') || low.includes('/androidtest/')) return false;
      if (low.endsWith('.xml') && low.includes('/layout/')) return true;
      if (low.endsWith('.kt') || low.endsWith('.java')) {
        return (
          /@Composable/.test(f.content) ||
          /setContentView\s*\(/.test(f.content) ||
          /\b(?:LinearLayout|TextView|Button|AlertDialog\.Builder)\s*\(/.test(f.content)
        );
      }
      return false;
    })
    .sort((a, b) => {
      const score = (p: string) => {
        if (preferredActivePath && p === preferredActivePath) return 0;
        if (p.endsWith('/MainActivity.kt') || p === 'MainActivity.kt') return 1;
        if (/Activity\.(kt|java)$/.test(p)) return 2;
        if (p.toLowerCase().endsWith('.kt')) return 3;
        return 4;
      };
      return score(a.path) - score(b.path) || a.path.localeCompare(b.path);
    });

  const allScreens: DesignerScreen[] = [];
  const seenKeys = new Set<string>();

  for (const file of candidates) {
    const bundle = importAllScreensFromFile({
      filePath: file.path,
      content: file.content,
      projectFiles,
    });
    const fileBase = file.path.split('/').pop()?.replace(/\.(kt|java|xml)$/i, '') ?? '';
    for (const s of bundle.screens) {
      const dedupKey = `${s.sourceFilePath ?? file.path}::${s.sourceSymbol ?? s.name}`;
      if (seenKeys.has(dedupKey)) continue;
      seenKeys.add(dedupKey);
      // Disambiguate dialog titles from secondary activities
      const alreadyHasName = allScreens.some((ex) => ex.name === s.name);
      const displayName = alreadyHasName && fileBase ? `${s.name} (${fileBase.replace(/Activity$/, '')})` : s.name;
      allScreens.push({ ...s, name: displayName });
      if (allScreens.length >= 28) break;
    }
    if (allScreens.length >= 28) break;
  }

  return summarizeBundle(
    allScreens,
    preferredActivePath ?? candidates[0]?.path ?? 'project',
    [`Imported ${allScreens.length} screens across ${candidates.length} UI source files.`]
  );
}
