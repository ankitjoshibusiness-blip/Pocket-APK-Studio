import {
  createDesignerElement,
  type DesignerElement,
  type DesignerElementKind,
  type DesignerScreen,
} from './apkDesignerEngine';
import type { ProjectFile } from './studioEngine';

export interface CodeImportContext {
  filePath: string;
  content: string;
  projectFiles?: ProjectFile[];
  screenWidth?: number;
  screenHeight?: number;
}

export interface CodeImportResult {
  screen: DesignerScreen;
  sourceKind: 'compose' | 'xml' | 'kotlin-views' | 'empty';
  sourcePath: string;
  importedCount: number;
  notes: string[];
}

interface DrawableShapeInfo {
  fillColor?: string;
  strokeColor?: string;
  strokeWidth?: number;
  borderRadius?: number;
}

interface ResourceMaps {
  colors: Map<string, string>;
  strings: Map<string, string>;
  drawables: Map<string, DrawableShapeInfo>;
  kotlinColors: Map<string, string>;
  kotlinStrings: Map<string, string>;
}

const DEFAULT_WIDTH = 390;
const DEFAULT_HEIGHT = 800;
const STATUS_BAR_OFFSET = 36;

const NAMED_COLORS: Record<string, string> = {
  white: '#FFFFFF',
  black: '#000000',
  transparent: 'transparent',
  gray: '#808080',
  grey: '#808080',
  darkgray: '#334155',
  dkgray: '#334155',
  lightgray: '#CBD5E1',
  ltgray: '#CBD5E1',
  red: '#EF4444',
  green: '#22C55E',
  blue: '#3B82F6',
  cyan: '#06B6D4',
  yellow: '#EAB308',
  magenta: '#D946EF',
};

const MATERIAL3_DEFAULTS: Record<string, string> = {
  primary: '#3E8790',
  onprimary: '#FFFFFF',
  primarycontainer: '#1F3A42',
  onprimarycontainer: '#D4EFF2',
  secondary: '#4B9BA2',
  onsecondary: '#FFFFFF',
  secondarycontainer: '#1D2F3A',
  background: '#10171F',
  onbackground: '#EEF4F6',
  surface: '#18232E',
  onsurface: '#EEF4F6',
  surfacevariant: '#1F2D3A',
  onsurfacevariant: '#9BB0BC',
  surfacecontainer: '#192632',
  surfacecontainerhigh: '#213140',
  outline: '#344A5B',
  outlinevariant: '#253542',
  error: '#EF4444',
  onerror: '#FFFFFF',
};

let importUid = 1;
function nextImportedId(prefix: string): string {
  importUid += 1;
  return `imp-${prefix}-${importUid}`;
}

function argbHexToCss(hex: string): string {
  const clean = hex.trim().replace(/^#/, '').replace(/^0x/i, '');
  if (/^[0-9a-fA-F]{6}$/.test(clean)) {
    return `#${clean.toUpperCase()}`;
  }
  if (/^[0-9a-fA-F]{8}$/.test(clean)) {
    const a = parseInt(clean.slice(0, 2), 16);
    if (a === 0) return 'transparent';
    return `#${clean.slice(2).toUpperCase()}`;
  }
  if (/^[0-9a-fA-F]{3}$/.test(clean)) {
    return `#${clean
      .split('')
      .map((c) => c + c)
      .join('')
      .toUpperCase()}`;
  }
  return '#3E8790';
}

function isLightHex(hex: string): boolean {
  if (!hex.startsWith('#') || hex.length !== 7) return false;
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  const luma = 0.299 * r + 0.587 * g + 0.114 * b;
  return luma > 160;
}

function parseDpNumber(raw: string | undefined): number | undefined {
  if (!raw) return undefined;
  const cleaned = raw.trim();
  const numMatch = cleaned.match(/(-?\d+(?:\.\d+)?)\s*(?:dp|dip|sp|px|f)?/i);
  if (!numMatch) return undefined;
  const val = parseFloat(numMatch[1]);
  return Number.isFinite(val) ? val : undefined;
}

function buildResourceMaps(activeContent: string, projectFiles: ProjectFile[] = []): ResourceMaps {
  const colors = new Map<string, string>();
  const strings = new Map<string, string>();
  const drawables = new Map<string, DrawableShapeInfo>();
  const kotlinColors = new Map<string, string>();
  const kotlinStrings = new Map<string, string>();

  // 1. Parse XML resources (colors.xml, strings.xml, drawable/*.xml)
  for (const file of projectFiles) {
    const lower = file.path.toLowerCase();
    if (lower.endsWith('.xml')) {
      if (lower.includes('/values') || lower.endsWith('colors.xml')) {
        const colorRe = /<color\s+name=["']([^"']+)["'][^>]*>\s*([^<]+?)\s*<\/color>/gi;
        let m: RegExpExecArray | null;
        while ((m = colorRe.exec(file.content)) !== null) {
          const val = m[2].trim();
          if (val.startsWith('#')) {
            colors.set(m[1], argbHexToCss(val));
          }
        }
        const strRe = /<string\s+name=["']([^"']+)["'][^>]*>([\s\S]*?)<\/string>/gi;
        while ((m = strRe.exec(file.content)) !== null) {
          strings.set(m[1], m[2].trim().replace(/\\n/g, '\n').replace(/\\'/g, "'"));
        }
      }
      if (lower.includes('/drawable') && file.content.includes('<shape')) {
        const name = file.path.split('/').pop()?.replace(/\.xml$/i, '') ?? '';
        if (name) {
          const solidMatch = file.content.match(/<solid[^>]*android:color\s*=\s*"([^"]+)"/i);
          const strokeColorMatch = file.content.match(/<stroke[^>]*android:color\s*=\s*"([^"]+)"/i);
          const strokeWidthMatch = file.content.match(/<stroke[^>]*android:width\s*=\s*"([^"]+)"/i);
          const radiusMatch = file.content.match(/<corners[^>]*android:radius\s*=\s*"([^"]+)"/i);
          drawables.set(name, {
            fillColor: solidMatch ? solidMatch[1] : undefined,
            strokeColor: strokeColorMatch ? strokeColorMatch[1] : undefined,
            strokeWidth: strokeWidthMatch ? parseDpNumber(strokeWidthMatch[1]) : undefined,
            borderRadius: radiusMatch ? parseDpNumber(radiusMatch[1]) : undefined,
          });
        }
      }
    }
  }

  // Resolve chained @color/ refs in drawables
  for (const [name, info] of drawables.entries()) {
    if (info.fillColor?.startsWith('@color/')) {
      info.fillColor = colors.get(info.fillColor.slice(7)) ?? info.fillColor;
    } else if (info.fillColor?.startsWith('#')) {
      info.fillColor = argbHexToCss(info.fillColor);
    }
    if (info.strokeColor?.startsWith('@color/')) {
      info.strokeColor = colors.get(info.strokeColor.slice(7)) ?? info.strokeColor;
    } else if (info.strokeColor?.startsWith('#')) {
      info.strokeColor = argbHexToCss(info.strokeColor);
    }
    drawables.set(name, info);
  }

  // 2. Scan Kotlin files (Color.kt, Theme.kt, active file) for color & string constants
  const ktSources = [
    ...projectFiles
      .filter((f) => f.path.toLowerCase().endsWith('.kt'))
      .map((f) => f.content),
    activeContent,
  ];

  for (const src of ktSources) {
    // val BrandTeal = Color(0xFF3E8790) or Color.parseColor("#3E8790") or 0xFF3E8790.toInt()
    const colorConstRe =
      /\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=\s*(?:Color\(\s*0x([0-9a-fA-F]{6,8})\s*\)|Color\.parseColor\(\s*"(#[0-9a-fA-F]{6,8})"\s*\)|0x([0-9a-fA-F]{8})\.toInt\(\))/g;
    let m: RegExpExecArray | null;
    while ((m = colorConstRe.exec(src)) !== null) {
      const hex = m[2] || m[3] || m[4];
      if (hex) kotlinColors.set(m[1], argbHexToCss(hex));
    }

    // Theme colorScheme slots: primary = Color(0xFF...) or primary = BrandTeal
    const schemeSlotRe =
      /\b(primary|onPrimary|primaryContainer|secondary|background|onBackground|surface|onSurface|surfaceVariant|onSurfaceVariant|outline|error)\s*=\s*(Color\(\s*0x[0-9a-fA-F]{6,8}\s*\)|[A-Za-z_][A-Za-z0-9_]*)/g;
    while ((m = schemeSlotRe.exec(src)) !== null) {
      const slot = m[1].toLowerCase();
      const rawVal = m[2];
      const literal = rawVal.match(/0x([0-9a-fA-F]{6,8})/);
      if (literal) {
        kotlinColors.set(`theme:${slot}`, argbHexToCss(literal[1]));
      } else if (kotlinColors.has(rawVal)) {
        kotlinColors.set(`theme:${slot}`, kotlinColors.get(rawVal)!);
      }
    }
  }

  // Local string vars/state in active file (e.g., var title by remember { mutableStateOf("Hello") })
  const stateStrRe =
    /\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:by\s+remember[^{]*\{\s*mutableStateOf\(\s*"([^"]*)"\s*\)\s*\}|=\s*"([^"]*)")/g;
  let sm: RegExpExecArray | null;
  while ((sm = stateStrRe.exec(activeContent)) !== null) {
    const val = sm[2] ?? sm[3];
    if (val !== undefined) kotlinStrings.set(sm[1], val);
  }

  return { colors, strings, drawables, kotlinColors, kotlinStrings };
}

function resolveColorExpr(raw: string | undefined, res: ResourceMaps): string | undefined {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (!trimmed) return undefined;

  if (trimmed === 'Color.Transparent' || trimmed === '@android:color/transparent') {
    return 'transparent';
  }
  const colorCall = trimmed.match(/Color\(\s*0x([0-9a-fA-F]{6,8})\s*\)/);
  if (colorCall) return argbHexToCss(colorCall[1]);

  const parseColor = trimmed.match(/Color\.parseColor\(\s*"(#[0-9a-fA-F]{3,8})"\s*\)/);
  if (parseColor) return argbHexToCss(parseColor[1]);

  const intHex = trimmed.match(/0x([0-9a-fA-F]{8})(?:\.toInt\(\))?/);
  if (intHex) return argbHexToCss(intHex[1]);

  const colorRes = trimmed.match(/colorResource\(\s*R\.color\.([A-Za-z0-9_]+)\s*\)/);
  if (colorRes) return res.colors.get(colorRes[1]);

  if (trimmed.startsWith('@color/')) {
    return res.colors.get(trimmed.slice(7));
  }
  if (trimmed.startsWith('@android:color/')) {
    const key = trimmed.slice('@android:color/'.length).toLowerCase();
    return NAMED_COLORS[key];
  }
  if (/^#[0-9a-fA-F]{3,8}$/.test(trimmed)) {
    return argbHexToCss(trimmed);
  }

  const themeMatch = trimmed.match(/colorScheme\.([A-Za-z0-9_]+)/);
  if (themeMatch) {
    const slot = themeMatch[1].toLowerCase();
    return res.kotlinColors.get(`theme:${slot}`) ?? MATERIAL3_DEFAULTS[slot];
  }

  const namedDot = trimmed.match(/^Color\.([A-Za-z_]+)$/);
  if (namedDot) {
    return NAMED_COLORS[namedDot[1].toLowerCase()];
  }

  if (res.kotlinColors.has(trimmed)) {
    return res.kotlinColors.get(trimmed);
  }
  const alphaCopy = trimmed.match(/^([A-Za-z_][A-Za-z0-9_.]*)\.copy\(/);
  if (alphaCopy) {
    return resolveColorExpr(alphaCopy[1], res);
  }
  return undefined;
}

function resolveTextExpr(raw: string | undefined, res: ResourceMaps): string | undefined {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (!trimmed) return undefined;

  if (trimmed.startsWith('@string/')) {
    return res.strings.get(trimmed.slice(8)) ?? trimmed.slice(8).replace(/_/g, ' ');
  }
  const strRes = trimmed.match(/stringResource\(\s*R\.string\.([A-Za-z0-9_]+)\s*\)/);
  if (strRes) {
    return res.strings.get(strRes[1]) ?? strRes[1].replace(/_/g, ' ');
  }
  const quoted = trimmed.match(/^"((?:[^"\\]|\\.)*)"$/);
  if (quoted) {
    return quoted[1].replace(/\\n/g, '\n').replace(/\\"/g, '"');
  }
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(trimmed) && res.kotlinStrings.has(trimmed)) {
    return res.kotlinStrings.get(trimmed);
  }
  const embeddedQuoted = trimmed.match(/"((?:[^"\\]|\\.)*)"/);
  if (embeddedQuoted) {
    return embeddedQuoted[1].replace(/\\n/g, '\n').replace(/\\"/g, '"');
  }
  return undefined;
}

