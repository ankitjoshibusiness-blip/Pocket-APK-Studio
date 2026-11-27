import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {
  importAllScreensFromFile,
  importFullProjectToDesigner,
} from './fullAppImportEngine';

test('importAllScreensFromFile extracts all pages, tabs, tiles, and dialogs from real native MainActivity.kt', () => {
  const mainKtPath = path.resolve(process.cwd(), 'app/src/main/java/com/pocketapkstudio/MainActivity.kt');
  const content = fs.readFileSync(mainKtPath, 'utf8');

  const bundle = importAllScreensFromFile({
    filePath: 'app/src/main/java/com/pocketapkstudio/MainActivity.kt',
    content,
  });

  const screenNames = bundle.screens.map((s) => s.name);
  assert.ok(bundle.screens.length >= 9, `Expected >= 9 screens/dialogs, got ${bundle.screens.length}: ${screenNames.join(', ')}`);

  // 5 main bottom-tab pages
  const home = bundle.screens.find((s) => s.name === 'Home');
  const workspace = bundle.screens.find((s) => s.name === 'Workspace');
  const tools = bundle.screens.find((s) => s.name === 'APK Tools');
  const preview = bundle.screens.find((s) => s.name === 'App Preview');
  const settings = bundle.screens.find((s) => s.name === 'Settings');
  assert.ok(home, `Missing Home screen in: ${screenNames.join(', ')}`);
  assert.ok(workspace, `Missing Workspace screen in: ${screenNames.join(', ')}`);
  assert.ok(tools, `Missing APK Tools screen in: ${screenNames.join(', ')}`);
  assert.ok(preview, `Missing App Preview screen in: ${screenNames.join(', ')}`);
  assert.ok(settings, `Missing Settings screen in: ${screenNames.join(', ')}`);

  // Exact root background color #202A33
  assert.equal(home.backgroundColor, '#202A33');

  // Home page has App Bar, white WORKSPACE hero card, all 6 welcomeMenuItem tiles, and 5-tab bottom bar
  const homeTexts = home.elements.map((e) => e.text).join(' | ');
  assert.ok(homeTexts.includes('Pocket APK Studio'), 'Home should render App Bar title');
  assert.ok(homeTexts.includes('Get started'), 'Home should render Get started heading');
  assert.ok(homeTexts.includes('WORKSPACE'), 'Home should render WORKSPACE hero card');
  assert.ok(homeTexts.includes('App Preview'), 'Home should render App Preview tile');
  assert.ok(homeTexts.includes('Clone git repository'), 'Home should render Clone git repository tile');
  assert.ok(homeTexts.includes('Terminal'), 'Home should render Terminal tile');
  assert.ok(homeTexts.includes('APK Tools'), 'Home should render APK Tools tile');
  assert.ok(homeTexts.includes('Documentation'), 'Home should render Documentation tile');
  assert.ok(homeTexts.includes('Preferences'), 'Home should render Preferences tile');

  const whiteHeroCard = home.elements.find((e) => e.kind === 'card' && e.fillColor === '#FFFFFF');
  assert.ok(whiteHeroCard, 'Home should preserve white #FFFFFF WORKSPACE hero card');

  const bottomNav = home.elements.find((e) => e.kind === 'bottomNav');
  assert.ok(bottomNav, 'Home should include bottomNav bar');
  assert.ok(bottomNav.text.includes('Workspace') && bottomNav.text.includes('Settings'), `Bottom nav should contain real tabs, got: ${bottomNav.text}`);

  // Workspace page has all 4 accordion tiles + inner action buttons
  const wsTexts = workspace.elements.map((e) => e.text).join(' | ');
  assert.ok(wsTexts.includes('01  PROJECT ACTIONS'), 'Workspace should render 01 PROJECT ACTIONS section');
  assert.ok(wsTexts.includes('02  PROJECT DETAILS'), 'Workspace should render 02 PROJECT DETAILS section');
  assert.ok(wsTexts.includes('03  BUILD APK'), 'Workspace should render 03 BUILD APK section');
  assert.ok(wsTexts.includes('04  BUILD LOGS'), 'Workspace should render 04 BUILD LOGS section');
  assert.ok(wsTexts.includes('Create Project'), 'Workspace should include Create Project button inside accordion');
  assert.ok(wsTexts.includes('Build Android APK'), 'Workspace should include Build Android APK button');

  // APK Tools page has Analyze Apk, Decompile Apk, and APK ANALYSIS REPORT
  const toolsTexts = tools.elements.map((e) => e.text).join(' | ');
  assert.ok(toolsTexts.includes('Analyze Apk'), 'APK Tools should include Analyze Apk');
  assert.ok(toolsTexts.includes('Decompile Apk'), 'APK Tools should include Decompile Apk');
  assert.ok(toolsTexts.includes('APK ANALYSIS REPORT'), 'APK Tools should include APK ANALYSIS REPORT card');

  // Environment Setup wizard page & Quick Start form dialog & Documentation dialog
  const envSetup = bundle.screens.find((s) => s.name === 'Environment Setup');
  const quickStart = bundle.screens.find((s) => s.name.includes('Quick Start'));
  const docsDlg = bundle.screens.find((s) => s.name.includes('Documentation'));
  assert.ok(envSetup, `Missing Environment Setup page in: ${screenNames.join(', ')}`);
  assert.ok(quickStart, `Missing Quick Start dialog window in: ${screenNames.join(', ')}`);
  assert.ok(docsDlg, `Missing Documentation dialog window in: ${screenNames.join(', ')}`);

  const qsTexts = quickStart.elements.map((e) => e.text || e.placeholder || '').join(' | ');
  assert.ok(qsTexts.includes('PROJECT NAME') && qsTexts.includes('Create Project'), 'Quick Start dialog should contain form fields and Create Project button');
});

