import type { ColorCombination } from './colorUtils';

export type DesignerElementKind =
  | 'topBar'
  | 'bottomNav'
  | 'card'
  | 'rectangle'
  | 'circle'
  | 'heading'
  | 'text'
  | 'button'
  | 'outlinedButton'
  | 'input'
  | 'switch'
  | 'progress'
  | 'fab'
  | 'image'
  | 'divider';

export type ResizeHandle = 'nw' | 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w';

export type AlignAction = 'left' | 'centerX' | 'right' | 'top' | 'centerY' | 'bottom';

export interface DesignerElement {
  id: string;
  name: string;
  kind: DesignerElementKind;
  x: number;
  y: number;
  width: number;
  height: number;
  rotation: number;
  opacity: number; // 0..100
  borderRadius: number;
  fillColor: string;
  strokeColor: string;
  strokeWidth: number;
  shadow: number; // 0..24
  text: string;
  placeholder?: string;
  textColor: string;
  fontSize: number;
  fontWeight: '400' | '500' | '600' | '700';
  textAlign: 'left' | 'center' | 'right';
  imageUrl?: string;
  imageFit?: 'cover' | 'contain';
  brightness: number; // 50..150
  contrast: number; // 50..150
  saturation: number; // 0..200
  blur: number; // 0..16
  checked?: boolean;
  progress?: number;
  visible: boolean;
  locked: boolean;
}

export interface DesignerScreen {
  id: string;
  name: string;
  width: number;
  height: number;
  backgroundColor: string;
  elements: DesignerElement[];
}

export interface SnapGuides {
  verticalCenter: boolean;
  horizontalCenter: boolean;
  verticalX: number | null;
  horizontalY: number | null;
}

export interface MoveResult {
  x: number;
  y: number;
  guides: SnapGuides;
}

let uidCounter = 1;
function nextId(prefix: string): string {
  uidCounter += 1;
  return `${prefix}-${Date.now().toString(36)}-${uidCounter}`;
}

