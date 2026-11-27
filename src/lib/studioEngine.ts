export interface ProjectFile {
  path: string;
  content: string;
}

export interface StudioProject {
  id: string;
  name: string;
  packageName: string;
  template: 'compose' | 'kotlin' | 'java' | 'webview' | 'native-repo' | 'imported';
  updatedAt: string;
  files: ProjectFile[];
}

export interface DiagnosticItem {
  line: number;
  severity: 'error' | 'warning';
  message: string;
}

export interface PreviewNode {
  id: string;
  kind:
    | 'column'
    | 'row'
    | 'card'
    | 'topBar'
    | 'text'
    | 'button'
    | 'outlinedButton'
    | 'textField'
    | 'switch'
    | 'checkbox'
    | 'slider'
    | 'progress'
    | 'radioGroup'
    | 'divider'
    | 'spacer';
  text?: string;
  hint?: string;
  textColor?: string;
  backgroundColor?: string;
  fontSize?: number;
  bold?: boolean;
  checked?: boolean;
  value?: number;
  options?: string[];
  children?: PreviewNode[];
}

export interface PreviewParseResult {
  sourceType: 'xml' | 'compose' | 'none';
  screenTitle: string;
  nodes: PreviewNode[];
  warnings: string[];
}

export interface ApkAnalysisReport {
  fileName: string;
  sizeBytes: number;
  packageName: string;
  versionName: string;
  versionCode: string;
  minSdk: string;
  targetSdk: string;
  applicationLabel: string;
  signingVerified: boolean;
  signingSchemes: string[];
  permissions: string[];
  entrySummary: {
    totalEntries: number;
    dexCount: number;
    resCount: number;
    assetCount: number;
    libCount: number;
    sampleEntries: string[];
  };
  updatedAt?: string;
}

export interface AiPatchSuggestion {
  reply: string;
  patchLabel?: string;
  targetPath?: string;
  updatedContent?: string;
}

export function defaultCopyRelativePath(relativePath: string): string {
  const clean = relativePath.replace(/^\/+/, '');
  const slashIdx = clean.lastIndexOf('/');
  const dir = slashIdx >= 0 ? clean.slice(0, slashIdx) : '';
  const name = slashIdx >= 0 ? clean.slice(slashIdx + 1) : clean;
  const dotIdx = name.lastIndexOf('.');
  const copyName =
    dotIdx > 0
      ? `${name.slice(0, dotIdx)}_copy${name.slice(dotIdx)}`
      : `${name}_copy`;
  return dir ? `${dir}/${copyName}` : copyName;
}

export function findColorHexInColorsXml(colorsXml: string, colorName: string): string | null {
  const escaped = colorName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const regex = new RegExp(
    `<color\\s+name=["']${escaped}["'][^>]*>\\s*(#[0-9a-fA-F]{6,8})\\s*</color>`,
    'i'
  );
  const match = colorsXml.match(regex);
  return match ? match[1] : null;
}

export function normalizeAndroidHexColor(raw: string | undefined, colorsXml = ''): string | undefined {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (trimmed.startsWith('@color/')) {
    const resolved = findColorHexInColorsXml(colorsXml, trimmed.slice('@color/'.length));
    return resolved ? normalizeAndroidHexColor(resolved) : undefined;
  }
  const composeHex = trimmed.match(/Color\(\s*0x([0-9a-fA-F]{8})\s*\)/);
  if (composeHex) {
    return argbToCssRgba(composeHex[1]);
  }
  if (/^#[0-9a-fA-F]{6}$/.test(trimmed)) {
    return trimmed;
  }
  if (/^#[0-9a-fA-F]{8}$/.test(trimmed)) {
    return argbToCssRgba(trimmed.slice(1));
  }
  return undefined;
}

