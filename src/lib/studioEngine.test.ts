import test from 'node:test';
import assert from 'node:assert/strict';
import {
  argbToCssRgba,
  createTemplateProject,
  defaultCopyRelativePath,
  findColorHexInColorsXml,
  parseUiPreview,
  readXmlAttribute,
  scanDiagnostics,
  writeXmlAttribute,
} from './studioEngine';

test('defaultCopyRelativePath handles root and nested paths', () => {
  assert.equal(defaultCopyRelativePath('settings.gradle.kts'), 'settings.gradle_copy.kts');
  assert.equal(defaultCopyRelativePath('app/src/main/MainActivity.kt'), 'app/src/main/MainActivity_copy.kt');
  assert.equal(defaultCopyRelativePath('README'), 'README_copy');
});

test('findColorHexInColorsXml resolves 6-digit and 8-digit ARGB colors', () => {
  const xml = `
    <resources>
      <color name="brand_primary">#4B9BA2</color>
      <color name="overlay_bg">#80112233</color>
    </resources>
  `;
  assert.equal(findColorHexInColorsXml(xml, 'brand_primary'), '#4B9BA2');
  assert.equal(findColorHexInColorsXml(xml, 'overlay_bg'), '#80112233');
  assert.equal(argbToCssRgba('80112233'), 'rgba(17, 34, 51, 0.502)');
  assert.equal(argbToCssRgba('FF4B9BA2'), '#4B9BA2');
});

test('readXmlAttribute and writeXmlAttribute update without duplication', () => {
  const xml = `<TextView android:text="Hello" android:textColor="#FFFFFF" />`;
  assert.equal(readXmlAttribute(xml, 'text'), 'Hello');
  const updated = writeXmlAttribute(xml, 'text', 'Updated');
  assert.equal(readXmlAttribute(updated, 'text'), 'Updated');
  assert.equal((updated.match(/android:text=/g) ?? []).length, 1);
});

test('scanDiagnostics handles Kotlin raw strings and nested block comments without false positives', () => {
  const validKt = `
    /* outer /* nested */ still comment */
    val script = """
      if [ "(" = ")" ]; then echo "ok { }"
    """
    fun ok() { println(script) }
  `;
  assert.equal(scanDiagnostics(validKt, 'MainActivity.kt').length, 0);

  const brokenKt = `fun broken() { val x = (1 + 2 }`;
  const issues = scanDiagnostics(brokenKt, 'Broken.kt');
  assert.ok(issues.length > 0);
});

test('parseUiPreview parses Compose and XML templates cleanly', () => {
  const composeProj = createTemplateProject('compose', 'Sample Studio', 'com.test.sample');
  const mainKt = composeProj.files.find((f) => f.path.endsWith('MainActivity.kt'))!;
  const cmpPreview = parseUiPreview(mainKt.content, mainKt.path);
  assert.equal(cmpPreview.sourceType, 'compose');
  assert.ok(cmpPreview.nodes.some((n) => n.kind === 'topBar' && n.text === 'Sample Studio'));
  assert.ok(cmpPreview.nodes.some((n) => n.kind === 'button' && n.text === 'Build Debug APK'));

  const kotlinProj = createTemplateProject('kotlin', 'Xml Demo', 'com.test.xml');
  const layoutXml = kotlinProj.files.find((f) => f.path.endsWith('activity_main.xml'))!;
  const colorsXml = kotlinProj.files.find((f) => f.path.endsWith('colors.xml'))!;
  const xmlPreview = parseUiPreview(layoutXml.content, layoutXml.path, colorsXml.content);
  assert.equal(xmlPreview.sourceType, 'xml');
  assert.ok(xmlPreview.nodes.some((n) => n.kind === 'text' && n.text === 'Xml Demo' && n.textColor === '#4B9BA2'));
});