export function createDesignerElement(
  kind: DesignerElementKind,
  screenWidth = 390,
  screenHeight = 800,
  offsetIndex = 0
): DesignerElement {
  const cascade = (offsetIndex % 6) * 14;
  const base: DesignerElement = {
    id: nextId(kind),
    name: kind,
    kind,
    x: 24 + cascade,
    y: 120 + cascade,
    width: 180,
    height: 48,
    rotation: 0,
    opacity: 100,
    borderRadius: 12,
    fillColor: '#1E2C38',
    strokeColor: '#324858',
    strokeWidth: 0,
    shadow: 0,
    text: '',
    textColor: '#EEF4F6',
    fontSize: 15,
    fontWeight: '500',
    textAlign: 'left',
    brightness: 100,
    contrast: 100,
    saturation: 100,
    blur: 0,
    visible: true,
    locked: false,
  };

  switch (kind) {
    case 'topBar':
      return {
        ...base,
        name: 'Top App Bar',
        x: 0,
        y: 36,
        width: screenWidth,
        height: 56,
        borderRadius: 0,
        fillColor: '#192530',
        strokeColor: '#2B3E4D',
        strokeWidth: 1,
        text: 'Pocket App',
        fontSize: 18,
        fontWeight: '700',
      };
    case 'bottomNav':
      return {
        ...base,
        name: 'Bottom Nav Bar',
        x: 0,
        y: Math.max(0, screenHeight - 64),
        width: screenWidth,
        height: 64,
        borderRadius: 0,
        fillColor: '#15202B',
        strokeColor: '#2A3C4A',
        strokeWidth: 1,
        text: 'Home | Search | Profile',
        fontSize: 12,
        textColor: '#9BB0BC',
        textAlign: 'center',
      };
    case 'card':
      return {
        ...base,
        name: 'Surface Card',
        x: 20,
        y: 116 + cascade,
        width: screenWidth - 40,
        height: 156,
        borderRadius: 18,
        fillColor: '#1B2834',
        strokeColor: '#2E4252',
        strokeWidth: 1,
        shadow: 6,
      };
    case 'rectangle':
      return {
        ...base,
        name: 'Rectangle',
        x: 40 + cascade,
        y: 180 + cascade,
        width: 150,
        height: 100,
        borderRadius: 14,
        fillColor: '#2C4957',
        strokeColor: '#4B9BA2',
        strokeWidth: 1,
      };
    case 'circle':
      return {
        ...base,
        name: 'Avatar Circle',
        x: Math.round((screenWidth - 88) / 2),
        y: 140 + cascade,
        width: 88,
        height: 88,
        borderRadius: 999,
        fillColor: '#3E8790',
        strokeColor: '#78A5A8',
        strokeWidth: 2,
        text: 'PA',
        textAlign: 'center',
        fontSize: 22,
        fontWeight: '700',
      };
    case 'heading':
      return {
        ...base,
        name: 'Heading',
        x: 24,
        y: 112 + cascade,
        width: screenWidth - 48,
        height: 40,
        fillColor: 'transparent',
        text: 'Welcome back',
        fontSize: 24,
        fontWeight: '700',
        textColor: '#F3F7F9',
      };
    case 'text':
      return {
        ...base,
        name: 'Body Text',
        x: 24,
        y: 156 + cascade,
        width: screenWidth - 48,
        height: 44,
        fillColor: 'transparent',
        text: 'Design your Android layout visually and export clean Compose or XML code.',
        fontSize: 14,
        fontWeight: '400',
        textColor: '#A6BAC6',
      };
    case 'button':
      return {
        ...base,
        name: 'Primary Button',
        x: 24,
        y: 340 + cascade,
        width: screenWidth - 48,
        height: 48,
        borderRadius: 14,
        fillColor: '#3E8790',
        text: 'Continue',
        textColor: '#FFFFFF',
        fontSize: 15,
        fontWeight: '600',
        textAlign: 'center',
        shadow: 4,
      };
    case 'outlinedButton':
      return {
        ...base,
        name: 'Outlined Button',
        x: 24,
        y: 400 + cascade,
        width: screenWidth - 48,
        height: 48,
        borderRadius: 14,
        fillColor: 'transparent',
        strokeColor: '#5BA3AA',
        strokeWidth: 1.5,
        text: 'Secondary Action',
        textColor: '#9FD1D6',
        fontSize: 15,
        fontWeight: '600',
        textAlign: 'center',
      };
    case 'input':
      return {
        ...base,
        name: 'Text Input',
        x: 24,
        y: 230 + cascade,
        width: screenWidth - 48,
        height: 50,
        borderRadius: 12,
        fillColor: '#111921',
        strokeColor: '#314656',
        strokeWidth: 1,
        text: '',
        placeholder: 'Email or username',
        textColor: '#DCE7EC',
        fontSize: 14,
      };
    case 'switch':
      return {
        ...base,
        name: 'Toggle Switch',
        x: 24,
        y: 296 + cascade,
        width: screenWidth - 48,
        height: 44,
        borderRadius: 12,
        fillColor: '#16202A',
        strokeColor: '#273846',
        strokeWidth: 1,
        text: 'Enable notifications',
        textColor: '#E4EEF2',
        fontSize: 14,
        checked: true,
      };
    case 'progress':
      return {
        ...base,
        name: 'Progress Bar',
        x: 24,
        y: 310 + cascade,
        width: screenWidth - 48,
        height: 14,
        borderRadius: 999,
        fillColor: '#223340',
        strokeColor: '#4B9BA2',
        progress: 72,
      };
    case 'fab':
      return {
        ...base,
        name: 'Floating Button',
        x: screenWidth - 76,
        y: screenHeight - 136,
        width: 56,
        height: 56,
        borderRadius: 18,
        fillColor: '#3E8790',
        text: '+',
        textColor: '#FFFFFF',
        fontSize: 26,
        fontWeight: '600',
        textAlign: 'center',
        shadow: 10,
      };
    case 'image':
      return {
        ...base,
        name: 'Photo Frame',
        x: 20,
        y: 112 + cascade,
        width: screenWidth - 40,
        height: 190,
        borderRadius: 16,
        fillColor: '#1D2C38',
        strokeColor: '#314757',
        strokeWidth: 1,
        text: 'Upload Photo',
        textColor: '#9BB0BC',
        textAlign: 'center',
        imageFit: 'cover',
      };
    case 'divider':
      return {
        ...base,
        name: 'Divider',
        x: 24,
        y: 260 + cascade,
        width: screenWidth - 48,
        height: 2,
        borderRadius: 1,
        fillColor: '#2B3E4C',
      };
  }
}

