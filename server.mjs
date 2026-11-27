import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const PORT = Number(process.env.PORT_APP || 3000);
const ROOT_DIR = process.cwd();
const DIST_DIR = path.join(ROOT_DIR, 'dist');
const APK_CANDIDATES = [
  path.join(ROOT_DIR, '.build-outputs', 'app-debug.apk'),
  path.join(ROOT_DIR, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
];

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.apk': 'application/vnd.android.package-archive',
};

function findBuiltApk() {
  for (const candidate of APK_CANDIDATES) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      return candidate;
    }
  }
  return null;
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readRepoSourceFiles() {
  const files = [];
  const allowedRoots = [
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle.properties',
    '.github/workflows/android-build.yml',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
  ];

  const addFileIfExists = (relPath) => {
    const absPath = path.join(ROOT_DIR, relPath);
    if (!fs.existsSync(absPath) || !fs.statSync(absPath).isFile()) return;
    const stat = fs.statSync(absPath);
    if (stat.size > 350_000) return;
    files.push({
      path: relPath.replace(/\\/g, '/'),
      content: fs.readFileSync(absPath, 'utf8'),
    });
  };

  for (const rel of allowedRoots) addFileIfExists(rel);

  const walkDir = (dirRel) => {
    const absDir = path.join(ROOT_DIR, dirRel);
    if (!fs.existsSync(absDir)) return;
    for (const entry of fs.readdirSync(absDir, { withFileTypes: true })) {
      const childRel = path.posix.join(dirRel, entry.name);
      if (entry.isDirectory()) {
        walkDir(childRel);
      } else if (/\.(kt|java|xml|sh|gradle|kts|properties|md)$/i.test(entry.name)) {
        addFileIfExists(childRel);
      }
    }
  };

  walkDir('app/src/main/java');
  walkDir('app/src/main/res');
  walkDir('app/src/main/assets');
  walkDir('app/src/test/java');

  const deduped = new Map();
  for (const f of files) deduped.set(f.path, f);
  return Array.from(deduped.values()).sort((a, b) => a.path.localeCompare(b.path));
}

async function inspectBuiltApk() {
  const apkPath = findBuiltApk();
  if (!apkPath) {
    return {
      exists: false,
      error: 'No compiled app-debug.apk found yet.',
    };
  }

  const stat = fs.statSync(apkPath);
  const info = {
    exists: true,
    fileName: path.basename(apkPath),
    sizeBytes: stat.size,
    updatedAt: stat.mtime.toISOString(),
    packageName: 'com.pocketapkstudio',
    versionName: '1.6',
    versionCode: '5',
    minSdk: '26',
    targetSdk: '36',
    applicationLabel: 'Pocket Apk Studio',
    permissions: [],
    signingVerified: false,
    signingSchemes: [],
    entrySummary: {
      totalEntries: 0,
      dexCount: 0,
      resCount: 0,
      assetCount: 0,
      libCount: 0,
      sampleEntries: [],
    },
  };

  const aapt2 = '/opt/android/sdk/build-tools/36.0.0/aapt2';
  const apksigner = '/opt/android/sdk/build-tools/36.0.0/apksigner';

  if (fs.existsSync(aapt2)) {
    try {
      const { stdout } = await execFileAsync(aapt2, ['dump', 'badging', apkPath], { maxBuffer: 4 * 1024 * 1024 });
      const pkgMatch = stdout.match(/package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'/);
      if (pkgMatch) {
        info.packageName = pkgMatch[1];
        info.versionCode = pkgMatch[2];
        info.versionName = pkgMatch[3];
      }
      const minMatch = stdout.match(/minSdkVersion:'([^']+)'/);
      if (minMatch) info.minSdk = minMatch[1];
      const targetMatch = stdout.match(/targetSdkVersion:'([^']+)'/);
      if (targetMatch) info.targetSdk = targetMatch[1];
      const labelMatch = stdout.match(/application-label:'([^']+)'/);
      if (labelMatch) info.applicationLabel = labelMatch[1];
      const perms = new Set();
      for (const m of stdout.matchAll(/uses-permission:\s+name='([^']+)'/g)) {
        perms.add(m[1]);
      }
      info.permissions = Array.from(perms);
    } catch {
      // keep fallback metadata
    }
  }

  if (fs.existsSync(apksigner)) {
    try {
      const { stdout } = await execFileAsync(apksigner, ['verify', '--verbose', apkPath], { maxBuffer: 1024 * 1024 });
      info.signingVerified = stdout.includes('Verifies');
      const schemes = [];
      for (const m of stdout.matchAll(/Verified using (v[\d.]+) scheme.*:\s*true/g)) {
        schemes.push(m[1]);
      }
      info.signingSchemes = schemes;
    } catch {
      info.signingVerified = false;
    }
  }

  try {
    const { stdout } = await execFileAsync('unzip', ['-Z1', apkPath], { maxBuffer: 8 * 1024 * 1024 });
    const entries = stdout.split('\n').map((s) => s.trim()).filter(Boolean);
    info.entrySummary.totalEntries = entries.length;
    info.entrySummary.dexCount = entries.filter((e) => e.endsWith('.dex')).length;
    info.entrySummary.resCount = entries.filter((e) => e.startsWith('res/')).length;
    info.entrySummary.assetCount = entries.filter((e) => e.startsWith('assets/')).length;
    info.entrySummary.libCount = entries.filter((e) => e.startsWith('lib/')).length;
    info.entrySummary.sampleEntries = entries.slice(0, 60);
  } catch {
    // ignore unzip errors
  }

  return info;
}

