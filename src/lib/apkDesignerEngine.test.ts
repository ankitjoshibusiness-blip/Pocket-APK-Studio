import test from 'node:test';
import assert from 'node:assert/strict';
import {
  alignElementInCanvas,
  applyColorCombinationToScreen,
  createDesignerElement,
  createStarterDesignerScreen,
  generateComposeFromScreen,
  generateXmlFromScreen,
  moveElementWithSnap,
  reorderLayer,
  resizeElementFromHandle,
} from './apkDesignerEngine';
import { COLOR_COMBINATIONS } from './colorUtils';
import { scanDiagnostics } from './studioEngine';

test('createStarterDesignerScreen creates valid elements for dashboard and login presets', () => {
  const dash = createStarterDesignerScreen('dashboard');
  assert.ok(dash.elements.length >= 6);
  const login = createStarterDesignerScreen('login');
  assert.ok(login.elements.some((el) => el.kind === 'button' && el.text === 'Sign In'));
});

test('moveElementWithSnap snaps to canvas horizontal center and sets smart guide', () => {
  const screen = createStarterDesignerScreen('blank', 400, 800);
  const btn = { ...createDesignerElement('button', 400, 800), width: 200, height: 48 };
  // Center X is (400 - 200) / 2 = 100. Passing 104 should snap to 100.
  const moved = moveElementWithSnap(btn, 104, 200, screen, false);
  assert.equal(moved.x, 100);
  assert.equal(moved.guides.verticalCenter, true);
  assert.equal(moved.guides.verticalX, 200);
});

test('resizeElementFromHandle handles nw and se resizing with min size clamping', () => {
  const box = { x: 100, y: 100, width: 120, height: 80 };
  const expanded = resizeElementFromHandle(box, 'se', 40, 20, false);
  assert.deepEqual(expanded, { x: 100, y: 100, width: 160, height: 100 });

  const shrunkNw = resizeElementFromHandle(box, 'nw', 200, 200, false);
  assert.equal(shrunkNw.width, 24);
  assert.equal(shrunkNw.height, 2);
  assert.equal(shrunkNw.x, 196);
});

test('alignElementInCanvas and reorderLayer update element position and z-order', () => {
  const screen = createStarterDesignerScreen('blank', 390, 800);
  const a = createDesignerElement('heading', 390, 800);
  const b = createDesignerElement('button', 390, 800);
  const centered = alignElementInCanvas({ ...a, width: 190 }, 'centerX', screen);
  assert.equal(centered.x, 100);

  const movedUp = reorderLayer([a, b], a.id, 'up');
  assert.equal(movedUp[0].id, b.id);
  assert.equal(movedUp[1].id, a.id);
});

test('generateComposeFromScreen and generateXmlFromScreen export textAlign cleanly', () => {
  const screen = createStarterDesignerScreen('dashboard');
  screen.elements = screen.elements.map((el) =>
    el.kind === 'heading' ? { ...el, textAlign: 'right' } : el
  );
  const composeCode = generateComposeFromScreen(screen, 'com.pocketapkstudio.demo');
  assert.ok(composeCode.includes('fun HomeDashboard()'));
  assert.ok(composeCode.includes('Button('));
  assert.ok(composeCode.includes('textAlign = TextAlign.End'));
  assert.ok(composeCode.includes('textAlign = TextAlign.Center'));
  assert.equal(scanDiagnostics(composeCode, 'HomeDashboard.kt').length, 0);

  const xmlCode = generateXmlFromScreen(screen);
  assert.ok(xmlCode.includes('<FrameLayout'));
  assert.ok(xmlCode.includes('android:text="Build APK"'));
  assert.ok(xmlCode.includes('android:gravity="end"'));
  assert.ok(xmlCode.includes('android:gravity="center"'));
  assert.equal(scanDiagnostics(xmlCode, 'activity_home_dashboard.xml').length, 0);
});

test('applyColorCombinationToScreen harmonizes screen background and UI elements', () => {
  const screen = createStarterDesignerScreen('dashboard');
  const daylight = COLOR_COMBINATIONS.find((c) => c.id === 'crisp-daylight')!;
  const styled = applyColorCombinationToScreen(screen, daylight);
  assert.equal(styled.backgroundColor, daylight.background);
  const btn = styled.elements.find((el) => el.kind === 'button')!;
  assert.equal(btn.fillColor, daylight.accent);
  assert.equal(btn.textColor, daylight.onAccent);
  const heading = styled.elements.find((el) => el.kind === 'heading')!;
  assert.equal(heading.textColor, daylight.text);
});