export function createStarterDesignerScreen(
  preset: 'dashboard' | 'login' | 'product' | 'blank',
  width = 390,
  height = 800
): DesignerScreen {
  const id = nextId('screen');
  if (preset === 'blank') {
    return {
      id,
      name: 'Blank Screen',
      width,
      height,
      backgroundColor: '#10171F',
      elements: [],
    };
  }

  if (preset === 'login') {
    return {
      id,
      name: 'Sign In Screen',
      width,
      height,
      backgroundColor: '#10171F',
      elements: [
        { ...createDesignerElement('circle', width, height), x: Math.round((width - 76) / 2), y: 92, width: 76, height: 76, text: 'PS' },
        { ...createDesignerElement('heading', width, height), y: 190, text: 'Sign in to Studio', textAlign: 'center' },
        { ...createDesignerElement('text', width, height), y: 234, text: 'Sync your Android builds and layouts across devices.', textAlign: 'center' },
        { ...createDesignerElement('input', width, height), y: 306, placeholder: 'Email address' },
        { ...createDesignerElement('input', width, height), y: 372, placeholder: 'Password' },
        { ...createDesignerElement('switch', width, height), y: 440, text: 'Keep me signed in', checked: true },
        { ...createDesignerElement('button', width, height), y: 508, text: 'Sign In' },
        { ...createDesignerElement('outlinedButton', width, height), y: 570, text: 'Create Account' },
      ],
    };
  }

  if (preset === 'product') {
    return {
      id,
      name: 'Showcase Screen',
      width,
      height,
      backgroundColor: '#10171F',
      elements: [
        { ...createDesignerElement('topBar', width, height), text: 'Featured Build' },
        { ...createDesignerElement('image', width, height), x: 20, y: 112, width: width - 40, height: 220, text: 'Drop or Upload Hero Photo' },
        { ...createDesignerElement('heading', width, height), y: 352, text: 'Pocket APK Studio 1.6', fontSize: 22 },
        { ...createDesignerElement('text', width, height), y: 394, text: 'Visual mobile UI builder with instant Jetpack Compose and XML export.' },
        { ...createDesignerElement('card', width, height), y: 454, height: 108 },
        { ...createDesignerElement('text', width, height), x: 38, y: 472, width: width - 76, height: 24, text: 'Build Readiness · Android 16 (SDK 36)', fontWeight: '600', textColor: '#EEF4F6' },
        { ...createDesignerElement('progress', width, height), x: 38, y: 512, width: width - 76, progress: 86 },
        { ...createDesignerElement('button', width, height), y: 586, text: 'Export Android Layout' },
        { ...createDesignerElement('bottomNav', width, height) },
      ],
    };
  }

  // Default: dashboard
  return {
    id,
    name: 'Home Dashboard',
    width,
    height,
    backgroundColor: '#10171F',
    elements: [
      { ...createDesignerElement('topBar', width, height), text: 'Pocket APK Studio' },
      { ...createDesignerElement('card', width, height), x: 20, y: 112, width: width - 40, height: 168, fillColor: '#192632' },
      { ...createDesignerElement('heading', width, height), x: 36, y: 132, width: width - 72, height: 34, text: 'Android Workspace', fontSize: 21 },
      { ...createDesignerElement('text', width, height), x: 36, y: 170, width: width - 72, height: 42, text: 'Drag elements, drop photos, adjust filters, and export Compose or XML.' },
      { ...createDesignerElement('button', width, height), x: 36, y: 220, width: 152, height: 40, text: 'Build APK', fontSize: 13 },
      { ...createDesignerElement('image', width, height), x: 20, y: 300, width: width - 40, height: 156, text: 'Tap Upload Photo in Inspector' },
      { ...createDesignerElement('switch', width, height), x: 20, y: 476, width: width - 40, text: 'Auto-sign debug builds', checked: true },
      { ...createDesignerElement('progress', width, height), x: 20, y: 540, width: width - 40, progress: 78 },
      { ...createDesignerElement('outlinedButton', width, height), x: 20, y: 576, width: width - 40, text: 'Inspect APK Metadata' },
      { ...createDesignerElement('fab', width, height) },
      { ...createDesignerElement('bottomNav', width, height) },
    ],
  };
}