export function argbToCssRgba(argbHex: string): string {
  const clean = argbHex.replace(/^#/, '');
  if (clean.length !== 8) return `#${clean.slice(-6)}`;
  const a = parseInt(clean.slice(0, 2), 16) / 255;
  const r = parseInt(clean.slice(2, 4), 16);
  const g = parseInt(clean.slice(4, 6), 16);
  const b = parseInt(clean.slice(6, 8), 16);
  if (a >= 0.996) {
    return `#${clean.slice(2)}`;
  }
  return `rgba(${r}, ${g}, ${b}, ${Number(a.toFixed(3))})`;
}

export function readXmlAttribute(xml: string, attrName: string): string | null {
  const escaped = attrName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const regex = new RegExp(`(?:^|[\\s<])(?:[a-zA-Z0-9_]+:)?${escaped}\\s*=\\s*"([^"]*)"`);
  const match = xml.match(regex);
  return match ? match[1] : null;
}

export function writeXmlAttribute(xml: string, attrName: string, value: string): string {
  const escaped = attrName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const regex = new RegExp(`((?:^|[\\s<])(?:[a-zA-Z0-9_]+:)?${escaped}\\s*=\\s*)"[^"]*"`);
  if (regex.test(xml)) {
    return xml.replace(regex, `$1"${value}"`);
  }
  return xml.replace(/>/, `\n    android:${attrName}="${value}">`);
}

export function scanDiagnostics(content: string, filePath: string): DiagnosticItem[] {
  const ext = filePath.split('.').pop()?.toLowerCase() ?? '';
  if (ext === 'xml') {
    return scanXmlDiagnostics(content);
  }
  if (['kt', 'kts', 'java', 'gradle', 'js', 'ts'].includes(ext)) {
    return scanCodeDiagnostics(content, ext === 'kt' || ext === 'kts');
  }
  return [];
}

function scanXmlDiagnostics(content: string): DiagnosticItem[] {
  const items: DiagnosticItem[] = [];
  const stack: Array<{ tag: string; line: number }> = [];
  let line = 1;
  let i = 0;

  while (i < content.length) {
    if (content[i] === '\n') {
      line++;
      i++;
      continue;
    }
    if (content.startsWith('<!--', i)) {
      const end = content.indexOf('-->', i + 4);
      if (end === -1) {
        items.push({ line, severity: 'error', message: 'Unclosed XML comment (missing -->).' });
        break;
      }
      line += content.slice(i, end + 3).split('\n').length - 1;
      i = end + 3;
      continue;
    }
    if (content.startsWith('<?', i)) {
      const end = content.indexOf('?>', i + 2);
      if (end === -1) {
        items.push({ line, severity: 'error', message: 'Unclosed XML processing instruction.' });
        break;
      }
      i = end + 2;
      continue;
    }
    if (content[i] === '<') {
      const startLine = line;
      const closeIdx = content.indexOf('>', i + 1);
      if (closeIdx === -1) {
        items.push({ line: startLine, severity: 'error', message: 'Unclosed XML tag (missing >).' });
        break;
      }
      const rawTag = content.slice(i + 1, closeIdx).trim();
      if (rawTag.startsWith('/')) {
        const tagName = rawTag.slice(1).trim().split(/\s+/)[0];
        const top = stack.pop();
        if (!top) {
          items.push({ line: startLine, severity: 'error', message: `Unexpected closing tag </${tagName}>.` });
        } else if (top.tag !== tagName) {
          items.push({
            line: startLine,
            severity: 'error',
            message: `Mismatched closing tag </${tagName}>; expected </${top.tag}> opened at line ${top.line}.`,
          });
        }
      } else if (!rawTag.endsWith('/') && !rawTag.startsWith('!')) {
        const tagName = rawTag.split(/\s+/)[0];
        if (tagName) stack.push({ tag: tagName, line: startLine });
      }
      line += content.slice(i, closeIdx + 1).split('\n').length - 1;
      i = closeIdx + 1;
      continue;
    }
    i++;
  }

  for (const unclosed of stack) {
    items.push({ line: unclosed.line, severity: 'error', message: `Unclosed XML tag <${unclosed.tag}>.` });
  }
  return items;
}

function scanCodeDiagnostics(content: string, isKotlin: boolean): DiagnosticItem[] {
  const items: DiagnosticItem[] = [];
  const stack: Array<{ ch: string; line: number }> = [];
  const matching: Record<string, string> = { ')': '(', '}': '{', ']': '[' };
  let line = 1;
  let i = 0;
  let blockCommentDepth = 0;
  let inLineComment = false;
  let inRawString = false;
  let inString = false;
  let inChar = false;

  while (i < content.length) {
    const ch = content[i];
    const next = content[i + 1] ?? '';

    if (ch === '\n') {
      if (inString) {
        items.push({ line, severity: 'error', message: 'Unclosed string literal.' });
        inString = false;
      }
      if (inChar) {
        items.push({ line, severity: 'error', message: 'Unclosed character literal.' });
        inChar = false;
      }
      inLineComment = false;
      line++;
      i++;
      continue;
    }

    if (inLineComment) {
      i++;
      continue;
    }

    if (blockCommentDepth > 0) {
      if (isKotlin && ch === '/' && next === '*') {
        blockCommentDepth++;
        i += 2;
      } else if (ch === '*' && next === '/') {
        blockCommentDepth--;
        i += 2;
      } else {
        i++;
      }
      continue;
    }

    if (inRawString) {
      if (content.startsWith('"""', i)) {
        inRawString = false;
        i += 3;
      } else {
        i++;
      }
      continue;
    }

    if (inString) {
      if (ch === '\\') {
        i += 2;
        continue;
      }
      if (ch === '"') inString = false;
      i++;
      continue;
    }

    if (inChar) {
      if (ch === '\\') {
        i += 2;
        continue;
      }
      if (ch === "'") inChar = false;
      i++;
      continue;
    }

    if (ch === '/' && next === '/') {
      inLineComment = true;
      i += 2;
      continue;
    }
    if (ch === '/' && next === '*') {
      blockCommentDepth = 1;
      i += 2;
      continue;
    }
    if (isKotlin && content.startsWith('"""', i)) {
      inRawString = true;
      i += 3;
      continue;
    }
    if (ch === '"') {
      inString = true;
      i++;
      continue;
    }
    if (ch === "'") {
      inChar = true;
      i++;
      continue;
    }

    if (ch === '(' || ch === '{' || ch === '[') {
      stack.push({ ch, line });
    } else if (ch === ')' || ch === '}' || ch === ']') {
      const top = stack.pop();
      if (!top) {
        items.push({ line, severity: 'error', message: `Unexpected closing '${ch}'.` });
      } else if (top.ch !== matching[ch]) {
        items.push({
          line,
          severity: 'error',
          message: `Mismatched '${ch}' for '${top.ch}' opened at line ${top.line}.`,
        });
      }
    }
    i++;
  }

  if (inRawString) {
    items.push({ line, severity: 'error', message: 'Unclosed Kotlin raw string (""").' });
  }
  if (blockCommentDepth > 0) {
    items.push({ line, severity: 'error', message: 'Unclosed block comment (/* ... */).' });
  }
  for (const unclosed of stack) {
    items.push({ line: unclosed.line, severity: 'error', message: `Unclosed '${unclosed.ch}'.` });
  }
  return items;
}

export function parseUiPreview(
  content: string,
  filePath: string,
  colorsXml = ''
): PreviewParseResult {
  const lower = filePath.toLowerCase();
  if (lower.endsWith('.xml') && (lower.includes('/layout/') || /<(?:LinearLayout|FrameLayout|ConstraintLayout|ScrollView|RelativeLayout|androidx\.|TextView|Button)/.test(content))) {
    return parseXmlLayout(content, colorsXml);
  }
  if (lower.endsWith('.kt') && (/@Composable/.test(content) || /\b(Column|Row|Card|Surface|Text|Button|TextField|Switch|Checkbox|Slider|TopAppBar)\s*\(/.test(content))) {
    return parseComposeSource(content, filePath, colorsXml);
  }
  return {
    sourceType: 'none',
    screenTitle: filePath.split('/').pop() ?? 'Preview',
    nodes: [],
    warnings: ['Open an Android XML layout or @Composable Kotlin file to inspect the live UI preview.'],
  };
}

function parseXmlLayout(xml: string, colorsXml: string): PreviewParseResult {
  const nodes: PreviewNode[] = [];
  const warnings: string[] = [];
  const tagRegex = /<([A-Za-z0-9_.]+)([^>]*?)(\/?)>/g;
  let idCounter = 1;
  let match: RegExpExecArray | null;

  while ((match = tagRegex.exec(xml)) !== null) {
    const rawTag = match[1];
    const attrs = match[2] ?? '';
    const tag = rawTag.split('.').pop() ?? rawTag;
    const text = readXmlAttribute(attrs, 'text') ?? undefined;
    const hint = readXmlAttribute(attrs, 'hint') ?? undefined;
    const textColor = normalizeAndroidHexColor(readXmlAttribute(attrs, 'textColor') ?? undefined, colorsXml);
    const backgroundColor = normalizeAndroidHexColor(readXmlAttribute(attrs, 'background') ?? undefined, colorsXml);
    const sizeAttr = readXmlAttribute(attrs, 'textSize');
    const fontSize = sizeAttr ? parseFloat(sizeAttr) : undefined;
    const bold = (readXmlAttribute(attrs, 'textStyle') ?? '').includes('bold');

    const id = `xml-${idCounter++}`;
    if (tag === 'TextView') {
      nodes.push({ id, kind: 'text', text: text ?? 'TextView', textColor, backgroundColor, fontSize, bold });
    } else if (tag === 'Button' || tag === 'MaterialButton') {
      nodes.push({ id, kind: 'button', text: text ?? 'Button', textColor, backgroundColor });
    } else if (tag === 'EditText' || tag === 'TextInputEditText') {
      nodes.push({ id, kind: 'textField', text: text ?? '', hint: hint ?? 'Enter text…' });
    } else if (tag === 'Switch' || tag === 'SwitchCompat' || tag === 'SwitchMaterial') {
      nodes.push({ id, kind: 'switch', text: text ?? 'Toggle option', checked: readXmlAttribute(attrs, 'checked') === 'true' });
    } else if (tag === 'CheckBox') {
      nodes.push({ id, kind: 'checkbox', text: text ?? 'Check option', checked: readXmlAttribute(attrs, 'checked') === 'true' });
    } else if (tag === 'SeekBar') {
      const progress = Number(readXmlAttribute(attrs, 'progress') ?? 50);
      nodes.push({ id, kind: 'slider', text: 'Value', value: Number.isFinite(progress) ? progress : 50 });
    } else if (tag === 'ProgressBar') {
      const progress = Number(readXmlAttribute(attrs, 'progress') ?? 64);
      nodes.push({ id, kind: 'progress', value: Number.isFinite(progress) ? progress : 64 });
    } else if (tag === 'RadioGroup') {
      nodes.push({ id, kind: 'radioGroup', options: ['Option A', 'Option B', 'Option C'] });
    }
  }

  if (nodes.length === 0) {
    warnings.push('No renderable Android Views found in this XML file.');
  }

  return {
    sourceType: 'xml',
    screenTitle: 'XML Layout Preview',
    nodes,
    warnings,
  };
}

function extractQuotedText(segment: string): string | undefined {
  const named = segment.match(/(?:text|label|title)\s*=\s*"([^"]*)"/);
  if (named) return named[1];
  const first = segment.match(/"([^"]*)"/);
  return first ? first[1] : undefined;
}