test('importAllScreensFromFile extracts multiple @Composable screens and AlertDialog windows', () => {
  const composeSrc = `
package com.demo.multi

val BrandBlue = Color(0xFF2563EB)

@Composable
fun HomeTabScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TopAppBar(title = { Text("Dashboard") })
        Text(text = "Welcome Back", fontSize = 22.sp, color = BrandBlue)
        Button(onClick = {}) { Text("Start Now") }
    }
}

@Composable
fun ProfileTabScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Account Profile", fontSize = 20.sp)
        OutlinedTextField(value = "", onValueChange = {}, label = { Text("Email") })
        Button(onClick = {}) { Text("Save Profile") }
    }
}

@Composable
fun ConfirmExitDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Exit Workspace") },
        text = { Text("Unsaved changes will be kept locally.") },
        confirmButton = { Button(onClick = {}) { Text("Exit") } },
        dismissButton = { TextButton(onClick = {}) { Text("Stay") } }
    )
}
`;

  const bundle = importAllScreensFromFile({
    filePath: 'app/src/main/java/com/demo/multi/MainActivity.kt',
    content: composeSrc,
  });

  const names = bundle.screens.map((s) => s.name);
  assert.ok(names.includes('HomeTabScreen'), `Expected HomeTabScreen in ${names.join(', ')}`);
  assert.ok(names.includes('ProfileTabScreen'), `Expected ProfileTabScreen in ${names.join(', ')}`);
  assert.ok(names.some((n) => n.includes('Exit Workspace')), `Expected Exit Workspace dialog in ${names.join(', ')}`);
});

test('importFullProjectToDesigner aggregates screens across multiple native Activity files', () => {
  const mainKt = fs.readFileSync(
    path.resolve(process.cwd(), 'app/src/main/java/com/pocketapkstudio/MainActivity.kt'),
    'utf8'
  );
  const previewKt = fs.readFileSync(
    path.resolve(process.cwd(), 'app/src/main/java/com/pocketapkstudio/LivePreviewActivity.kt'),
    'utf8'
  );

  const bundle = importFullProjectToDesigner(
    [
      { path: 'app/src/main/java/com/pocketapkstudio/MainActivity.kt', content: mainKt },
      { path: 'app/src/main/java/com/pocketapkstudio/LivePreviewActivity.kt', content: previewKt },
    ],
    'app/src/main/java/com/pocketapkstudio/MainActivity.kt'
  );

  const names = bundle.screens.map((s) => s.name);
  assert.ok(bundle.screens.length >= 10, `Expected >= 10 full-app screens, got ${bundle.screens.length}`);
  assert.ok(names.includes('Home'), 'Full project should include MainActivity Home');
  assert.ok(names.includes('LivePreview'), `Full project should include LivePreviewActivity screen in: ${names.join(', ')}`);
});