export function moveElementWithSnap(
  el: DesignerElement,
  rawX: number,
  rawY: number,
  screen: DesignerScreen,
  snapToGrid: boolean,
  gridSize = 8
): MoveResult {
  let x = Math.round(rawX);
  let y = Math.round(rawY);

  if (snapToGrid) {
    x = Math.round(x / gridSize) * gridSize;
    y = Math.round(y / gridSize) * gridSize;
  }

  const guides: SnapGuides = {
    verticalCenter: false,
    horizontalCenter: false,
    verticalX: null,
    horizontalY: null,
  };

  const centerX = Math.round((screen.width - el.width) / 2);
  const centerY = Math.round((screen.height - el.height) / 2);

  if (Math.abs(x - centerX) <= 7) {
    x = centerX;
    guides.verticalCenter = true;
    guides.verticalX = Math.round(screen.width / 2);
  }
  if (Math.abs(y - centerY) <= 7) {
    y = centerY;
    guides.horizontalCenter = true;
    guides.horizontalY = Math.round(screen.height / 2);
  }

  // Clamp so element stays partially reachable inside artboard
  x = Math.max(-el.width + 24, Math.min(screen.width - 24, x));
  y = Math.max(0, Math.min(screen.height - 24, y));

  return { x, y, guides };
}

export function resizeElementFromHandle(
  initial: Pick<DesignerElement, 'x' | 'y' | 'width' | 'height'>,
  handle: ResizeHandle,
  dx: number,
  dy: number,
  snapToGrid = false,
  gridSize = 8
): Pick<DesignerElement, 'x' | 'y' | 'width' | 'height'> {
  const minW = 24;
  const minH = 2;
  let { x, y, width, height } = initial;

  const snap = (v: number) => (snapToGrid ? Math.round(v / gridSize) * gridSize : Math.round(v));

  if (handle.includes('e')) {
    width = Math.max(minW, snap(initial.width + dx));
  }
  if (handle.includes('s')) {
    height = Math.max(minH, snap(initial.height + dy));
  }
  if (handle.includes('w')) {
    const nextW = Math.max(minW, snap(initial.width - dx));
    x = initial.x + (initial.width - nextW);
    width = nextW;
  }
  if (handle.includes('n')) {
    const nextH = Math.max(minH, snap(initial.height - dy));
    y = initial.y + (initial.height - nextH);
    height = nextH;
  }

  return { x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) };
}

export function alignElementInCanvas(
  el: DesignerElement,
  action: AlignAction,
  screen: Pick<DesignerScreen, 'width' | 'height'>
): Pick<DesignerElement, 'x' | 'y'> {
  switch (action) {
    case 'left':
      return { x: 16, y: el.y };
    case 'centerX':
      return { x: Math.round((screen.width - el.width) / 2), y: el.y };
    case 'right':
      return { x: Math.max(0, screen.width - el.width - 16), y: el.y };
    case 'top':
      return { x: el.x, y: 40 };
    case 'centerY':
      return { x: el.x, y: Math.round((screen.height - el.height) / 2) };
    case 'bottom':
      return { x: el.x, y: Math.max(0, screen.height - el.height - 16) };
  }
}

export function reorderLayer(
  elements: DesignerElement[],
  id: string,
  direction: 'up' | 'down'
): DesignerElement[] {
  const index = elements.findIndex((el) => el.id === id);
  if (index === -1) return elements;
  const target = direction === 'up' ? index + 1 : index - 1;
  if (target < 0 || target >= elements.length) return elements;
  const copy = [...elements];
  const [item] = copy.splice(index, 1);
  copy.splice(target, 0, item);
  return copy;
}