function parseComposeSource(source: string, filePath: string, colorsXml: string): PreviewParseResult {
  const nodes: PreviewNode[] = [];
  const warnings: string[] = [];
  const fnMatch = source.match(/@Composable[\s\S]*?fun\s+([A-Za-z0-9_]+)/);
  const screenTitle = fnMatch ? fnMatch[1] : filePath.split('/').pop()?.replace(/\.kt$/, '') ?? 'Compose Preview';

  const tokenRegex =
    /\b(TopAppBar|CenterAlignedTopAppBar|Card|ElevatedCard|OutlinedCard|Text|Button|ElevatedButton|OutlinedButton|OutlinedTextField|TextField|Switch|Checkbox|Slider|LinearProgressIndicator|HorizontalDivider|Divider|RadioButton)\s*\(/g;

  let idCounter = 1;
  let match: RegExpExecArray | null;
  const radioOptions: string[] = [];

  while ((match = tokenRegex.exec(source)) !== null) {
    const widget = match[1];
    const startIdx = match.index + match[0].length;
    let depth = 1;
    let endIdx = startIdx;
    while (endIdx < source.length && depth > 0) {
      if (source[endIdx] === '(') depth++;
      else if (source[endIdx] === ')') depth--;
      endIdx++;
    }
    const args = source.slice(startIdx, Math.max(startIdx, endIdx - 1));
    const afterArgs = source.slice(endIdx, endIdx + 180);
    const id = `cmp-${idCounter++}`;

    if (widget === 'TopAppBar' || widget === 'CenterAlignedTopAppBar') {
      const title = extractQuotedText(args) ?? screenTitle;
      nodes.push({ id, kind: 'topBar', text: title });
    } else if (widget === 'Text') {
      const text = extractQuotedText(args);
      if (!text) continue;
      const colorMatch = args.match(/color\s*=\s*(Color\(\s*0x[0-9a-fA-F]{8}\s*\))/);
      const fontSizeMatch = args.match(/fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp/);
      const isBold = /FontWeight\.(Bold|SemiBold|ExtraBold)/.test(args) || /headline|title/.test(args);
      nodes.push({
        id,
        kind: 'text',
        text,
        textColor: colorMatch ? normalizeAndroidHexColor(colorMatch[1], colorsXml) : undefined,
        fontSize: fontSizeMatch ? Number(fontSizeMatch[1]) : undefined,
        bold: isBold,
      });
    } else if (widget === 'Button' || widget === 'ElevatedButton') {
      const blockMatch = afterArgs.match(/^\s*\{([\s\S]*?)\}/);
      const label = extractQuotedText(blockMatch ? blockMatch[1] : args) ?? 'Action';
      nodes.push({ id, kind: 'button', text: label });
    } else if (widget === 'OutlinedButton') {
      const blockMatch = afterArgs.match(/^\s*\{([\s\S]*?)\}/);
      const label = extractQuotedText(blockMatch ? blockMatch[1] : args) ?? 'Secondary';
      nodes.push({ id, kind: 'outlinedButton', text: label });
    } else if (widget === 'TextField' || widget === 'OutlinedTextField') {
      const hint = extractQuotedText(args) ?? 'Type here…';
      nodes.push({ id, kind: 'textField', hint, text: '' });
    } else if (widget === 'Switch') {
      const checked = /checked\s*=\s*true/.test(args);
      nodes.push({ id, kind: 'switch', text: 'Enable setting', checked });
    } else if (widget === 'Checkbox') {
      const checked = /checked\s*=\s*true/.test(args);
      nodes.push({ id, kind: 'checkbox', text: 'Accept option', checked });
    } else if (widget === 'Slider') {
      const valMatch = args.match(/value\s*=\s*([0-9.]+)f?/);
      const rawVal = valMatch ? Number(valMatch[1]) : 0.6;
      const pct = rawVal <= 1 ? Math.round(rawVal * 100) : Math.min(100, Math.round(rawVal));
      nodes.push({ id, kind: 'slider', text: 'Slider value', value: pct });
    } else if (widget === 'LinearProgressIndicator') {
      const valMatch = args.match(/progress\s*=\s*\{?\s*([0-9.]+)f?/);
      const pct = valMatch ? Math.round(Number(valMatch[1]) * 100) : 72;
      nodes.push({ id, kind: 'progress', value: pct });
    } else if (widget === 'HorizontalDivider' || widget === 'Divider') {
      nodes.push({ id, kind: 'divider' });
    } else if (widget === 'RadioButton') {
      radioOptions.push(`Option ${radioOptions.length + 1}`);
    }
  }

  if (radioOptions.length > 0) {
    nodes.push({ id: `cmp-radio-group`, kind: 'radioGroup', options: radioOptions });
  }

  if (nodes.length === 0) {
    warnings.push('No previewable Compose widgets were matched in this file.');
  }

  return {
    sourceType: 'compose',
    screenTitle,
    nodes,
    warnings,
  };
}

export function createTemplateProject(
  template: 'compose' | 'kotlin' | 'java' | 'webview',
  appName: string,
  packageName: string
): StudioProject {
  const safeName = appName.trim() || 'My Android App';
  const safePkg = packageName.trim() || 'com.example.myapp';
  const pkgPath = safePkg.replace(/\./g, '/');

  const settingsGradle = `pluginManagement {\n    repositories {\n        google()\n        mavenCentral()\n        gradlePluginPortal()\n    }\n}\n\ndependencyResolutionManagement {\n    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n    repositories {\n        google()\n        mavenCentral()\n    }\n}\n\nrootProject.name = "${safeName.replace(/"/g, '')}"\ninclude(":app")\n`;

  const colorsXml = `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="brand_primary">#4B9BA2</color>\n    <color name="surface_dark">#151E27</color>\n    <color name="text_primary">#EEF4F6</color>\n    <color name="text_muted">#9BB0BC</color>\n    <color name="accent_translucent">#CC4B9BA2</color>\n</resources>\n`;

  const manifestXml = `<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <uses-permission android:name="android.permission.INTERNET" />\n    <application\n        android:label="${safeName}"\n        android:supportsRtl="true">\n        <activity\n            android:name=".MainActivity"\n            android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n                <category android:name="android.intent.category.LAUNCHER" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>\n`;

  const files: ProjectFile[] = [
    { path: 'settings.gradle.kts', content: settingsGradle },
    { path: 'app/src/main/AndroidManifest.xml', content: manifestXml },
    { path: 'app/src/main/res/values/colors.xml', content: colorsXml },
  ];

  if (template === 'compose') {
    files.unshift({
      path: `app/src/main/java/${pkgPath}/MainActivity.kt`,
      content: `package ${safePkg}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HomeDashboardScreen()
            }
        }
    }
}

@Composable
fun HomeDashboardScreen() {
    var projectTitle by remember { mutableStateOf("${safeName}") }
    var autoBuild by remember { mutableStateOf(true) }
    var targetScale by remember { mutableFloatStateOf(0.75f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TopAppBar(title = { Text("${safeName}") })
        Text(
            text = "Ready for Android 16 (SDK 36)",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4B9BA2)
        )
        Text(
            text = "Edit any text, button, or color in this file to see instant updates inside the Live UI Preview.",
            fontSize = 14.sp
        )
        OutlinedTextField(
            value = projectTitle,
            onValueChange = { projectTitle = it },
            label = { Text("App display title") }
        )
        Switch(checked = autoBuild, onCheckedChange = { autoBuild = it })
        Slider(value = targetScale, onValueChange = { targetScale = it })
        LinearProgressIndicator(progress = { 0.82f })
        HorizontalDivider()
        Button(onClick = { /* build action */ }) {
            Text("Build Debug APK")
        }
        OutlinedButton(onClick = { /* inspect */ }) {
            Text("Inspect Manifest")
        }
    }
}
`,
    });
  } else if (template === 'kotlin') {
    files.unshift(
      {
        path: 'app/src/main/res/layout/activity_main.xml',
        content: `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="20dp">

    <TextView
        android:id="@+id/titleText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="${safeName}"
        android:textSize="22sp"
        android:textStyle="bold"
        android:textColor="@color/brand_primary" />

    <TextView
        android:id="@+id/subtitleText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="XML View hierarchy rendered live in Pocket APK Studio."
        android:textSize="14sp" />

    <EditText
        android:id="@+id/inputField"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Enter package label" />

    <Switch
        android:id="@+id/darkSwitch"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Enable release shrinking"
        android:checked="true" />

    <CheckBox
        android:id="@+id/signCheck"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Sign with debug keystore"
        android:checked="true" />

    <Button
        android:id="@+id/primaryButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Continue" />
</LinearLayout>
`,
      },
      {
        path: `app/src/main/java/${pkgPath}/MainActivity.kt`,
        content: `package ${safePkg}

import android.app.Activity
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
`,
      }
    );
  } else if (template === 'java') {
    files.unshift(
      {
        path: 'app/src/main/res/layout/activity_main.xml',
        content: `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="20dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="${safeName} (Java)"
        android:textSize="22sp"
        android:textStyle="bold" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Classic Java Android Activity starter template."
        android:textSize="14sp" />

    <ProgressBar
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:progress="68" />

    <Button
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Launch Action" />
</LinearLayout>
`,
      },
      {
        path: `app/src/main/java/${pkgPath}/MainActivity.java`,
        content: `package ${safePkg};

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
`,
      }
    );
  } else {
    files.unshift({
      path: `app/src/main/java/${pkgPath}/MainActivity.java`,
      content: `package ${safePkg};

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("https://developer.android.com");
        setContentView(webView);
    }
}
`,
    });
  }

  return {
    id: `proj-${Date.now()}`,
    name: safeName,
    packageName: safePkg,
    template,
    updatedAt: new Date().toISOString(),
    files,
  };
}

export function generateAiResponse(
  prompt: string,
  activeFile: ProjectFile | undefined,
  project: StudioProject
): AiPatchSuggestion {
  const lower = prompt.toLowerCase();
  if (!activeFile) {
    return {
      reply: `Project "${project.name}" has ${project.files.length} files. Select a Kotlin, Java, or XML file in the editor and ask me to inspect diagnostics, add a Compose widget, or update XML attributes.`,
    };
  }

  const diags = scanDiagnostics(activeFile.content, activeFile.path);
  if (lower.includes('error') || lower.includes('bug') || lower.includes('check') || lower.includes('fix')) {
    if (diags.length === 0) {
      return {
        reply: `Scanned ${activeFile.path}: 0 syntax or delimiter errors found. Ready for Gradle compilation.`,
      };
    }
    const list = diags.map((d) => `Line ${d.line}: ${d.message}`).join('\n');
    return {
      reply: `Found ${diags.length} diagnostic issue(s) in ${activeFile.path}:\n${list}`,
    };
  }

  if ((lower.includes('button') || lower.includes('cta')) && activeFile.path.endsWith('.kt')) {
    const updated = activeFile.content.replace(
      /(\n\s*\}\s*\n\s*\}\s*$|\n\s*\}\s*$)/,
      `\n        Button(onClick = { /* export */ }) {\n            Text("Export Release APK")\n        }$1`
    );
    return {
      reply: `Prepared a Jetpack Compose Button patch for ${activeFile.path}. Click Apply Patch to insert it and refresh the live phone preview.`,
      patchLabel: 'Insert Compose Button',
      targetPath: activeFile.path,
      updatedContent: updated,
    };
  }

  if ((lower.includes('button') || lower.includes('text')) && activeFile.path.endsWith('.xml')) {
    const updated = activeFile.content.replace(
      /<\/LinearLayout>/,
      `    <Button\n        android:id="@+id/quickActionBtn"\n        android:layout_width="match_parent"\n        android:layout_height="wrap_content"\n        android:text="Run Quick Check" />\n</LinearLayout>`
    );
    return {
      reply: `Prepared an XML Button patch for ${activeFile.path}. Click Apply Patch to add it directly to your layout.`,
      patchLabel: 'Insert XML Button',
      targetPath: activeFile.path,
      updatedContent: updated,
    };
  }

  return {
    reply: `Active file: ${activeFile.path} (${activeFile.content.split('\n').length} lines, ${diags.length} diagnostics). Try asking "Check for errors", "Add a button", or edit code directly to see instant Live UI Preview updates.`,
  };
}