/* -------------------------------------------------------------------------- */
/* 1. JETPACK COMPOSE AST TREE PARSER (Non-XML UI)                            */
/* -------------------------------------------------------------------------- */

interface ComposeNode {
  widget: string;
  args: string;
  body: string;
  children: ComposeNode[];
}

const COMPOSE_WIDGETS = new Set([
  'Scaffold',
  'Surface',
  'Box',
  'Column',
  'Row',
  'LazyColumn',
  'LazyRow',
  'Card',
  'ElevatedCard',
  'OutlinedCard',
  'TopAppBar',
  'CenterAlignedTopAppBar',
  'SmallTopAppBar',
  'MediumTopAppBar',
  'LargeTopAppBar',
  'NavigationBar',
  'BottomAppBar',
  'Text',
  'Button',
  'ElevatedButton',
  'FilledTonalButton',
  'OutlinedButton',
  'TextButton',
  'OutlinedTextField',
  'TextField',
  'BasicTextField',
  'Switch',
  'Checkbox',
  'Slider',
  'LinearProgressIndicator',
  'CircularProgressIndicator',
  'FloatingActionButton',
  'ExtendedFloatingActionButton',
  'Image',
  'AsyncImage',
  'HorizontalDivider',
  'Divider',
  'Spacer',
  'Icon',
]);

