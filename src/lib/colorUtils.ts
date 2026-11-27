export interface HsvColor {
  h: number; // 0..360
  s: number; // 0..100
  v: number; // 0..100
}

export interface ColorCombination {
  id: string;
  name: string;
  background: string;
  surface: string;
  accent: string;
  text: string;
  mutedText: string;
  onAccent: string;
}

export const COLOR_COMBINATIONS: ColorCombination[] = [
  {
    id: 'ocean-slate',
    name: 'Ocean Slate',
    background: '#10171F',
    surface: '#1B2834',
    accent: '#3E8790',
    text: '#EEF4F6',
    mutedText: '#9BB0BC',
    onAccent: '#FFFFFF',
  },
  {
    id: 'midnight-indigo',
    name: 'Midnight Indigo',
    background: '#0E121D',
    surface: '#1A2133',
    accent: '#6366F1',
    text: '#F1F5F9',
    mutedText: '#94A3B8',
    onAccent: '#FFFFFF',
  },
  {
    id: 'emerald-dark',
    name: 'Emerald Studio',
    background: '#0D1714',
    surface: '#162822',
    accent: '#10B981',
    text: '#ECFDF5',
    mutedText: '#86B8A7',
    onAccent: '#062016',
  },
  {
    id: 'amber-charcoal',
    name: 'Amber Charcoal',
    background: '#17130F',
    surface: '#261F18',
    accent: '#F59E0B',
    text: '#FEF9EE',
    mutedText: '#C2B09B',
    onAccent: '#1C1306',
  },
  {
    id: 'rose-obsidian',
    name: 'Rose Obsidian',
    background: '#181014',
    surface: '#281A21',
    accent: '#F43F5E',
    text: '#FFF1F2',
    mutedText: '#C79FA8',
    onAccent: '#FFFFFF',
  },
  {
    id: 'crisp-daylight',
    name: 'Crisp Daylight',
    background: '#F4F7FA',
    surface: '#FFFFFF',
    accent: '#2563EB',
    text: '#0F172A',
    mutedText: '#475569',
    onAccent: '#FFFFFF',
  },
  {
    id: 'warm-sand',
    name: 'Warm Paper',
    background: '#F8F5F0',
    surface: '#FFFFFF',
    accent: '#D97706',
    text: '#1C1917',
    mutedText: '#57534E',
    onAccent: '#FFFFFF',
  },
  {
    id: 'nordic-teal',
    name: 'Nordic Teal',
    background: '#EEF5F6',
    surface: '#FFFFFF',
    accent: '#0F766E',
    text: '#102428',
    mutedText: '#48666C',
    onAccent: '#FFFFFF',
  },
];

export function normalizeHex(input: string, fallback = '#3E8790'): string {
  const clean = input.trim().replace(/^#/, '');
  if (/^[0-9a-fA-F]{6}$/.test(clean)) {
    return `#${clean.toUpperCase()}`;
  }
  if (/^[0-9a-fA-F]{3}$/.test(clean)) {
    const expanded = clean
      .split('')
      .map((c) => c + c)
      .join('');
    return `#${expanded.toUpperCase()}`;
  }
  return fallback;
}

export function hexToHsv(hex: string): HsvColor {
  const safe = normalizeHex(hex).slice(1);
  const r = parseInt(safe.slice(0, 2), 16) / 255;
  const g = parseInt(safe.slice(2, 4), 16) / 255;
  const b = parseInt(safe.slice(4, 6), 16) / 255;

  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;

  let h = 0;
  if (delta !== 0) {
    if (max === r) h = 60 * (((g - b) / delta) % 6);
    else if (max === g) h = 60 * ((b - r) / delta + 2);
    else h = 60 * ((r - g) / delta + 4);
  }
  if (h < 0) h += 360;

  const s = max === 0 ? 0 : (delta / max) * 100;
  const v = max * 100;
  return {
    h: Math.round(h),
    s: Math.round(s),
    v: Math.round(v),
  };
}

export function hsvToHex(h: number, s: number, v: number): string {
  const hue = ((h % 360) + 360) % 360;
  const sat = Math.max(0, Math.min(100, s)) / 100;
  const val = Math.max(0, Math.min(100, v)) / 100;

  const c = val * sat;
  const x = c * (1 - Math.abs(((hue / 60) % 2) - 1));
  const m = val - c;

  let r = 0;
  let g = 0;
  let b = 0;

  if (hue < 60) [r, g, b] = [c, x, 0];
  else if (hue < 120) [r, g, b] = [x, c, 0];
  else if (hue < 180) [r, g, b] = [0, c, x];
  else if (hue < 240) [r, g, b] = [0, x, c];
  else if (hue < 300) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];

  const toHex = (n: number) =>
    Math.round((n + m) * 255)
      .toString(16)
      .padStart(2, '0')
      .toUpperCase();

  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}