let activeBuildPromise = null;

async function runNativeBuildCheck(mode) {
  if (activeBuildPromise) return activeBuildPromise;
  activeBuildPromise = (async () => {
    const startedAt = Date.now();
    const steps = [];

    const runStep = async (label, cmd, args) => {
      const t0 = Date.now();
      try {
        const { stdout, stderr } = await execFileAsync(cmd, args, {
          cwd: ROOT_DIR,
          maxBuffer: 8 * 1024 * 1024,
          timeout: 180_000,
        });
        steps.push({
          label,
          ok: true,
          durationMs: Date.now() - t0,
          output: `${stdout}${stderr ? `\n${stderr}` : ''}`.trim(),
        });
        return true;
      } catch (err) {
        const out = `${err.stdout || ''}\n${err.stderr || ''}\n${err.message || ''}`.trim();
        steps.push({
          label,
          ok: false,
          durationMs: Date.now() - t0,
          output: out,
        });
        return false;
      }
    };

    const selfTestOk = await runStep(
      'Validate local APK build engine (build-project.sh --self-test)',
      'bash',
      ['app/src/main/assets/build-project.sh', '--self-test']
    );

    let unitTestsOk = true;
    let assembleOk = true;

    if (selfTestOk) {
      unitTestsOk = await runStep(
        'Run Android JVM unit tests (gradle testDebugUnitTest)',
        'gradle',
        ['testDebugUnitTest', '--console=plain']
      );
    }

    if (selfTestOk && unitTestsOk && mode === 'assemble') {
      assembleOk = await runStep(
        'Assemble signed Debug APK (gradle assembleDebug)',
        'gradle',
        ['assembleDebug', '--console=plain']
      );
    }

    const apk = await inspectBuiltApk();
    return {
      ok: selfTestOk && unitTestsOk && assembleOk,
      mode,
      durationMs: Date.now() - startedAt,
      steps,
      apk,
    };
  })().finally(() => {
    activeBuildPromise = null;
  });

  return activeBuildPromise;
}

const server = http.createServer(async (req, res) => {
  try {
    const reqUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const pathname = decodeURIComponent(reqUrl.pathname);

    if (pathname === '/api/health') {
      return sendJson(res, 200, { ok: true, mode: 'hybrid-android-web-preview' });
    }

    if (pathname === '/api/repo/files' && req.method === 'GET') {
      const files = readRepoSourceFiles();
      return sendJson(res, 200, {
        projectName: 'Pocket APK Studio (Native Repo)',
        packageName: 'com.pocketapkstudio',
        files,
      });
    }

    if (pathname === '/api/apk/info' && req.method === 'GET') {
      const info = await inspectBuiltApk();
      return sendJson(res, 200, info);
    }

    if (pathname === '/api/apk/download' && req.method === 'GET') {
      const apkPath = findBuiltApk();
      if (!apkPath) {
        return sendJson(res, 404, { error: 'Compiled APK not found.' });
      }
      const stat = fs.statSync(apkPath);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="PocketAPKStudio-debug.apk"',
        'Content-Length': stat.size,
      });
      fs.createReadStream(apkPath).pipe(res);
      return;
    }

    if (pathname === '/api/build/verify' && req.method === 'POST') {
      let body = '';
      for await (const chunk of req) body += chunk;
      let mode = 'verify';
      try {
        const parsed = JSON.parse(body || '{}');
        if (parsed.mode === 'assemble') mode = 'assemble';
      } catch {
        // ignore invalid body
      }
      const result = await runNativeBuildCheck(mode);
      return sendJson(res, 200, result);
    }

    // Serve static Vite build from dist/
    const safePath = path.normalize(pathname).replace(/^(\.\.(\/|\\|$))+/, '');
    let filePath = path.join(DIST_DIR, safePath === '/' ? 'index.html' : safePath);

    if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
      filePath = path.join(DIST_DIR, 'index.html');
    }

    if (!fs.existsSync(filePath)) {
      res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Web preview build not ready yet.');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    res.writeHead(200, {
      'Content-Type': contentType,
      'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=31536000, immutable',
    });
    fs.createReadStream(filePath).pipe(res);
  } catch (err) {
    sendJson(res, 500, { error: err instanceof Error ? err.message : 'Internal server error' });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Pocket APK Studio web preview running on http://0.0.0.0:${PORT}`);
});