function skipStringOrComment(src: string, idx: number): number {
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

function readBalanced(src: string, openIdx: number, openCh: string, closeCh: string): { text: string; nextIdx: number } {
  let depth = 0;
  let i = openIdx;
  while (i < src.length) {
    const skipped = skipStringOrComment(src, i);
    if (skipped !== i) {
      i = skipped;
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

function splitTopLevelArgs(argsStr: string): string[] {
  const parts: string[] = [];
  let start = 0;
  let paren = 0;
  let brace = 0;
  let bracket = 0;
  let i = 0;
  while (i < argsStr.length) {
    const skipped = skipStringOrComment(argsStr, i);
    if (skipped !== i) {
      i = skipped;
      continue;
    }
    const ch = argsStr[i];
    if (ch === '(') paren += 1;
    else if (ch === ')') paren -= 1;
    else if (ch === '{') brace += 1;
    else if (ch === '}') brace -= 1;
    else if (ch === '[') bracket += 1;
    else if (ch === ']') bracket -= 1;
    else if (ch === ',' && paren === 0 && brace === 0 && bracket === 0) {
      parts.push(argsStr.slice(start, i).trim());
      start = i + 1;
    }
    i += 1;
  }
  const tail = argsStr.slice(start).trim();
  if (tail) parts.push(tail);
  return parts;
}

function getNamedArg(argsStr: string, name: string): string | undefined {
  const parts = splitTopLevelArgs(argsStr);
  const re = new RegExp(`^${name}\\s*=\\s*([\\s\\S]+)$`);
  for (const part of parts) {
    const m = part.match(re);
    if (m) return m[1].trim();
  }
  return undefined;
}

function getPositionalArg(argsStr: string, index: number): string | undefined {
  const parts = splitTopLevelArgs(argsStr);
  const item = parts[index];
  if (!item) return undefined;
  if (/^[A-Za-z_][A-Za-z0-9_]*\s*=/.test(item)) return undefined;
  return item;
}

function parseComposeBlock(block: string): ComposeNode[] {
  const nodes: ComposeNode[] = [];
  let i = 0;

  while (i < block.length) {
    const skipped = skipStringOrComment(block, i);
    if (skipped !== i) {
      i = skipped;
      continue;
    }
    if (/[A-Za-z_]/.test(block[i])) {
      const start = i;
      while (i < block.length && /[A-Za-z0-9_]/.test(block[i])) i += 1;
      const ident = block.slice(start, i);

      // Skip method calls like `.padding(...)` or `Color(...)`
      const prevChar = block[start - 1];
      if (prevChar === '.' || !COMPOSE_WIDGETS.has(ident)) {
        continue;
      }

      let cursor = i;
      while (cursor < block.length && /\s/.test(block[cursor])) cursor += 1;

      let args = '';
      if (block[cursor] === '(') {
        const res = readBalanced(block, cursor, '(', ')');
        args = res.text;
        cursor = res.nextIdx;
        while (cursor < block.length && /\s/.test(block[cursor])) cursor += 1;
      }

      let body = '';
      if (block[cursor] === '{') {
        const res = readBalanced(block, cursor, '{', '}');
        body = res.text;
        cursor = res.nextIdx;
      }

      const isContainer = [
        'Scaffold',
        'Surface',
        'Box',
        'Column',
        'Row',
        'LazyColumn',
        'LazyRow',
        'Card',
        'ElevatedCard',
        'OutlinedCard',
      ].includes(ident);

      const children = isContainer && body ? parseComposeBlock(body) : [];
      nodes.push({ widget: ident, args, body, children });
      i = cursor;
      continue;
    }
    i += 1;
  }

  return nodes;
}

interface ParsedModifier {
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  fillMaxWidth?: number; // fraction 0..1
  fillMaxSize?: boolean;
  padTop: number;
  padRight: number;
  padBottom: number;
  padLeft: number;
  backgroundColor?: string;
  borderRadius?: number;
  strokeColor?: string;
  strokeWidth?: number;
  alpha?: number;
  rotation?: number;
  weight?: number;
  align?: string;
}

function parseComposeModifier(argsStr: string, res: ResourceMaps): ParsedModifier {
  const modStr = getNamedArg(argsStr, 'modifier') ?? '';
  const mod: ParsedModifier = { padTop: 0, padRight: 0, padBottom: 0, padLeft: 0 };
  if (!modStr) return mod;

  const offsetMatch = modStr.match(/\.offset\s*\(([^)]*)\)/);
  if (offsetMatch) {
    const oArgs = offsetMatch[1];
    const xNamed = oArgs.match(/x\s*=\s*(-?\d+(?:\.\d+)?)\.dp/);
    const yNamed = oArgs.match(/y\s*=\s*(-?\d+(?:\.\d+)?)\.dp/);
    if (xNamed) mod.x = Math.round(parseFloat(xNamed[1]));
    if (yNamed) mod.y = Math.round(parseFloat(yNamed[1]));
    if (!xNamed && !yNamed) {
      const nums = [...oArgs.matchAll(/(-?\d+(?:\.\d+)?)\.dp/g)].map((m) => parseFloat(m[1]));
      if (nums[0] !== undefined) mod.x = Math.round(nums[0]);
      if (nums[1] !== undefined) mod.y = Math.round(nums[1]);
    }
  }

  const sizeMatch = modStr.match(/\.size\s*\(([^)]*)\)/);
  if (sizeMatch) {
    const sArgs = sizeMatch[1];
    const wNamed = sArgs.match(/width\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    const hNamed = sArgs.match(/height\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    if (wNamed) mod.width = Math.round(parseFloat(wNamed[1]));
    if (hNamed) mod.height = Math.round(parseFloat(hNamed[1]));
    if (!wNamed && !hNamed) {
      const nums = [...sArgs.matchAll(/(\d+(?:\.\d+)?)\.dp/g)].map((m) => parseFloat(m[1]));
      if (nums.length === 1) {
        mod.width = Math.round(nums[0]);
        mod.height = Math.round(nums[0]);
      } else if (nums.length >= 2) {
        mod.width = Math.round(nums[0]);
        mod.height = Math.round(nums[1]);
      }
    }
  }

  const widthMatch = modStr.match(/\.width\s*\(\s*(\d+(?:\.\d+)?)\.dp\s*\)/);
  if (widthMatch) mod.width = Math.round(parseFloat(widthMatch[1]));

  const heightMatch = modStr.match(/\.height\s*\(\s*(\d+(?:\.\d+)?)\.dp\s*\)/);
  if (heightMatch) mod.height = Math.round(parseFloat(heightMatch[1]));

  const fillWMatch = modStr.match(/\.fillMaxWidth\s*\(\s*(\d+(?:\.\d+)?)?f?\s*\)/);
  if (fillWMatch) {
    mod.fillMaxWidth = fillWMatch[1] ? Math.min(1, Math.max(0.1, parseFloat(fillWMatch[1]))) : 1;
  }
  if (/\.fillMaxSize\s*\(/.test(modStr)) {
    mod.fillMaxSize = true;
    mod.fillMaxWidth = 1;
  }

  const padRegex = /\.padding\s*\(([^)]*)\)/g;
  let pm: RegExpExecArray | null;
  while ((pm = padRegex.exec(modStr)) !== null) {
    const pArgs = pm[1];
    const allNamed = pArgs.match(/all\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    const horizNamed = pArgs.match(/horizontal\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    const vertNamed = pArgs.match(/vertical\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    const startNamed = pArgs.match(/(?:start|left)\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    const endNamed = pArgs.match(/(?:end|right)\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    const topNamed = pArgs.match(/top\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    const botNamed = pArgs.match(/bottom\s*=\s*(\d+(?:\.\d+)?)\.dp/);
    if (allNamed || horizNamed || vertNamed || startNamed || endNamed || topNamed || botNamed) {
      if (allNamed) {
        const v = Math.round(parseFloat(allNamed[1]));
        mod.padTop += v;
        mod.padRight += v;
        mod.padBottom += v;
        mod.padLeft += v;
      }
      if (horizNamed) {
        const v = Math.round(parseFloat(horizNamed[1]));
        mod.padLeft += v;
        mod.padRight += v;
      }
      if (vertNamed) {
        const v = Math.round(parseFloat(vertNamed[1]));
        mod.padTop += v;
        mod.padBottom += v;
      }
      if (startNamed) mod.padLeft += Math.round(parseFloat(startNamed[1]));
      if (endNamed) mod.padRight += Math.round(parseFloat(endNamed[1]));
      if (topNamed) mod.padTop += Math.round(parseFloat(topNamed[1]));
      if (botNamed) mod.padBottom += Math.round(parseFloat(botNamed[1]));
    } else {
      const nums = [...pArgs.matchAll(/(\d+(?:\.\d+)?)\.dp/g)].map((n) => Math.round(parseFloat(n[1])));
      if (nums.length === 1) {
        mod.padTop += nums[0];
        mod.padRight += nums[0];
        mod.padBottom += nums[0];
        mod.padLeft += nums[0];
      } else if (nums.length === 2) {
        mod.padLeft += nums[0];
        mod.padRight += nums[0];
        mod.padTop += nums[1];
        mod.padBottom += nums[1];
      } else if (nums.length >= 4) {
        mod.padLeft += nums[0];
        mod.padTop += nums[1];
        mod.padRight += nums[2];
        mod.padBottom += nums[3];
      }
    }
  }

  const bgIdx = modStr.indexOf('.background(');
  if (bgIdx !== -1) {
    const bgBal = readBalanced(modStr, bgIdx + '.background'.length, '(', ')');
    const bgParts = splitTopLevelArgs(bgBal.text);
    const colorPart = getNamedArg(bgBal.text, 'color') ?? bgParts[0];
    mod.backgroundColor = resolveColorExpr(colorPart, res);
    const shapePart = getNamedArg(bgBal.text, 'shape') ?? bgParts[1];
    if (shapePart) {
      const rad = shapePart.match(/RoundedCornerShape\(\s*(\d+(?:\.\d+)?)\.dp/);
      if (rad) mod.borderRadius = Math.round(parseFloat(rad[1]));
      else if (shapePart.includes('CircleShape')) mod.borderRadius = 999;
    }
  }

  const clipMatch = modStr.match(/\.clip\s*\(\s*(RoundedCornerShape\(\s*(\d+(?:\.\d+)?)\.dp\s*\)|CircleShape)\s*\)/);
  if (clipMatch) {
    mod.borderRadius = clipMatch[1].includes('CircleShape') ? 999 : Math.round(parseFloat(clipMatch[2]));
  }

  const borderIdx = modStr.indexOf('.border(');
  if (borderIdx !== -1) {
    const bBal = readBalanced(modStr, borderIdx + '.border'.length, '(', ')');
    const bParts = splitTopLevelArgs(bBal.text);
    const wVal = parseDpNumber(getNamedArg(bBal.text, 'width') ?? bParts[0]);
    const cVal = resolveColorExpr(getNamedArg(bBal.text, 'color') ?? bParts[1], res);
    if (wVal !== undefined) mod.strokeWidth = Math.max(1, Math.round(wVal));
    if (cVal) mod.strokeColor = cVal;
  }

  const alphaMatch = modStr.match(/\.alpha\s*\(\s*(\d+(?:\.\d+)?)f?\s*\)/);
  if (alphaMatch) mod.alpha = Math.round(Math.min(1, Math.max(0.1, parseFloat(alphaMatch[1]))) * 100);

  const rotMatch = modStr.match(/\.rotate\s*\(\s*(-?\d+(?:\.\d+)?)f?\s*\)/);
  if (rotMatch) mod.rotation = Math.round(parseFloat(rotMatch[1]));

  const weightMatch = modStr.match(/\.weight\s*\(\s*(\d+(?:\.\d+)?)f?\s*\)/);
  if (weightMatch) mod.weight = parseFloat(weightMatch[1]);

  const alignMatch = modStr.match(/\.align\s*\(\s*Alignment\.([A-Za-z]+)\s*\)/);
  if (alignMatch) mod.align = alignMatch[1];

  return mod;
}

function parseComposeShapeRadius(argsStr: string): number | undefined {
  const shape = getNamedArg(argsStr, 'shape');
  if (!shape) return undefined;
  if (shape.includes('CircleShape')) return 999;
  const r = shape.match(/RoundedCornerShape\(\s*(\d+(?:\.\d+)?)\.dp/);
  return r ? Math.round(parseFloat(r[1])) : undefined;
}

function parseComposeTextAlign(argsStr: string): DesignerElement['textAlign'] | undefined {
  const raw = getNamedArg(argsStr, 'textAlign');
  if (!raw) return undefined;
  if (/Center/.test(raw)) return 'center';
  if (/End|Right/.test(raw)) return 'right';
  if (/Start|Left/.test(raw)) return 'left';
  return undefined;
}

function parseComposeFontWeight(argsStr: string): DesignerElement['fontWeight'] | undefined {
  const raw = getNamedArg(argsStr, 'fontWeight') ?? argsStr;
  if (/FontWeight\.(Bold|ExtraBold|Black)/.test(raw)) return '700';
  if (/FontWeight\.SemiBold/.test(raw)) return '600';
  if (/FontWeight\.Medium/.test(raw)) return '500';
  if (/FontWeight\.(Normal|Light)/.test(raw)) return '400';
  return undefined;
}

function extractInlineTextFromBody(body: string, res: ResourceMaps): {
  text?: string;
  textColor?: string;
  fontSize?: number;
  fontWeight?: DesignerElement['fontWeight'];
  textAlign?: DesignerElement['textAlign'];
} {
  const innerNodes = parseComposeBlock(body);
  const textNode = innerNodes.find((n) => n.widget === 'Text');
  if (!textNode) {
    const quoted = resolveTextExpr(body, res);
    return { text: quoted };
  }
  const rawText = getNamedArg(textNode.args, 'text') ?? getPositionalArg(textNode.args, 0);
  const text = resolveTextExpr(rawText, res);
  const textColor = resolveColorExpr(getNamedArg(textNode.args, 'color'), res);
  const fontSize = parseDpNumber(getNamedArg(textNode.args, 'fontSize'));
  const fontWeight = parseComposeFontWeight(textNode.args);
  const textAlign = parseComposeTextAlign(textNode.args);
  return { text, textColor, fontSize, fontWeight, textAlign };
}

function pickPrimaryComposableBody(source: string): { fnName: string; body: string } | null {
  const re = /@Composable[\s\S]{0,120}?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*\{/g;
  const candidates: Array<{ fnName: string; body: string; score: number }> = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(source)) !== null) {
    const openBraceIdx = m.index + m[0].length - 1;
    const { text: body } = readBalanced(source, openBraceIdx, '{', '}');
    const widgetHits = (
      body.match(/\b(Scaffold|Column|Row|Box|Surface|Card|TopAppBar|Text|Button|OutlinedTextField|Switch|Image)\s*\(/g) ??
      []
    ).length;
    const isTheme = /Theme$/.test(m[1]);
    candidates.push({
      fnName: m[1],
      body,
      score: isTheme ? widgetHits - 20 : widgetHits,
    });
  }
  if (candidates.length === 0) {
    // Try setContent { ... }
    const scIdx = source.indexOf('setContent');
    if (scIdx !== -1) {
      const brace = source.indexOf('{', scIdx);
      if (brace !== -1) {
        const { text: body } = readBalanced(source, brace, '{', '}');
        return { fnName: 'MainScreen', body };
      }
    }
    return null;
  }
  candidates.sort((a, b) => b.score - a.score);
  return { fnName: candidates[0].fnName, body: candidates[0].body };
}

function importComposeToScreen(
  ctx: CodeImportContext,
  res: ResourceMaps
): CodeImportResult {
  const screenWidth = ctx.screenWidth ?? DEFAULT_WIDTH;
  const baseHeight = ctx.screenHeight ?? DEFAULT_HEIGHT;
  const picked = pickPrimaryComposableBody(ctx.content);
  const fallbackName = ctx.filePath.split('/').pop()?.replace(/\.kt$/i, '') ?? 'Compose Screen';

  if (!picked) {
    return {
      screen: {
        id: nextImportedId('screen'),
        name: fallbackName,
        width: screenWidth,
        height: baseHeight,
        backgroundColor: res.kotlinColors.get('theme:background') ?? '#10171F',
        elements: [],
        sourceFilePath: ctx.filePath,
      },
      sourceKind: 'empty',
      sourcePath: ctx.filePath,
      importedCount: 0,
      notes: ['No @Composable UI tree found in this Kotlin file.'],
    };
  }

  let screenBg = res.kotlinColors.get('theme:background') ?? '#10171F';
  const elements: DesignerElement[] = [];

  const defaultTextColor = () => (isLightHex(screenBg) ? '#142029' : '#EEF4F6');
  const defaultMutedColor = () => (isLightHex(screenBg) ? '#4B5E6B' : '#A6BAC6');
  const defaultSurfaceColor = () =>
    res.kotlinColors.get('theme:surface') ?? (isLightHex(screenBg) ? '#FFFFFF' : '#1B2834');
  const defaultAccentColor = () => res.kotlinColors.get('theme:primary') ?? '#3E8790';

  const makeEl = (
    kind: DesignerElementKind,
    overrides: Partial<DesignerElement>
  ): DesignerElement => {
    const base = createDesignerElement(kind, screenWidth, baseHeight, elements.length);
    return {
      ...base,
      id: nextImportedId(kind),
      ...overrides,
    };
  };

  let maxBottomY = baseHeight;

  const layoutChildren = (
    nodes: ComposeNode[],
    containerX: number,
    containerY: number,
    containerW: number,
    mode: 'column' | 'row' | 'box',
    gap: number,
    horizontalAlign: 'start' | 'center' | 'end' = 'start'
  ): number => {
    let cursorY = containerY;
    let cursorX = containerX;
    let rowMaxH = 0;

    if (mode === 'row' && nodes.length > 0) {
      // Pre-calculate weights / automatic split for Row children
      const visibleRowNodes = nodes.filter((n) => n.widget !== 'Spacer');
      const totalGap = Math.max(0, nodes.length - 1) * gap;
      const totalWeight = visibleRowNodes.reduce(
        (acc, n) => acc + (parseComposeModifier(n.args, res).weight ?? 0),
        0
      );
      const defaultColW = Math.max(
        48,
        Math.floor((containerW - totalGap) / Math.max(1, visibleRowNodes.length))
      );

      for (const node of nodes) {
        const mod = parseComposeModifier(node.args, res);
        if (node.widget === 'Spacer') {
          cursorX += mod.width ?? gap;
          continue;
        }
        const slotW =
          mod.width ??
          (mod.weight && totalWeight > 0
            ? Math.max(48, Math.round(((containerW - totalGap) * mod.weight) / totalWeight))
            : defaultColW);
        const placedH = layoutSingleNode(node, cursorX, containerY, slotW, 'column', 'start');
        rowMaxH = Math.max(rowMaxH, placedH);
        cursorX += slotW + gap;
      }
      return containerY + rowMaxH;
    }

    for (let idx = 0; idx < nodes.length; idx++) {
      const node = nodes[idx];
      const mod = parseComposeModifier(node.args, res);

      if (node.widget === 'Spacer') {
        cursorY += mod.height ?? 12;
        continue;
      }

      const targetX = mode === 'box' && mod.x !== undefined ? mod.x : containerX + mod.padLeft;
      const targetY = mode === 'box' && mod.y !== undefined ? mod.y : cursorY + mod.padTop;
      const availW = Math.max(40, containerW - mod.padLeft - mod.padRight);

      const usedH = layoutSingleNode(
        node,
        targetX,
        targetY,
        availW,
        mode,
        horizontalAlign
      );

      if (mode === 'column') {
        cursorY = targetY + usedH + mod.padBottom + (idx < nodes.length - 1 ? gap : 0);
      } else {
        maxBottomY = Math.max(maxBottomY, targetY + usedH);
      }
    }

    maxBottomY = Math.max(maxBottomY, cursorY);
    return mode === 'column' ? cursorY : Math.max(cursorY, maxBottomY);
  };

  const layoutSingleNode = (
    node: ComposeNode,
    x: number,
    y: number,
    availW: number,
    parentMode: 'column' | 'row' | 'box',
    parentHAlign: 'start' | 'center' | 'end'
  ): number => {
    const mod = parseComposeModifier(node.args, res);
    const isAbsolute = parentMode === 'box' && (mod.x !== undefined || mod.y !== undefined);

    const resolveBoxX = (w: number): number => {
      if (isAbsolute) return mod.x ?? x;
      if (mod.align?.includes('CenterHorizontally') || mod.align === 'Center' || parentHAlign === 'center') {
        return Math.round(x + Math.max(0, (availW - w) / 2));
      }
      if (mod.align?.includes('End') || parentHAlign === 'end') {
        return Math.round(x + Math.max(0, availW - w));
      }
      return Math.round(x);
    };

    // Container widgets
    if (node.widget === 'Scaffold') {
      const containerColor = resolveColorExpr(getNamedArg(node.args, 'containerColor'), res);
      if (containerColor && containerColor !== 'transparent') screenBg = containerColor;

      let contentTop = STATUS_BAR_OFFSET;
      const topBarArg = getNamedArg(node.args, 'topBar');
      if (topBarArg) {
        const tbNodes = parseComposeBlock(topBarArg);
        if (tbNodes.length > 0) {
          layoutSingleNode(tbNodes[0], 0, STATUS_BAR_OFFSET, screenWidth, 'box', 'start');
          contentTop = STATUS_BAR_OFFSET + 56 + 12;
        }
      }

      const bodyEnd = layoutChildren(node.children, 16, contentTop, screenWidth - 32, 'column', 12);

      const bottomBarArg = getNamedArg(node.args, 'bottomBar');
      if (bottomBarArg) {
        const bbNodes = parseComposeBlock(bottomBarArg);
        if (bbNodes.length > 0) {
          layoutSingleNode(bbNodes[0], 0, Math.max(baseHeight, bodyEnd + 24) - 64, screenWidth, 'box', 'start');
        }
      }

      const fabArg = getNamedArg(node.args, 'floatingActionButton');
      if (fabArg) {
        const fabNodes = parseComposeBlock(fabArg);
        if (fabNodes.length > 0) {
          layoutSingleNode(
            fabNodes[0],
            screenWidth - 76,
            Math.max(baseHeight, bodyEnd + 24) - (bottomBarArg ? 136 : 80),
            56,
            'box',
            'end'
          );
        }
      }
      return Math.max(baseHeight, bodyEnd) - y;
    }

    if (node.widget === 'Surface' || node.widget === 'Card' || node.widget === 'ElevatedCard' || node.widget === 'OutlinedCard') {
      const explicitColor =
        resolveColorExpr(getNamedArg(node.args, 'color'), res) ??
        resolveColorExpr(
          node.args.match(/containerColor\s*=\s*([^,)\n]+)/)?.[1],
          res
        ) ??
        mod.backgroundColor;

      // Full-screen root Surface -> sets screen background & lays out children directly
      if (node.widget === 'Surface' && mod.fillMaxSize && elements.length === 0) {
        if (explicitColor && explicitColor !== 'transparent') screenBg = explicitColor;
        const innerX = mod.padLeft;
        const innerY = Math.max(STATUS_BAR_OFFSET, mod.padTop);
        const innerW = screenWidth - mod.padLeft - mod.padRight;
        const endY = layoutChildren(node.children, innerX, innerY, innerW, 'column', 12);
        return Math.max(0, endY - y);
      }

      const cardW = mod.width ?? (mod.fillMaxWidth ? Math.round(availW * mod.fillMaxWidth) : availW);
      const cardX = resolveBoxX(cardW);
      const cardY = isAbsolute ? mod.y ?? y : y;
      const radius = parseComposeShapeRadius(node.args) ?? mod.borderRadius ?? (node.widget === 'Surface' ? 12 : 16);
      const inlineText = node.children.length === 0 && node.body ? extractInlineTextFromBody(node.body, res) : {};

      const cardEl = makeEl('card', {
        name: node.widget,
        x: cardX,
        y: cardY,
        width: cardW,
        height: mod.height ?? 120,
        fillColor:
          explicitColor ?? (node.widget === 'OutlinedCard' ? 'transparent' : defaultSurfaceColor()),
        strokeColor: mod.strokeColor ?? (node.widget === 'OutlinedCard' ? defaultAccentColor() : '#2E4252'),
        strokeWidth: mod.strokeWidth ?? (node.widget === 'OutlinedCard' ? 1.5 : 1),
        borderRadius: radius,
        shadow: node.widget === 'ElevatedCard' ? 10 : node.widget === 'OutlinedCard' ? 0 : 4,
        text: inlineText.text ?? '',
        textColor: inlineText.textColor ?? defaultTextColor(),
        textAlign: inlineText.textAlign ?? 'left',
        opacity: mod.alpha ?? 100,
        rotation: mod.rotation ?? 0,
      });
      elements.push(cardEl);

      if (node.children.length > 0) {
        const innerPad = Math.max(14, mod.padLeft || 14);
        const endY = layoutChildren(
          node.children,
          cardX + innerPad,
          cardY + innerPad,
          Math.max(40, cardW - innerPad * 2),
          'column',
          10
        );
        if (!mod.height) {
          cardEl.height = Math.max(64, Math.round(endY - cardY + innerPad));
        }
      }
      return cardEl.height;
    }

    if (node.widget === 'Box') {
      if (mod.fillMaxSize && elements.length === 0) {
        if (mod.backgroundColor && mod.backgroundColor !== 'transparent') {
          screenBg = mod.backgroundColor;
        }
        const padX = mod.padLeft;
        const padTop = mod.padTop;
        const endY = layoutChildren(
          node.children,
          padX,
          padTop,
          Math.max(40, screenWidth - mod.padLeft - mod.padRight),
          'box',
          10
        );
        return Math.max(0, endY - y);
      }

      const boxW = mod.width ?? (mod.fillMaxWidth ? Math.round(availW * mod.fillMaxWidth) : availW);
      const boxX = resolveBoxX(boxW);
      const boxY = isAbsolute ? mod.y ?? y : y;
      const boxH = mod.height ?? 100;

      if (mod.backgroundColor || mod.strokeWidth) {
        elements.push(
          makeEl('rectangle', {
            name: 'Box',
            x: boxX,
            y: boxY,
            width: boxW,
            height: boxH,
            fillColor: mod.backgroundColor ?? 'transparent',
            strokeColor: mod.strokeColor ?? '#324858',
            strokeWidth: mod.strokeWidth ?? 0,
            borderRadius: mod.borderRadius ?? 8,
            opacity: mod.alpha ?? 100,
            rotation: mod.rotation ?? 0,
          })
        );
      }

      const endY = layoutChildren(
        node.children,
        boxX + mod.padLeft,
        boxY + mod.padTop,
        Math.max(40, boxW - mod.padLeft - mod.padRight),
        'box',
        10
      );
      return mod.height ?? Math.max(48, endY - boxY);
    }

    if (node.widget === 'Column' || node.widget === 'LazyColumn') {
      if (mod.fillMaxSize && elements.length === 0 && mod.backgroundColor && mod.backgroundColor !== 'transparent') {
        screenBg = mod.backgroundColor;
      }
      const gapMatch = node.args.match(/spacedBy\(\s*(\d+(?:\.\d+)?)\.dp\s*\)/);
      const gap = gapMatch ? Math.round(parseFloat(gapMatch[1])) : 12;
      const hAlign: 'start' | 'center' | 'end' = /CenterHorizontally/.test(node.args)
        ? 'center'
        : /Alignment\.End/.test(node.args)
        ? 'end'
        : 'start';

      const colW = mod.width ?? (mod.fillMaxWidth ? Math.round(availW * mod.fillMaxWidth) : availW);
      const colX = resolveBoxX(colW);
      const startY = Math.max(elements.length === 0 ? STATUS_BAR_OFFSET : 0, y);

      if (mod.backgroundColor && !mod.fillMaxSize) {
        const bgCard = makeEl('card', {
          name: 'Column Section',
          x: colX,
          y: startY,
          width: colW,
          height: mod.height ?? 100,
          fillColor: mod.backgroundColor,
          borderRadius: mod.borderRadius ?? 12,
          strokeWidth: mod.strokeWidth ?? 0,
          strokeColor: mod.strokeColor ?? '#324858',
        });
        elements.push(bgCard);
        const endY = layoutChildren(
          node.children,
          colX + Math.max(12, mod.padLeft),
          startY + Math.max(12, mod.padTop),
          Math.max(40, colW - Math.max(12, mod.padLeft) - Math.max(12, mod.padRight)),
          'column',
          gap,
          hAlign
        );
        if (!mod.height) bgCard.height = Math.max(48, Math.round(endY - startY + Math.max(12, mod.padBottom)));
        return bgCard.height;
      }

      const endY = layoutChildren(
        node.children,
        colX,
        startY,
        colW,
        'column',
        gap,
        hAlign
      );
      return Math.max(0, endY - y);
    }

    if (node.widget === 'Row' || node.widget === 'LazyRow') {
      // Special case: Row containing Text + Switch (very common in Android settings & Designer exports!)
      const hasSwitch = node.children.some((c) => c.widget === 'Switch' || c.widget === 'Checkbox');
      const labelChild = node.children.find((c) => c.widget === 'Text');
      if (hasSwitch && labelChild && node.children.length <= 2) {
        const swChild = node.children.find((c) => c.widget === 'Switch' || c.widget === 'Checkbox')!;
        const swText =
          resolveTextExpr(getNamedArg(labelChild.args, 'text') ?? getPositionalArg(labelChild.args, 0), res) ??
          'Toggle option';
        const swColor = resolveColorExpr(getNamedArg(labelChild.args, 'color'), res) ?? defaultTextColor();
        const checked = /checked\s*=\s*true/.test(swChild.args);
        const swW = mod.width ?? availW;
        const swH = mod.height ?? 46;
        elements.push(
          makeEl('switch', {
            name: swText.slice(0, 24),
            x: resolveBoxX(swW),
            y: isAbsolute ? mod.y ?? y : y,
            width: swW,
            height: swH,
            text: swText,
            textColor: swColor,
            fillColor: mod.backgroundColor ?? defaultSurfaceColor(),
            textAlign: parseComposeTextAlign(labelChild.args) ?? 'left',
            checked,
          })
        );
        return swH;
      }

      const gapMatch = node.args.match(/spacedBy\(\s*(\d+(?:\.\d+)?)\.dp\s*\)/);
      const gap = gapMatch ? Math.round(parseFloat(gapMatch[1])) : 10;
      const rowW = mod.width ?? availW;
      const rowX = resolveBoxX(rowW);
      const endY = layoutChildren(node.children, rowX, isAbsolute ? mod.y ?? y : y, rowW, 'row', gap);
      return Math.max(36, endY - (isAbsolute ? mod.y ?? y : y));
    }

    // Leaf Widgets
    if (
      node.widget === 'TopAppBar' ||
      node.widget === 'CenterAlignedTopAppBar' ||
      node.widget === 'SmallTopAppBar' ||
      node.widget === 'MediumTopAppBar' ||
      node.widget === 'LargeTopAppBar'
    ) {
      const titleArg = getNamedArg(node.args, 'title') ?? '';
      const titleInfo = extractInlineTextFromBody(titleArg, res);
      const barBg =
        resolveColorExpr(node.args.match(/containerColor\s*=\s*([^,)\n]+)/)?.[1], res) ??
        mod.backgroundColor ??
        defaultSurfaceColor();
      const titleColor =
        resolveColorExpr(node.args.match(/titleContentColor\s*=\s*([^,)\n]+)/)?.[1], res) ??
        titleInfo.textColor ??
        defaultTextColor();
      const h = mod.height ?? 56;
      const w = mod.width ?? screenWidth;
      const tx = isAbsolute ? mod.x ?? 0 : 0;
      const ty = isAbsolute ? mod.y ?? STATUS_BAR_OFFSET : Math.max(STATUS_BAR_OFFSET, y);
      elements.push(
        makeEl('topBar', {
          name: 'Top App Bar',
          x: tx,
          y: ty,
          width: w,
          height: h,
          text: titleInfo.text ?? picked.fnName,
          fillColor: barBg,
          textColor: titleColor,
          textAlign:
            titleInfo.textAlign ??
            (node.widget === 'CenterAlignedTopAppBar' ? 'center' : 'left'),
          fontSize: titleInfo.fontSize ?? 18,
          fontWeight: titleInfo.fontWeight ?? '700',
        })
      );
      return h;
    }

    if (node.widget === 'NavigationBar' || node.widget === 'BottomAppBar') {
      const labels = [...node.body.matchAll(/Text\(\s*(?:text\s*=\s*)?"([^"]+)"/g)]
        .map((m) => m[1])
        .slice(0, 4);
      const barBg =
        resolveColorExpr(getNamedArg(node.args, 'containerColor'), res) ??
        mod.backgroundColor ??
        defaultSurfaceColor();
      const h = mod.height ?? 64;
      elements.push(
        makeEl('bottomNav', {
          name: 'Bottom Nav Bar',
          x: 0,
          y: isAbsolute ? mod.y ?? baseHeight - h : y,
          width: mod.width ?? screenWidth,
          height: h,
          fillColor: barBg,
          text: labels.length > 0 ? labels.join(' | ') : 'Home | Search | Profile',
          textColor: defaultMutedColor(),
          textAlign: 'center',
        })
      );
      return h;
    }

    if (node.widget === 'Text') {
      const rawText = getNamedArg(node.args, 'text') ?? getPositionalArg(node.args, 0);
      const text = resolveTextExpr(rawText, res) ?? 'Text';
      const fontSize =
        parseDpNumber(getNamedArg(node.args, 'fontSize')) ??
        (/headlineLarge|display/.test(node.args)
          ? 26
          : /headline|titleLarge/.test(node.args)
          ? 22
          : /titleMedium|titleSmall/.test(node.args)
          ? 18
          : /labelSmall|bodySmall/.test(node.args)
          ? 12
          : 15);
      const fontWeight =
        parseComposeFontWeight(node.args) ??
        (fontSize >= 18 || /headline|title/.test(node.args) ? '700' : '400');
      const isHeading = fontSize >= 18 || fontWeight === '700';
      const textColor =
        resolveColorExpr(getNamedArg(node.args, 'color'), res) ??
        (isHeading ? defaultTextColor() : defaultMutedColor());
      const textAlign =
        parseComposeTextAlign(node.args) ??
        (parentHAlign === 'center' ? 'center' : parentHAlign === 'end' ? 'right' : 'left');

      const w = mod.width ?? (mod.fillMaxWidth ? Math.round(availW * mod.fillMaxWidth) : availW);
      const charsPerLine = Math.max(12, Math.floor(w / Math.max(6, fontSize * 0.56)));
      const lines = Math.max(1, Math.ceil(text.length / charsPerLine));
      const estimatedH = Math.max(Math.round(fontSize * 1.45 * lines + 6), 24);
      const h = mod.height ?? estimatedH;

      elements.push(
        makeEl(isHeading ? 'heading' : 'text', {
          name: isHeading ? `Heading: ${text.slice(0, 16)}` : `Text: ${text.slice(0, 16)}`,
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          text,
          fontSize: Math.round(fontSize),
          fontWeight,
          textColor,
          textAlign,
          fillColor: mod.backgroundColor ?? 'transparent',
          borderRadius: mod.borderRadius ?? (mod.backgroundColor ? 8 : 0),
          opacity: mod.alpha ?? 100,
          rotation: mod.rotation ?? 0,
        })
      );
      return h;
    }

    if (
      node.widget === 'Button' ||
      node.widget === 'ElevatedButton' ||
      node.widget === 'FilledTonalButton' ||
      node.widget === 'OutlinedButton' ||
      node.widget === 'TextButton'
    ) {
      const isOutlined = node.widget === 'OutlinedButton' || node.widget === 'TextButton';
      const inner = extractInlineTextFromBody(node.body, res);
      const label = inner.text ?? (isOutlined ? 'Secondary' : 'Continue');
      const containerColor =
        resolveColorExpr(node.args.match(/containerColor\s*=\s*([^,)\n]+)/)?.[1], res) ??
        mod.backgroundColor ??
        (isOutlined ? 'transparent' : defaultAccentColor());
      const contentColor =
        resolveColorExpr(node.args.match(/contentColor\s*=\s*([^,)\n]+)/)?.[1], res) ??
        inner.textColor ??
        (isOutlined ? defaultAccentColor() : '#FFFFFF');
      const w = mod.width ?? (mod.fillMaxWidth ? Math.round(availW * mod.fillMaxWidth) : availW);
      const h = mod.height ?? 48;
      elements.push(
        makeEl(isOutlined ? 'outlinedButton' : 'button', {
          name: label.slice(0, 22),
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          text: label,
          fillColor: containerColor,
          textColor: contentColor,
          strokeColor: mod.strokeColor ?? (node.widget === 'OutlinedButton' ? contentColor : 'transparent'),
          strokeWidth: mod.strokeWidth ?? (node.widget === 'OutlinedButton' ? 1.5 : 0),
          borderRadius: parseComposeShapeRadius(node.args) ?? mod.borderRadius ?? 14,
          fontSize: inner.fontSize ? Math.round(inner.fontSize) : 15,
          fontWeight: inner.fontWeight ?? '600',
          textAlign: inner.textAlign ?? 'center',
          shadow: node.widget === 'ElevatedButton' ? 8 : isOutlined ? 0 : 4,
          opacity: mod.alpha ?? 100,
          rotation: mod.rotation ?? 0,
        })
      );
      return h;
    }

    if (node.widget === 'OutlinedTextField' || node.widget === 'TextField' || node.widget === 'BasicTextField') {
      const labelArg = getNamedArg(node.args, 'label') ?? getNamedArg(node.args, 'placeholder') ?? '';
      const labelInfo = extractInlineTextFromBody(labelArg, res);
      const valArg = getNamedArg(node.args, 'value');
      const resolvedVal = resolveTextExpr(valArg, res);
      const w = mod.width ?? availW;
      const h = mod.height ?? 52;
      elements.push(
        makeEl('input', {
          name: (labelInfo.text ?? 'Text Input').slice(0, 22),
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          placeholder: labelInfo.text ?? resolvedVal ?? 'Enter text',
          fillColor: mod.backgroundColor ?? (isLightHex(screenBg) ? '#FFFFFF' : '#111921'),
          strokeColor: mod.strokeColor ?? '#344A5B',
          strokeWidth: mod.strokeWidth ?? 1,
          textColor: defaultTextColor(),
          borderRadius: parseComposeShapeRadius(node.args) ?? mod.borderRadius ?? 12,
          textAlign: 'left',
        })
      );
      return h;
    }

    if (node.widget === 'Switch' || node.widget === 'Checkbox') {
      const checked = /checked\s*=\s*true/.test(node.args);
      const w = mod.width ?? availW;
      const h = mod.height ?? 44;
      elements.push(
        makeEl('switch', {
          name: node.widget,
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          text: node.widget === 'Checkbox' ? 'Accept option' : 'Enable option',
          textColor: defaultTextColor(),
          fillColor: mod.backgroundColor ?? defaultSurfaceColor(),
          checked,
        })
      );
      return h;
    }

    if (node.widget === 'Slider' || node.widget === 'LinearProgressIndicator' || node.widget === 'CircularProgressIndicator') {
      const rawVal =
        node.args.match(/progress\s*=\s*\{?\s*([0-9.]+)f?/)?.[1] ??
        node.args.match(/value\s*=\s*([0-9.]+)f?/)?.[1];
      const num = rawVal ? parseFloat(rawVal) : 0.72;
      const pct = Math.min(100, Math.max(5, Math.round(num <= 1 ? num * 100 : num)));
      const activeColor =
        resolveColorExpr(getNamedArg(node.args, 'color'), res) ?? defaultAccentColor();
      const trackColor =
        resolveColorExpr(getNamedArg(node.args, 'trackColor'), res) ?? defaultSurfaceColor();
      const w = mod.width ?? availW;
      const h = mod.height ?? 14;
      elements.push(
        makeEl('progress', {
          name: node.widget === 'Slider' ? 'Slider' : 'Progress Bar',
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          fillColor: trackColor,
          strokeColor: activeColor,
          progress: pct,
        })
      );
      return h;
    }

    if (node.widget === 'FloatingActionButton' || node.widget === 'ExtendedFloatingActionButton') {
      const inner = extractInlineTextFromBody(node.body, res);
      const bg = resolveColorExpr(getNamedArg(node.args, 'containerColor'), res) ?? defaultAccentColor();
      const fg = resolveColorExpr(getNamedArg(node.args, 'contentColor'), res) ?? '#FFFFFF';
      const w = mod.width ?? (node.widget === 'ExtendedFloatingActionButton' ? 148 : 56);
      const h = mod.height ?? 56;
      elements.push(
        makeEl('fab', {
          name: 'Floating Button',
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          fillColor: bg,
          textColor: fg,
          text: inner.text ?? '+',
          borderRadius: parseComposeShapeRadius(node.args) ?? 18,
          shadow: 10,
        })
      );
      return h;
    }

    if (node.widget === 'Image' || node.widget === 'AsyncImage') {
      const desc =
        resolveTextExpr(getNamedArg(node.args, 'contentDescription'), res) ??
        node.args.match(/R\.(?:drawable|mipmap)\.([A-Za-z0-9_]+)/)?.[1] ??
        'Image';
      const w = mod.width ?? availW;
      const h = mod.height ?? 170;
      elements.push(
        makeEl('image', {
          name: desc.slice(0, 22),
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          text: desc,
          borderRadius: mod.borderRadius ?? 14,
          fillColor: mod.backgroundColor ?? defaultSurfaceColor(),
        })
      );
      return h;
    }

    if (node.widget === 'HorizontalDivider' || node.widget === 'Divider') {
      const color = resolveColorExpr(getNamedArg(node.args, 'color'), res) ?? '#2B3E4C';
      const h = Math.max(1, Math.round(parseDpNumber(getNamedArg(node.args, 'thickness')) ?? mod.height ?? 2));
      const w = mod.width ?? availW;
      elements.push(
        makeEl('divider', {
          name: 'Divider',
          x: resolveBoxX(w),
          y: isAbsolute ? mod.y ?? y : y,
          width: w,
          height: h,
          fillColor: color,
        })
      );
      return h;
    }

    return 0;
  };

  const rootNodes = parseComposeBlock(picked.body);
  layoutChildren(rootNodes, 16, STATUS_BAR_OFFSET + 8, screenWidth - 32, 'column', 12);

  const finalHeight = Math.min(1400, Math.max(baseHeight, Math.round(maxBottomY + 48)));

  return {
    screen: {
      id: nextImportedId('screen'),
      name: picked.fnName,
      width: screenWidth,
      height: finalHeight,
      backgroundColor: screenBg,
      elements,
      sourceFilePath: ctx.filePath,
    },
    sourceKind: 'compose',
    sourcePath: ctx.filePath,
    importedCount: elements.length,
    notes: [
      `Imported ${elements.length} Compose elements from ${ctx.filePath.split('/').pop()} with colors, sizes, alignment, and layout positions.`,
    ],
  };
}

/* -------------------------------------------------------------------------- */
/* 2. ANDROID XML LAYOUT TREE PARSER (res/layout/*.xml)                       */
/* -------------------------------------------------------------------------- */

interface XmlNode {
  tag: string;
  attrs: Record<string, string>;
  children: XmlNode[];
}

function parseXmlTree(xml: string): XmlNode | null {
  const cleaned = xml.replace(/<!--[\s\S]*?-->/g, '').replace(/<\?[\s\S]*?\?>/g, '');
  const tokenRe = /<(\/?)([A-Za-z0-9_.:-]+)([^>]*?)(\/?)>/g;
  const stack: XmlNode[] = [];
  let root: XmlNode | null = null;
  let m: RegExpExecArray | null;

  while ((m = tokenRe.exec(cleaned)) !== null) {
    const isClose = m[1] === '/';
    const rawTag = m[2];
    const attrText = m[3] ?? '';
    const isSelfClose = m[4] === '/';

    if (isClose) {
      if (stack.length > 0) stack.pop();
      continue;
    }

    const attrs: Record<string, string> = {};
    const attrRe = /(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)\s*=\s*"([^"]*)"/g;
    let am: RegExpExecArray | null;
    while ((am = attrRe.exec(attrText)) !== null) {
      attrs[am[1]] = am[2];
    }

    const node: XmlNode = {
      tag: rawTag.split('.').pop() ?? rawTag,
      attrs,
      children: [],
    };

    if (stack.length > 0) {
      stack[stack.length - 1].children.push(node);
    } else if (!root) {
      root = node;
    }

    if (!isSelfClose) {
      stack.push(node);
    }
  }

  return root;
}

function parseXmlPadding(attrs: Record<string, string>) {
  const all = parseDpNumber(attrs.padding) ?? 0;
  const horiz = parseDpNumber(attrs.paddingHorizontal);
  const vert = parseDpNumber(attrs.paddingVertical);
  return {
    left: Math.round(parseDpNumber(attrs.paddingStart ?? attrs.paddingLeft) ?? horiz ?? all),
    right: Math.round(parseDpNumber(attrs.paddingEnd ?? attrs.paddingRight) ?? horiz ?? all),
    top: Math.round(parseDpNumber(attrs.paddingTop) ?? vert ?? all),
    bottom: Math.round(parseDpNumber(attrs.paddingBottom) ?? vert ?? all),
  };
}

function parseXmlMargins(attrs: Record<string, string>) {
  const all = parseDpNumber(attrs.layout_margin) ?? 0;
  const horiz = parseDpNumber(attrs.layout_marginHorizontal);
  const vert = parseDpNumber(attrs.layout_marginVertical);
  return {
    left: Math.round(parseDpNumber(attrs.layout_marginStart ?? attrs.layout_marginLeft) ?? horiz ?? all),
    right: Math.round(parseDpNumber(attrs.layout_marginEnd ?? attrs.layout_marginRight) ?? horiz ?? all),
    top: Math.round(parseDpNumber(attrs.layout_marginTop) ?? vert ?? all),
    bottom: Math.round(parseDpNumber(attrs.layout_marginBottom) ?? vert ?? all),
  };
}

function parseXmlAlign(raw: string | undefined): DesignerElement['textAlign'] | undefined {
  if (!raw) return undefined;
  const lower = raw.toLowerCase();
  if (lower.includes('center')) return 'center';
  if (lower.includes('end') || lower.includes('right')) return 'right';
  if (lower.includes('start') || lower.includes('left')) return 'left';
  return undefined;
}

function resolveXmlBackground(attrs: Record<string, string>, res: ResourceMaps): DrawableShapeInfo {
  const rawBg = attrs.cardBackgroundColor ?? attrs.backgroundTint ?? attrs.background;
  if (!rawBg) return {};
  if (rawBg.startsWith('@drawable/')) {
    const key = rawBg.slice('@drawable/'.length);
    return res.drawables.get(key) ?? {};
  }
  const color = resolveColorExpr(rawBg, res);
  return color ? { fillColor: color } : {};
}

function resolveXmlTextAttr(raw: string | undefined, res: ResourceMaps): string | undefined {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (!trimmed) return undefined;
  if (trimmed.startsWith('@string/')) {
    const key = trimmed.slice('@string/'.length);
    return res.strings.get(key) ?? key;
  }
  return trimmed.replace(/\\n/g, '\n');
}

function importXmlToScreen(ctx: CodeImportContext, res: ResourceMaps): CodeImportResult {
  const screenWidth = ctx.screenWidth ?? DEFAULT_WIDTH;
  const baseHeight = ctx.screenHeight ?? DEFAULT_HEIGHT;
  const screenName =
    ctx.filePath
      .split('/')
      .pop()
      ?.replace(/\.xml$/i, '')
      .replace(/^activity_/, '')
      .replace(/_/g, ' ')
      .replace(/\b\w/g, (c) => c.toUpperCase()) ?? 'XML Screen';

  const root = parseXmlTree(ctx.content);
  if (!root) {
    return {
      screen: {
        id: nextImportedId('screen'),
        name: screenName,
        width: screenWidth,
        height: baseHeight,
        backgroundColor: '#10171F',
        elements: [],
        sourceFilePath: ctx.filePath,
      },
      sourceKind: 'empty',
      sourcePath: ctx.filePath,
      importedCount: 0,
      notes: ['Could not find a valid root XML layout tag.'],
    };
  }

  const rootBg = resolveXmlBackground(root.attrs, res);
  const screenBg = rootBg.fillColor && rootBg.fillColor !== 'transparent' ? rootBg.fillColor : '#10171F';
  const defaultTextColor = isLightHex(screenBg) ? '#142029' : '#EEF4F6';
  const defaultSurfaceColor = isLightHex(screenBg) ? '#FFFFFF' : '#1B2834';
  const elements: DesignerElement[] = [];
  let maxBottomY = baseHeight;

  const makeEl = (kind: DesignerElementKind, overrides: Partial<DesignerElement>): DesignerElement => {
    const base = createDesignerElement(kind, screenWidth, baseHeight, elements.length);
    return { ...base, id: nextImportedId(kind), ...overrides };
  };

  const resolveSize = (raw: string | undefined, avail: number, fallback: number): number => {
    if (!raw) return fallback;
    if (raw === 'match_parent' || raw === 'fill_parent') return Math.max(24, avail);
    if (raw === 'wrap_content') return fallback;
    const dp = parseDpNumber(raw);
    return dp !== undefined && dp > 0 ? Math.round(dp) : fallback;
  };

  const layoutNode = (
    node: XmlNode,
    boxX: number,
    boxY: number,
    availW: number,
    isRoot = false
  ): number => {
    const pad = parseXmlPadding(node.attrs);
    const margin = parseXmlMargins(node.attrs);
    const bg = resolveXmlBackground(node.attrs, res);

    // ScrollView / NestedScrollView unwrap
    if (node.tag === 'ScrollView' || node.tag === 'NestedScrollView') {
      let curY = isRoot ? Math.max(STATUS_BAR_OFFSET, pad.top) : boxY + margin.top + pad.top;
      const innerX = (isRoot ? 0 : boxX + margin.left) + pad.left;
      const innerW = Math.max(40, availW - margin.left - margin.right - pad.left - pad.right);
      for (const child of node.children) {
        curY += layoutNode(child, innerX, curY, innerW, false);
      }
      return Math.max(0, curY - boxY);
    }

    // FrameLayout (exact marginStart / marginTop positioning — 100% round-trip with Designer!)
    if (node.tag === 'FrameLayout' || node.tag === 'CoordinatorLayout') {
      const startX = (isRoot ? 0 : boxX + margin.left) + pad.left;
      const startY = isRoot ? pad.top : boxY + margin.top + pad.top;
      const innerW = Math.max(40, availW - margin.left - margin.right - pad.left - pad.right);
      let deepest = startY;
      for (const child of node.children) {
        const cm = parseXmlMargins(child.attrs);
        const childY = startY + cm.top;
        const childX = startX + cm.left;
        const childAvailW = Math.max(40, innerW - cm.left - cm.right);
        // Pass zeroed margin offset since FrameLayout places child directly at cm.left / cm.top
        const h = layoutLeafOrNested(child, childX, childY, childAvailW, true);
        deepest = Math.max(deepest, childY + h);
      }
      maxBottomY = Math.max(maxBottomY, deepest);
      return Math.max(0, deepest - boxY);
    }

    // LinearLayout (Vertical or Horizontal)
    if (node.tag === 'LinearLayout' || node.tag === 'RadioGroup') {
      const orientation = (node.attrs.orientation ?? 'vertical').toLowerCase();
      const containerX = isRoot ? pad.left : boxX + margin.left;
      const containerY = isRoot ? Math.max(STATUS_BAR_OFFSET, pad.top) : boxY + margin.top;
      const containerW = isRoot
        ? Math.max(40, screenWidth - pad.left - pad.right)
        : resolveSize(node.attrs.layout_width, availW - margin.left - margin.right, availW - margin.left - margin.right);

      let bgCard: DesignerElement | null = null;
      if (!isRoot && bg.fillColor && bg.fillColor !== 'transparent') {
        bgCard = makeEl('card', {
          name: node.attrs.id?.replace(/^@\+?id\//, '') ?? 'Section',
          x: containerX,
          y: containerY,
          width: containerW,
          height: 80,
          fillColor: bg.fillColor,
          strokeColor: bg.strokeColor ?? '#2E4252',
          strokeWidth: bg.strokeWidth ?? 0,
          borderRadius: bg.borderRadius ?? 12,
        });
        elements.push(bgCard);
      }

      const innerX = isRoot ? containerX : containerX + pad.left;
      const innerY = isRoot ? containerY : containerY + pad.top;
      const innerW = isRoot ? containerW : Math.max(40, containerW - pad.left - pad.right);

      if (orientation === 'horizontal') {
        const totalWeight = node.children.reduce(
          (acc, c) => acc + (parseFloat(c.attrs.layout_weight ?? '0') || 0),
          0
        );
        const defaultW = Math.max(48, Math.floor(innerW / Math.max(1, node.children.length)));
        let cx = innerX;
        let maxH = 0;
        for (const child of node.children) {
          const cm = parseXmlMargins(child.attrs);
          const weight = parseFloat(child.attrs.layout_weight ?? '0') || 0;
          const slotW =
            weight > 0 && totalWeight > 0
              ? Math.max(40, Math.round((innerW * weight) / totalWeight) - cm.left - cm.right)
              : resolveSize(child.attrs.layout_width, defaultW, defaultW - cm.left - cm.right);
          const h = layoutLeafOrNested(child, cx + cm.left, innerY + cm.top, slotW, true);
          maxH = Math.max(maxH, h + cm.top + cm.bottom);
          cx += slotW + cm.left + cm.right + 8;
        }
        const totalH = maxH + pad.top + pad.bottom;
        if (bgCard) bgCard.height = Math.max(48, totalH);
        maxBottomY = Math.max(maxBottomY, containerY + totalH);
        return totalH + margin.top + margin.bottom;
      }

      let cy = innerY;
      for (const child of node.children) {
        const cm = parseXmlMargins(child.attrs);
        const h = layoutLeafOrNested(
          child,
          innerX + cm.left,
          cy + cm.top,
          Math.max(40, innerW - cm.left - cm.right),
          true
        );
        cy += cm.top + h + Math.max(cm.bottom, 8);
      }
      const totalH = Math.max(40, cy - containerY + pad.bottom);
      if (bgCard) bgCard.height = totalH;
      maxBottomY = Math.max(maxBottomY, containerY + totalH);
      return totalH + margin.top + margin.bottom;
    }

    // ConstraintLayout / RelativeLayout (honors constraints, margins, and flows cleanly)
    if (node.tag === 'ConstraintLayout' || node.tag === 'RelativeLayout') {
      const startX = (isRoot ? 0 : boxX + margin.left) + pad.left;
      const startY = (isRoot ? STATUS_BAR_OFFSET : boxY + margin.top) + pad.top;
      const innerW = Math.max(40, availW - margin.left - margin.right - pad.left - pad.right);
      const idBounds = new Map<string, { x: number; y: number; w: number; h: number }>();
      let flowY = startY;

      for (const child of node.children) {
        const cm = parseXmlMargins(child.attrs);
        const belowRef = (
          child.attrs.layout_constraintTop_toBottomOf ?? child.attrs.layout_below ?? ''
        ).replace(/^@\+?id\//, '');
        const parentTop = child.attrs.layout_constraintTop_toTopOf === 'parent' || child.attrs.layout_alignParentTop === 'true';
        const parentBottom = child.attrs.layout_constraintBottom_toBottomOf === 'parent' || child.attrs.layout_alignParentBottom === 'true';

        let cy = flowY + cm.top;
        if (belowRef && idBounds.has(belowRef)) {
          const ref = idBounds.get(belowRef)!;
          cy = ref.y + ref.h + Math.max(cm.top, 8);
        } else if (parentTop) {
          cy = startY + cm.top;
        } else if (parentBottom && !parentTop) {
          cy = Math.max(baseHeight - 80 - cm.bottom, flowY);
        }

        const cx = startX + cm.left;
        const childAvailW = Math.max(40, innerW - cm.left - cm.right);
        const h = layoutLeafOrNested(child, cx, cy, childAvailW, true);
        const idClean = child.attrs.id?.replace(/^@\+?id\//, '');
        if (idClean) {
          idBounds.set(idClean, { x: cx, y: cy, w: childAvailW, h });
        }
        flowY = Math.max(flowY, cy + h + Math.max(cm.bottom, 10));
      }
      maxBottomY = Math.max(maxBottomY, flowY);
      return Math.max(0, flowY - boxY);
    }

    // CardView / MaterialCardView
    if (node.tag === 'CardView' || node.tag === 'MaterialCardView') {
      const cardX = boxX + margin.left;
      const cardY = boxY + margin.top;
      const cardW = resolveSize(node.attrs.layout_width, availW - margin.left - margin.right, availW - margin.left - margin.right);
      const radius = Math.round(parseDpNumber(node.attrs.cardCornerRadius ?? node.attrs.cornerRadius) ?? bg.borderRadius ?? 16);
      const elevation = Math.round(parseDpNumber(node.attrs.cardElevation ?? node.attrs.elevation) ?? 6);
      const strokeW = Math.round(parseDpNumber(node.attrs.strokeWidth) ?? bg.strokeWidth ?? 0);
      const strokeColor = resolveColorExpr(node.attrs.strokeColor, res) ?? bg.strokeColor ?? '#2E4252';

      const cardEl = makeEl('card', {
        name: node.attrs.id?.replace(/^@\+?id\//, '') ?? 'Card',
        x: cardX,
        y: cardY,
        width: cardW,
        height: resolveSize(node.attrs.layout_height, 200, 120),
        fillColor: bg.fillColor ?? defaultSurfaceColor,
        strokeColor,
        strokeWidth: strokeW,
        borderRadius: radius,
        shadow: Math.min(24, elevation),
      });
      elements.push(cardEl);

      const innerPad = Math.max(12, pad.left || 12);
      let cy = cardY + Math.max(12, pad.top || 12);
      for (const child of node.children) {
        const cm = parseXmlMargins(child.attrs);
        const h = layoutLeafOrNested(
          child,
          cardX + innerPad + cm.left,
          cy + cm.top,
          Math.max(40, cardW - innerPad * 2 - cm.left - cm.right),
          true
        );
        cy += cm.top + h + Math.max(cm.bottom, 8);
      }
      if (!node.attrs.layout_height || node.attrs.layout_height === 'wrap_content') {
        cardEl.height = Math.max(64, Math.round(cy - cardY + Math.max(12, pad.bottom || 12)));
      }
      maxBottomY = Math.max(maxBottomY, cardY + cardEl.height);
      return cardEl.height + margin.top + margin.bottom;
    }

    return layoutLeafOrNested(node, boxX + margin.left, boxY + margin.top, availW - margin.left - margin.right, true);
  };

  const layoutLeafOrNested = (
    node: XmlNode,
    x: number,
    y: number,
    availW: number,
    marginsAlreadyApplied: boolean
  ): number => {
    if (
      [
        'LinearLayout',
        'FrameLayout',
        'ConstraintLayout',
        'RelativeLayout',
        'ScrollView',
        'NestedScrollView',
        'CardView',
        'MaterialCardView',
        'CoordinatorLayout',
        'RadioGroup',
      ].includes(node.tag)
    ) {
      return layoutNode(node, x, y, availW, false);
    }

    const attrs = node.attrs;
    const bg = resolveXmlBackground(attrs, res);
    const idName = attrs.id?.replace(/^@\+?id\//, '');
    const text = resolveXmlTextAttr(attrs.text, res);
    const hint = resolveXmlTextAttr(attrs.hint, res);
    const textColor = resolveColorExpr(attrs.textColor, res);
    const fontSize = parseDpNumber(attrs.textSize);
    const bold = (attrs.textStyle ?? '').toLowerCase().includes('bold');
    const textAlign =
      parseXmlAlign(attrs.gravity) ?? parseXmlAlign(attrs.textAlignment) ?? undefined;
    const layoutGravity = parseXmlAlign(attrs.layout_gravity);
    const alpha = attrs.alpha ? Math.round(Math.min(1, Math.max(0.1, parseFloat(attrs.alpha))) * 100) : 100;
    const rotation = attrs.rotation ? Math.round(parseFloat(attrs.rotation)) : 0;
    const elevation = Math.min(24, Math.round(parseDpNumber(attrs.elevation) ?? 0));
    const cornerRadius = Math.round(parseDpNumber(attrs.cornerRadius) ?? bg.borderRadius ?? 12);

    const placeX = (w: number): number => {
      if (layoutGravity === 'center' || attrs.layout_centerHorizontal === 'true') {
        return Math.round(x + Math.max(0, (availW - w) / 2));
      }
      if (layoutGravity === 'right' || attrs.layout_alignParentEnd === 'true') {
        return Math.round(x + Math.max(0, availW - w));
      }
      return Math.round(marginsAlreadyApplied ? x : x);
    };

    if (node.tag === 'Toolbar' || node.tag === 'MaterialToolbar') {
      const w = resolveSize(attrs.layout_width, screenWidth, screenWidth);
      const h = resolveSize(attrs.layout_height, 56, 56);
      elements.push(
        makeEl('topBar', {
          name: idName ?? 'Toolbar',
          x: Math.round(x),
          y: Math.round(y),
          width: w,
          height: h,
          text: resolveXmlTextAttr(attrs.title, res) ?? text ?? screenName,
          fillColor: bg.fillColor ?? defaultSurfaceColor,
          textColor: textColor ?? defaultTextColor,
          textAlign: textAlign ?? 'left',
          opacity: alpha,
          rotation,
        })
      );
      return h;
    }

    if (node.tag === 'BottomNavigationView') {
      const w = resolveSize(attrs.layout_width, screenWidth, screenWidth);
      const h = resolveSize(attrs.layout_height, 64, 64);
      elements.push(
        makeEl('bottomNav', {
          name: idName ?? 'Bottom Nav',
          x: Math.round(x),
          y: Math.round(y),
          width: w,
          height: h,
          fillColor: bg.fillColor ?? defaultSurfaceColor,
          textColor: textColor ?? '#9BB0BC',
        })
      );
      return h;
    }

    if (node.tag === 'TextView' || node.tag === 'AppCompatTextView' || node.tag === 'MaterialTextView') {
      const fs = Math.round(fontSize ?? 15);
      const isHeading = fs >= 18 || bold;
      const w = resolveSize(attrs.layout_width, availW, availW);
      const label = text ?? 'TextView';
      const charsPerLine = Math.max(12, Math.floor(w / Math.max(6, fs * 0.56)));
      const estH = Math.max(24, Math.round(fs * 1.45 * Math.ceil(label.length / charsPerLine) + 6));
      const h = resolveSize(attrs.layout_height, estH, estH);
      elements.push(
        makeEl(isHeading ? 'heading' : 'text', {
          name: idName ?? label.slice(0, 20),
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          text: label,
          fontSize: fs,
          fontWeight: bold ? '700' : '400',
          textColor: textColor ?? defaultTextColor,
          textAlign: textAlign ?? 'left',
          fillColor: bg.fillColor ?? 'transparent',
          strokeColor: bg.strokeColor ?? 'transparent',
          strokeWidth: bg.strokeWidth ?? 0,
          borderRadius: bg.borderRadius ?? 0,
          opacity: alpha,
          rotation,
        })
      );
      return h;
    }

    if (node.tag === 'Button' || node.tag === 'AppCompatButton' || node.tag === 'MaterialButton') {
      const w = resolveSize(attrs.layout_width, availW, availW);
      const h = resolveSize(attrs.layout_height, 48, 48);
      const isOutlined = (attrs.style ?? '').toLowerCase().includes('outlined');
      elements.push(
        makeEl(isOutlined ? 'outlinedButton' : 'button', {
          name: idName ?? (text ?? 'Button').slice(0, 20),
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          text: text ?? 'Button',
          fontSize: Math.round(fontSize ?? 15),
          fontWeight: bold ? '700' : '600',
          fillColor: bg.fillColor ?? (isOutlined ? 'transparent' : '#3E8790'),
          textColor: textColor ?? (isOutlined ? '#3E8790' : '#FFFFFF'),
          strokeColor: bg.strokeColor ?? (isOutlined ? '#3E8790' : 'transparent'),
          strokeWidth: bg.strokeWidth ?? (isOutlined ? 1.5 : 0),
          borderRadius: cornerRadius,
          textAlign: textAlign ?? 'center',
          shadow: elevation || (isOutlined ? 0 : 4),
          opacity: alpha,
          rotation,
        })
      );
      return h;
    }

    if (node.tag === 'EditText' || node.tag === 'AppCompatEditText' || node.tag === 'TextInputEditText') {
      const w = resolveSize(attrs.layout_width, availW, availW);
      const h = resolveSize(attrs.layout_height, 50, 50);
      elements.push(
        makeEl('input', {
          name: idName ?? (hint ?? 'Input').slice(0, 20),
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          placeholder: hint ?? text ?? 'Enter text',
          fontSize: Math.round(fontSize ?? 14),
          textColor: textColor ?? defaultTextColor,
          fillColor: bg.fillColor ?? (isLightHex(screenBg) ? '#FFFFFF' : '#111921'),
          strokeColor: bg.strokeColor ?? '#344A5B',
          strokeWidth: bg.strokeWidth ?? 1,
          borderRadius: cornerRadius,
          textAlign: textAlign ?? 'left',
          opacity: alpha,
          rotation,
        })
      );
      return h;
    }

    if (node.tag === 'Switch' || node.tag === 'SwitchCompat' || node.tag === 'SwitchMaterial' || node.tag === 'CheckBox') {
      const w = resolveSize(attrs.layout_width, availW, availW);
      const h = resolveSize(attrs.layout_height, 44, 44);
      elements.push(
        makeEl('switch', {
          name: idName ?? (text ?? node.tag).slice(0, 20),
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          text: text ?? 'Toggle option',
          textColor: textColor ?? defaultTextColor,
          fillColor: bg.fillColor ?? defaultSurfaceColor,
          textAlign: textAlign ?? 'left',
          checked: attrs.checked === 'true',
          opacity: alpha,
          rotation,
        })
      );
      return h;
    }

    if (node.tag === 'ProgressBar' || node.tag === 'SeekBar') {
      const w = resolveSize(attrs.layout_width, availW, availW);
      const h = resolveSize(attrs.layout_height, 14, 14);
      const progress = Number(attrs.progress ?? 70);
      elements.push(
        makeEl('progress', {
          name: idName ?? node.tag,
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          fillColor: bg.fillColor ?? '#223340',
          strokeColor: resolveColorExpr(attrs.progressTint, res) ?? '#4B9BA2',
          progress: Number.isFinite(progress) ? progress : 70,
          opacity: alpha,
        })
      );
      return h;
    }

    if (node.tag === 'ImageView' || node.tag === 'AppCompatImageView' || node.tag === 'ShapeableImageView') {
      const w = resolveSize(attrs.layout_width, availW, Math.min(availW, 180));
      const h = resolveSize(attrs.layout_height, 180, 160);
      const desc =
        resolveXmlTextAttr(attrs.contentDescription, res) ??
        attrs.src?.replace(/^@(?:drawable|mipmap)\//, '') ??
        'Image';
      elements.push(
        makeEl('image', {
          name: idName ?? desc.slice(0, 20),
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          text: desc,
          fillColor: bg.fillColor ?? defaultSurfaceColor,
          borderRadius: cornerRadius,
          opacity: alpha,
          rotation,
        })
      );
      return h;
    }

    if (node.tag === 'FloatingActionButton') {
      const w = resolveSize(attrs.layout_width, 56, 56);
      const h = resolveSize(attrs.layout_height, 56, 56);
      elements.push(
        makeEl('fab', {
          name: idName ?? 'FAB',
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          fillColor: bg.fillColor ?? '#3E8790',
          textColor: '#FFFFFF',
          text: '+',
          shadow: elevation || 10,
        })
      );
      return h;
    }

    if (node.tag === 'View') {
      const w = resolveSize(attrs.layout_width, availW, availW);
      const h = resolveSize(attrs.layout_height, 2, 2);
      const isDivider = h <= 6;
      elements.push(
        makeEl(isDivider ? 'divider' : 'rectangle', {
          name: idName ?? (isDivider ? 'Divider' : 'View'),
          x: placeX(w),
          y: Math.round(y),
          width: w,
          height: h,
          fillColor: bg.fillColor ?? '#2B3E4C',
          strokeColor: bg.strokeColor ?? 'transparent',
          strokeWidth: bg.strokeWidth ?? 0,
          borderRadius: isDivider ? 1 : cornerRadius,
          opacity: alpha,
          rotation,
        })
      );
      return h;
    }

    return 0;
  };

  layoutNode(root, 0, 0, screenWidth, true);
  const finalHeight = Math.min(1400, Math.max(baseHeight, Math.round(maxBottomY + 48)));

  return {
    screen: {
      id: nextImportedId('screen'),
      name: screenName,
      width: screenWidth,
      height: finalHeight,
      backgroundColor: screenBg,
      elements,
      sourceFilePath: ctx.filePath,
    },
    sourceKind: 'xml',
    sourcePath: ctx.filePath,
    importedCount: elements.length,
    notes: [
      `Imported ${elements.length} XML layout elements from ${ctx.filePath.split('/').pop()} with exact colors, sizes, alignment, and positions.`,
    ],
  };
}

/* -------------------------------------------------------------------------- */
/* 3. PROGRAMMATIC KOTLIN/JAVA ANDROID VIEW PARSER (Non-XML, Non-Compose)     */
/* -------------------------------------------------------------------------- */

function extractCallArg(source: string, fnName: string): string | undefined {
  const re = new RegExp(`\\b${fnName}\\s*\\(`);
  const m = re.exec(source);
  if (!m) return undefined;
  const openIdx = m.index + m[0].length - 1;
  const arg = readBalanced(source, openIdx, '(', ')').text.trim();
  return arg || undefined;
}

function importProgrammaticKotlinViews(
  ctx: CodeImportContext,
  res: ResourceMaps
): CodeImportResult {
  const screenWidth = ctx.screenWidth ?? DEFAULT_WIDTH;
  const baseHeight = ctx.screenHeight ?? DEFAULT_HEIGHT;
  const screenName = ctx.filePath.split('/').pop()?.replace(/\.(kt|java)$/i, '') ?? 'Activity Screen';
  const src = ctx.content;

  // Detect overall screen background color if set on root layout
  const bgArg = extractCallArg(src, 'setBackgroundColor');
  const screenBg = (bgArg && resolveColorExpr(bgArg, res)) || '#10171F';
  const defaultText = isLightHex(screenBg) ? '#142029' : '#EEF4F6';
  const defaultSurface = isLightHex(screenBg) ? '#FFFFFF' : '#1B2834';

  const elements: DesignerElement[] = [];
  let cursorY = STATUS_BAR_OFFSET + 16;
  const innerW = screenWidth - 36;

  const ctorRe =
    /\b(TextView|Button|EditText|Switch|CheckBox|ProgressBar|ImageView)\s*\(\s*this[^)]*\)\s*(?:\.apply\s*\{)?/g;
  let m: RegExpExecArray | null;
  let count = 0;

  while ((m = ctorRe.exec(src)) !== null && count < 28) {
    const widget = m[1];
    const hasApply = m[0].includes('{');
    let body = '';
    if (hasApply) {
      const openIdx = m.index + m[0].lastIndexOf('{');
      body = readBalanced(src, openIdx, '{', '}').text;
    }

    const textMatch = body.match(/\btext\s*=\s*"([^"]+)"/);
    const hintMatch = body.match(/\bhint\s*=\s*"([^"]+)"/);
    const sizeMatch = body.match(/\btextSize\s*=\s*(\d+(?:\.\d+)?)f?/);
    const textColorArg = extractCallArg(body, 'setTextColor');
    const bgColorArg = extractCallArg(body, 'setBackgroundColor');
    const gravMatch = body.match(/\bgravity\s*=\s*Gravity\.([A-Z_]+)/);
    const bold = /Typeface\.DEFAULT_BOLD|Typeface\.BOLD/.test(body);

    const text = textMatch?.[1];
    const hint = hintMatch?.[1];
    const fontSize = sizeMatch ? Math.round(parseFloat(sizeMatch[1])) : 15;
    const textColor = (textColorArg && resolveColorExpr(textColorArg, res)) || defaultText;
    const fillColor = bgColorArg ? resolveColorExpr(bgColorArg, res) : undefined;
    const textAlign: DesignerElement['textAlign'] = gravMatch
      ? gravMatch[1].includes('CENTER')
        ? 'center'
        : gravMatch[1].includes('END') || gravMatch[1].includes('RIGHT')
        ? 'right'
        : 'left'
      : widget === 'Button'
      ? 'center'
      : 'left';

    // Skip empty boilerplate views without text/hint unless ProgressBar/ImageView
    if (!text && !hint && widget !== 'ProgressBar' && widget !== 'ImageView') continue;

    let kind: DesignerElementKind = 'text';
    let h = 36;
    if (widget === 'TextView') {
      kind = fontSize >= 18 || bold ? 'heading' : 'text';
      h = Math.max(28, Math.round(fontSize * 1.6 * Math.ceil((text?.length ?? 12) / 36) + 8));
    } else if (widget === 'Button') {
      kind = 'button';
      h = 46;
    } else if (widget === 'EditText') {
      kind = 'input';
      h = 48;
    } else if (widget === 'Switch' || widget === 'CheckBox') {
      kind = 'switch';
      h = 44;
    } else if (widget === 'ProgressBar') {
      kind = 'progress';
      h = 14;
    } else if (widget === 'ImageView') {
      kind = 'image';
      h = 140;
    }

    const base = createDesignerElement(kind, screenWidth, baseHeight, elements.length);
    elements.push({
      ...base,
      id: nextImportedId(kind),
      name: (text ?? hint ?? widget).slice(0, 22),
      x: 18,
      y: cursorY,
      width: innerW,
      height: h,
      text: text ?? base.text,
      placeholder: hint ?? base.placeholder,
      fontSize,
      fontWeight: bold ? '700' : widget === 'Button' ? '600' : '400',
      textColor: widget === 'Button' && !textColorArg ? '#FFFFFF' : textColor,
      fillColor:
        fillColor ??
        (widget === 'TextView' ? 'transparent' : widget === 'Button' ? '#3E8790' : defaultSurface),
      textAlign,
    });
    cursorY += h + 12;
    count += 1;
  }

  return {
    screen: {
      id: nextImportedId('screen'),
      name: screenName,
      width: screenWidth,
      height: Math.min(1400, Math.max(baseHeight, cursorY + 48)),
      backgroundColor: screenBg,
      elements,
      sourceFilePath: ctx.filePath,
    },
    sourceKind: elements.length > 0 ? 'kotlin-views' : 'empty',
    sourcePath: ctx.filePath,
    importedCount: elements.length,
    notes:
      elements.length > 0
        ? [`Imported ${elements.length} programmatic Android Views from ${ctx.filePath.split('/').pop()}.`]
        : ['No UI elements were matched in this file.'],
  };
}

/* -------------------------------------------------------------------------- */
/* PUBLIC ENTRYPOINT & PROJECT DISCOVERY                                      */
/* -------------------------------------------------------------------------- */

export function importCodeToDesignerScreen(ctx: CodeImportContext): CodeImportResult {
  const projectFiles = ctx.projectFiles ?? [];
  const res = buildResourceMaps(ctx.content, projectFiles);
  const lower = ctx.filePath.toLowerCase();

  // 1. Direct XML layout file
  if (
    lower.endsWith('.xml') &&
    (lower.includes('/layout/') ||
      /<(?:LinearLayout|FrameLayout|ConstraintLayout|RelativeLayout|ScrollView|CardView|CoordinatorLayout|TextView|Button)/.test(
        ctx.content
      ))
  ) {
    return importXmlToScreen(ctx, res);
  }

  // 2. Kotlin/Java Activity that inflates an XML layout via setContentView(R.layout.xxx)
  const layoutRef = ctx.content.match(/setContentView\s*\(\s*R\.layout\.([A-Za-z0-9_]+)\s*\)/);
  if (layoutRef) {
    const targetXml = projectFiles.find((f) =>
      f.path.toLowerCase().endsWith(`/layout/${layoutRef[1].toLowerCase()}.xml`)
    );
    if (targetXml) {
      return importXmlToScreen(
        {
          ...ctx,
          filePath: targetXml.path,
          content: targetXml.content,
        },
        res
      );
    }
  }

  // 3. Jetpack Compose Kotlin file (Non-XML UI)
  if (
    lower.endsWith('.kt') &&
    (/@Composable/.test(ctx.content) ||
      /\b(Scaffold|Column|Row|Box|Surface|Card|TopAppBar|Text|Button|OutlinedTextField)\s*\(/.test(
        ctx.content
      ))
  ) {
    const composeResult = importComposeToScreen(ctx, res);
    if (composeResult.importedCount > 0) return composeResult;
  }

  // 4. Programmatic Kotlin/Java Android View Activity (Non-XML UI)
  if (lower.endsWith('.kt') || lower.endsWith('.java')) {
    return importProgrammaticKotlinViews(ctx, res);
  }

  return {
    screen: {
      id: nextImportedId('screen'),
      name: ctx.filePath.split('/').pop() ?? 'Imported Screen',
      width: ctx.screenWidth ?? DEFAULT_WIDTH,
      height: ctx.screenHeight ?? DEFAULT_HEIGHT,
      backgroundColor: '#10171F',
      elements: [],
      sourceFilePath: ctx.filePath,
    },
    sourceKind: 'empty',
    sourcePath: ctx.filePath,
    importedCount: 0,
    notes: ['Select a Jetpack Compose (.kt), Activity (.kt), or Android XML layout (.xml) file.'],
  };
}

export function listImportableUiFiles(files: ProjectFile[]): ProjectFile[] {
  return files.filter((f) => {
    const lower = f.path.toLowerCase();
    if (lower.endsWith('.xml') && lower.includes('/layout/')) return true;
    if (lower.endsWith('.kt')) {
      if (/@Composable/.test(f.content)) return true;
      if (/setContentView\s*\(\s*R\.layout\./.test(f.content)) return true;
      if (/\b(TextView|Button|EditText|LinearLayout)\s*\(\s*this/.test(f.content)) return true;
    }
    return false;
  });
}
