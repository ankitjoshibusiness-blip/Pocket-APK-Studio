import test from 'node:test';
import assert from 'node:assert/strict';
import {
  importCodeToDesignerScreen,
  listImportableUiFiles,
} from './codeToDesignerEngine';
import { createTemplateProject } from './studioEngine';
import {
  createStarterDesignerScreen,
  generateComposeFromScreen,
  generateXmlFromScreen,
} from './apkDesignerEngine';

test('importCodeToDesignerScreen faithfully imports Jetpack Compose (non-XML) colors, sizes, alignment and layout', () => {
  const composeCode = `
package com.example.demo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val BrandCoral = Color(0xFFFF5A5F)

@Composable
fun ProfileScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopAppBar(title = { Text("My Profile") })
            Text(
                text = "Ankit Studio",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = BrandCoral
            )
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Text(text = "Bio details", fontSize = 14.sp, color = Color(0xFFCBD5E1))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = BrandCoral, contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Save")
                }
                OutlinedButton(onClick = {}) {
                    Text("Cancel")
                }
            }
        }
    }
}
`;
  const res = importCodeToDesignerScreen({
    filePath: 'app/src/main/java/com/example/demo/MainActivity.kt',
    content: composeCode,
  });

  assert.equal(res.sourceKind, 'compose');
  assert.equal(res.screen.backgroundColor, '#0F172A');
  assert.ok(res.importedCount >= 6);

  const heading = res.screen.elements.find((e) => e.text === 'Ankit Studio')!;
  assert.ok(heading);
  assert.equal(heading.fontSize, 24);
  assert.equal(heading.fontWeight, '700');
  assert.equal(heading.textAlign, 'center');
  assert.equal(heading.textColor, '#FF5A5F');

  const card = res.screen.elements.find((e) => e.kind === 'card')!;
  assert.equal(card.fillColor, '#1E293B');
  assert.equal(card.borderRadius, 20);

  const saveBtn = res.screen.elements.find((e) => e.kind === 'button' && e.text === 'Save')!;
  const cancelBtn = res.screen.elements.find((e) => e.kind === 'outlinedButton' && e.text === 'Cancel')!;
  assert.equal(saveBtn.fillColor, '#FF5A5F');
  assert.equal(saveBtn.borderRadius, 16);
  // Side-by-side Row layout: Cancel button sits to the right of Save button at same Y
  assert.equal(saveBtn.y, cancelBtn.y);
  assert.ok(cancelBtn.x > saveBtn.x);
});

test('importCodeToDesignerScreen round-trips Designer Compose & XML exports with exact coordinates', () => {
  const starter = createStarterDesignerScreen('dashboard');
  const composeExport = generateComposeFromScreen(starter, 'com.example.roundtrip');
  const fromCompose = importCodeToDesignerScreen({
    filePath: 'app/src/main/java/com/example/roundtrip/HomeDashboard.kt',
    content: composeExport,
  });
  assert.equal(fromCompose.sourceKind, 'compose');
  const btn = fromCompose.screen.elements.find((e) => e.kind === 'button' && e.text === 'Build APK')!;
  assert.ok(btn);
  assert.equal(btn.x, 36);
  assert.equal(btn.y, 220);
  assert.equal(btn.width, 152);
  assert.equal(btn.height, 40);

  const xmlExport = generateXmlFromScreen(starter);
  const fromXml = importCodeToDesignerScreen({
    filePath: 'app/src/main/res/layout/activity_home_dashboard.xml',
    content: xmlExport,
  });
  assert.equal(fromXml.sourceKind, 'xml');
  const xmlBtn = fromXml.screen.elements.find((e) => e.kind === 'button' && e.text === 'Build APK')!;
  assert.ok(xmlBtn);
  assert.equal(xmlBtn.x, 36);
  assert.equal(xmlBtn.y, 220);
  assert.equal(xmlBtn.width, 152);
  assert.equal(xmlBtn.height, 40);
});

test('importCodeToDesignerScreen resolves @color, @string, @drawable shapes and setContentView(R.layout.*)', () => {
  const projectFiles = [
    {
      path: 'app/src/main/res/values/colors.xml',
      content: `<resources><color name="bg_night">#111827</color><color name="accent_gold">#F59E0B</color></resources>`,
    },
    {
      path: 'app/src/main/res/values/strings.xml',
      content: `<resources><string name="hero_title">Welcome Builder</string></resources>`,
    },
    {
      path: 'app/src/main/res/drawable/card_bg.xml',
      content: `<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="#1F2937"/><corners android:radius="18dp"/><stroke android:width="2dp" android:color="@color/accent_gold"/></shape>`,
    },
    {
      path: 'app/src/main/res/layout/activity_main.xml',
      content: `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="20dp"
    android:background="@color/bg_night">
    <TextView
        android:id="@+id/title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/hero_title"
        android:textSize="22sp"
        android:textStyle="bold"
        android:gravity="center"
        android:textColor="@color/accent_gold" />
    <Button
        android:id="@+id/startBtn"
        android:layout_width="match_parent"
        android:layout_height="52dp"
        android:text="Launch"
        android:background="@drawable/card_bg" />
</LinearLayout>`,
    },
  ];

  const mainKt = `
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
`;

  const result = importCodeToDesignerScreen({
    filePath: 'app/src/main/java/com/demo/MainActivity.kt',
    content: mainKt,
    projectFiles,
  });

  assert.equal(result.sourceKind, 'xml');
  assert.equal(result.screen.backgroundColor, '#111827');
  const title = result.screen.elements.find((e) => e.text === 'Welcome Builder')!;
  assert.ok(title);
  assert.equal(title.textColor, '#F59E0B');
  assert.equal(title.textAlign, 'center');
  assert.equal(title.fontSize, 22);

  const btn = result.screen.elements.find((e) => e.text === 'Launch')!;
  assert.equal(btn.fillColor, '#1F2937');
  assert.equal(btn.borderRadius, 18);
  assert.equal(btn.strokeColor, '#F59E0B');
});

test('importCodeToDesignerScreen imports programmatic Kotlin Android Views without XML', () => {
  const progKt = `
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val root = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#141C24"))
            addView(TextView(this@MainActivity).apply {
                text = "Pocket Native"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(0xFF4B9BA2.toInt())
            })
            addView(Button(this@MainActivity).apply {
                text = "Compile Now"
                setBackgroundColor(Color.parseColor("#3E8790"))
            })
        }
    }
}
`;
  const res = importCodeToDesignerScreen({
    filePath: 'app/src/main/java/com/demo/MainActivity.kt',
    content: progKt,
  });

  assert.equal(res.sourceKind, 'kotlin-views');
  assert.equal(res.screen.backgroundColor, '#141C24');
  assert.equal(res.importedCount, 2);
  assert.equal(res.screen.elements[0].text, 'Pocket Native');
  assert.equal(res.screen.elements[0].textAlign, 'center');
  assert.equal(res.screen.elements[0].textColor, '#4B9BA2');
  assert.equal(res.screen.elements[1].text, 'Compile Now');
});

test('listImportableUiFiles discovers Compose and XML UI files in a project', () => {
  const project = createTemplateProject('compose', 'Demo App', 'com.demo.app');
  const importable = listImportableUiFiles(project.files);
  assert.ok(importable.some((f) => f.path.endsWith('MainActivity.kt')));
});