function hexToComposeColor(hex: string): string {
  const clean = hex.trim().replace(/^#/, '');
  if (/^[0-9a-fA-F]{6}$/.test(clean)) {
    return `Color(0xFF${clean.toUpperCase()})`;
  }
  if (/^[0-9a-fA-F]{8}$/.test(clean)) {
    return `Color(0x${clean.toUpperCase()})`;
  }
  return 'Color.Transparent';
}

function escapeKotlinString(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

function escapeXmlString(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function toComposeTextAlign(align: DesignerElement['textAlign']): string {
  if (align === 'center') return 'TextAlign.Center';
  if (align === 'right') return 'TextAlign.End';
  return 'TextAlign.Start';
}

function toXmlGravity(align: DesignerElement['textAlign']): string {
  if (align === 'center') return 'center';
  if (align === 'right') return 'end';
  return 'start';
}

export function applyColorCombinationToScreen(
  screen: DesignerScreen,
  combo: ColorCombination
): DesignerScreen {
  return {
    ...screen,
    backgroundColor: combo.background,
    elements: screen.elements.map((el) => {
      switch (el.kind) {
        case 'topBar':
        case 'bottomNav':
        case 'card':
        case 'switch':
          return {
            ...el,
            fillColor: combo.surface,
            strokeColor: combo.accent,
            textColor: el.kind === 'bottomNav' ? combo.mutedText : combo.text,
          };
        case 'button':
        case 'fab':
        case 'circle':
          return {
            ...el,
            fillColor: combo.accent,
            strokeColor: combo.accent,
            textColor: combo.onAccent,
          };
        case 'outlinedButton':
          return {
            ...el,
            fillColor: 'transparent',
            strokeColor: combo.accent,
            textColor: combo.accent,
          };
        case 'heading':
          return {
            ...el,
            textColor: combo.text,
          };
        case 'text':
          return {
            ...el,
            textColor: combo.mutedText,
          };
        case 'input':
          return {
            ...el,
            fillColor: combo.surface,
            strokeColor: combo.mutedText,
            textColor: combo.text,
          };
        case 'progress':
          return {
            ...el,
            fillColor: combo.surface,
            strokeColor: combo.accent,
          };
        case 'rectangle':
          return {
            ...el,
            fillColor: combo.surface,
            strokeColor: combo.accent,
            textColor: combo.text,
          };
        case 'divider':
          return {
            ...el,
            fillColor: combo.surface,
          };
        default:
          return el;
      }
    }),
  };
}

export function generateComposeFromScreen(screen: DesignerScreen, packageName: string): string {
  const fnName =
    screen.name
      .replace(/[^a-zA-Z0-9]+/g, ' ')
      .trim()
      .split(/\s+/)
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join('') || 'DesignedScreen';

  const visibleElements = screen.elements.filter((el) => el.visible);
  const bodyLines = visibleElements.map((el) => {
    const label = escapeKotlinString(el.text || el.placeholder || el.name);
    const offsetMod = `Modifier.offset(x = ${el.x}.dp, y = ${el.y}.dp).size(width = ${el.width}.dp, height = ${el.height}.dp)`;
    const composeAlign = toComposeTextAlign(el.textAlign);
    switch (el.kind) {
      case 'topBar':
        return `        TopAppBar(\n            title = { Text("${label}", textAlign = ${composeAlign}) },\n            modifier = ${offsetMod}\n        )`;
      case 'heading':
      case 'text':
        return `        Text(\n            text = "${label}",\n            fontSize = ${el.fontSize}.sp,\n            fontWeight = FontWeight.${el.fontWeight === '700' ? 'Bold' : el.fontWeight === '600' ? 'SemiBold' : 'Normal'},\n            textAlign = ${composeAlign},\n            color = ${hexToComposeColor(el.textColor)},\n            modifier = ${offsetMod}\n        )`;
      case 'button':
        return `        Button(\n            onClick = { },\n            modifier = ${offsetMod}\n        ) {\n            Text("${label}", textAlign = ${composeAlign})\n        }`;
      case 'outlinedButton':
        return `        OutlinedButton(\n            onClick = { },\n            modifier = ${offsetMod}\n        ) {\n            Text("${label}", textAlign = ${composeAlign})\n        }`;
      case 'input':
        return `        OutlinedTextField(\n            value = "",\n            onValueChange = { },\n            label = { Text("${label}") },\n            modifier = ${offsetMod}\n        )`;
      case 'switch':
        return `        Row(modifier = ${offsetMod}) {\n            Text("${label}", textAlign = ${composeAlign}, color = ${hexToComposeColor(el.textColor)})\n            Switch(checked = ${el.checked ? 'true' : 'false'}, onCheckedChange = { })\n        }`;
      case 'progress':
        return `        LinearProgressIndicator(\n            progress = { ${((el.progress ?? 70) / 100).toFixed(2)}f },\n            modifier = ${offsetMod}\n        )`;
      case 'divider':
        return `        HorizontalDivider(modifier = ${offsetMod})`;
      case 'fab':
        return `        FloatingActionButton(onClick = { }, modifier = ${offsetMod}) {\n            Text("${label}")\n        }`;
      default:
        return `        Surface(\n            color = ${hexToComposeColor(el.fillColor)},\n            shape = RoundedCornerShape(${Math.min(el.borderRadius, 48)}.dp),\n            modifier = ${offsetMod}\n        ) {\n            ${el.text ? `Text("${label}", textAlign = ${composeAlign}, color = ${hexToComposeColor(el.textColor)})` : ''}\n        }`;
    }
  });

  return `package ${packageName}

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ${fnName}() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(${hexToComposeColor(screen.backgroundColor)})
    ) {
${bodyLines.join('\n')}
    }
}
`;
}

export function generateXmlFromScreen(screen: DesignerScreen): string {
  const visibleElements = screen.elements.filter((el) => el.visible);
  const children = visibleElements.map((el) => {
    const textAttr = escapeXmlString(el.text || el.placeholder || el.name);
    const idSlug = el.name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '') || 'view';
    const layoutPos = `android:layout_width="${el.width}dp"\n        android:layout_height="${el.height}dp"\n        android:layout_marginStart="${Math.max(0, el.x)}dp"\n        android:layout_marginTop="${Math.max(0, el.y)}dp"`;
    const gravity = toXmlGravity(el.textAlign);

    if (el.kind === 'heading' || el.kind === 'text' || el.kind === 'topBar') {
      return `    <TextView\n        android:id="@+id/${idSlug}"\n        ${layoutPos}\n        android:text="${textAttr}"\n        android:gravity="${gravity}"\n        android:textSize="${el.fontSize}sp"\n        android:textColor="${el.textColor}" />`;
    }
    if (el.kind === 'button' || el.kind === 'outlinedButton' || el.kind === 'fab') {
      return `    <Button\n        android:id="@+id/${idSlug}"\n        ${layoutPos}\n        android:text="${textAttr}"\n        android:gravity="${gravity}"\n        android:textColor="${el.textColor}" />`;
    }
    if (el.kind === 'input') {
      return `    <EditText\n        android:id="@+id/${idSlug}"\n        ${layoutPos}\n        android:gravity="${gravity}"\n        android:hint="${textAttr}" />`;
    }
    if (el.kind === 'switch') {
      return `    <Switch\n        android:id="@+id/${idSlug}"\n        ${layoutPos}\n        android:text="${textAttr}"\n        android:gravity="${gravity}"\n        android:checked="${el.checked ? 'true' : 'false'}" />`;
    }
    if (el.kind === 'progress') {
      return `    <ProgressBar\n        android:id="@+id/${idSlug}"\n        style="?android:attr/progressBarStyleHorizontal"\n        ${layoutPos}\n        android:progress="${el.progress ?? 70}" />`;
    }
    if (el.kind === 'image') {
      return `    <ImageView\n        android:id="@+id/${idSlug}"\n        ${layoutPos}\n        android:contentDescription="${textAttr}" />`;
    }
    return `    <View\n        android:id="@+id/${idSlug}"\n        ${layoutPos}\n        android:background="${el.fillColor === 'transparent' ? '#00000000' : el.fillColor}" />`;
  });

  return `<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="${screen.backgroundColor}">

${children.join('\n\n')}
</FrameLayout>
`;
}
