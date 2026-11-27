import React, { useRef, useState } from 'react';
import JSZip from 'jszip';
import {
  CheckCircle2,
  Copy,
  Download,
  FolderGit2,
  FolderOpen,
  Play,
  Plus,
  XCircle,
} from 'lucide-react';
import {
  createTemplateProject,
  type ApkAnalysisReport,
  type ProjectFile,
  type StudioProject,
} from '../lib/studioEngine';

interface BuildStepResult {
  label: string;
  ok: boolean;
  durationMs: number;
  output: string;
}

interface WorkspaceBuildViewProps {
  project: StudioProject;
  apkInfo: ApkAnalysisReport | null;
  onReplaceProject: (next: StudioProject) => void;
  onOpenEditor: () => void;
  onLoadNativeRepo: () => void;
  loadingNativeRepo: boolean;
  onRefreshApkInfo: () => Promise<void>;
}

const TEMPLATES: Array<{
  id: 'compose' | 'kotlin' | 'java' | 'webview';
  label: string;
  desc: string;
}> = [
  { id: 'compose', label: 'Jetpack Compose', desc: 'Material 3 Kotlin screen with live interactive controls' },
  { id: 'kotlin', label: 'Kotlin + XML Layout', desc: 'Classic Activity with Android XML layout and colors.xml' },
  { id: 'java', label: 'Java Activity', desc: 'Standard Java Android Activity and XML layout' },
  { id: 'webview', label: 'WebView App', desc: 'Java WebView wrapper with valid Android imports' },
];

export const WorkspaceBuildView: React.FC<WorkspaceBuildViewProps> = ({
  project,
  apkInfo,
  onReplaceProject,
  onOpenEditor,
  onLoadNativeRepo,
  loadingNativeRepo,
  onRefreshApkInfo,
}) => {
  const [template, setTemplate] = useState<'compose' | 'kotlin' | 'java' | 'webview'>('compose');
  const [appName, setAppName] = useState('Pocket Demo App');
  const [packageName, setPackageName] = useState('com.pocketstudio.demo');
  const [formError, setFormError] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string>('Ready to create, import, or run native Android build checks.');
  const [building, setBuilding] = useState(false);
  const [buildOk, setBuildOk] = useState<boolean | null>(null);
  const [buildLog, setBuildLog] = useState<string>('Build and unit test output will appear here.');
  const [buildDurationMs, setBuildDurationMs] = useState<number | null>(null);
  const [copiedLogs, setCopiedLogs] = useState(false);
  const zipInputRef = useRef<HTMLInputElement | null>(null);

  const handleCreateProject = (e: React.FormEvent) => {
    e.preventDefault();
    if (!appName.trim()) {
      setFormError('Enter an application name.');
      return;
    }
    if (!/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(packageName.trim())) {
      setFormError('Enter a valid Android package name (e.g. com.example.myapp).');
      return;
    }
    setFormError(null);
    const created = createTemplateProject(template, appName.trim(), packageName.trim());
    onReplaceProject(created);
    setStatusMessage(`Created "${created.name}" (${created.files.length} files).`);
    onOpenEditor();
  };

  const handleImportZip = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const zip = await JSZip.loadAsync(file);
      const loadedFiles: ProjectFile[] = [];
      const entries = Object.values(zip.files);
      for (const entry of entries) {
        if (entry.dir) continue;
        if (!/\.(kt|java|xml|gradle|kts|properties|sh|json|md|txt)$/i.test(entry.name)) continue;
        const text = await entry.async('string');
        loadedFiles.push({ path: entry.name.replace(/^\/+/, ''), content: text });
      }
      if (loadedFiles.length === 0) {
        setStatusMessage('No editable Android source files were found inside that ZIP.');
        return;
      }
      const next: StudioProject = {
        id: `zip-${Date.now()}`,
        name: file.name.replace(/\.zip$/i, ''),
        packageName: 'imported.android.project',
        template: 'imported',
        updatedAt: new Date().toISOString(),
        files: loadedFiles.sort((a, b) => a.path.localeCompare(b.path)),
      };
      onReplaceProject(next);
      setStatusMessage(`Imported ${loadedFiles.length} source files from ${file.name}.`);
      onOpenEditor();
    } catch (err) {
      setStatusMessage(`ZIP import failed: ${err instanceof Error ? err.message : 'invalid archive'}`);
    } finally {
      e.target.value = '';
    }
  };

  const handleExportZip = async () => {
    const zip = new JSZip();
    for (const f of project.files) {
      zip.file(f.path, f.content);
    }
    const blob = await zip.generateAsync({ type: 'blob' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${project.name.replace(/[^a-zA-Z0-9_-]+/g, '_')}.zip`;
    a.click();
    URL.revokeObjectURL(url);
    setStatusMessage(`Exported ${project.files.length} files to ZIP.`);
  };

  const handleRunNativeBuild = async (mode: 'verify' | 'assemble') => {
    setBuilding(true);
    setBuildOk(null);
    setBuildDurationMs(null);
    setBuildLog(
      mode === 'assemble'
        ? 'Running build-project.sh --self-test, gradle testDebugUnitTest, and gradle assembleDebug…'
        : 'Running build-project.sh --self-test and gradle testDebugUnitTest…'
    );
    try {
      const resp = await fetch('/api/build/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode }),
      });
      const data = (await resp.json()) as {
        ok: boolean;
        durationMs: number;
        steps: BuildStepResult[];
        error?: string;
      };
      if (!resp.ok) {
        setBuildOk(false);
        setBuildLog(data.error || 'Build endpoint returned an error.');
        return;
      }
      setBuildOk(data.ok);
      setBuildDurationMs(data.durationMs);
      const combined = data.steps
        .map(
          (step, idx) =>
            `[${idx + 1}/${data.steps.length}] ${step.ok ? 'PASS' : 'FAIL'} — ${step.label} (${(
              step.durationMs / 1000
            ).toFixed(1)}s)\n${step.output}`
        )
        .join('\n\n');
      setBuildLog(combined || 'Completed with no output.');
      setStatusMessage(
        data.ok
          ? mode === 'assemble'
            ? 'Native APK built and verified cleanly.'
            : 'All build-engine and Android unit tests passed.'
          : 'Build check reported an error — inspect logs below.'
      );
      await onRefreshApkInfo();
    } catch (err) {
      setBuildOk(false);
      setBuildLog(`Build request failed: ${err instanceof Error ? err.message : 'network error'}`);
    } finally {
      setBuilding(false);
    }
  };

  const handleCopyLogs = async () => {
    try {
      await navigator.clipboard.writeText(buildLog);
      setCopiedLogs(true);
      setTimeout(() => setCopiedLogs(false), 1800);
    } catch {
      // clipboard unavailable
    }
  };

  return (
    <div className="max-w-6xl mx-auto px-6 py-7 space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-display font-bold text-[#EEF4F6]">Project Workspace & Build</h1>
          <p className="text-xs text-[#9BB0BC] mt-0.5">{statusMessage}</p>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <input
            ref={zipInputRef}
            type="file"
            accept=".zip,application/zip"
            className="hidden"
            onChange={handleImportZip}
          />
          <button
            type="button"
            onClick={() => zipInputRef.current?.click()}
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#1A2530] hover:bg-[#22313F] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] whitespace-nowrap cursor-pointer"
          >
            <FolderOpen className="w-3.5 h-3.5 text-[#78A5A8]" />
            <span>Import Project ZIP</span>
          </button>
          <button
            type="button"
            onClick={handleExportZip}
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#1A2530] hover:bg-[#22313F] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] whitespace-nowrap cursor-pointer"
          >
            <Download className="w-3.5 h-3.5 text-[#78A5A8]" />
            <span>Export Edited ZIP</span>
          </button>
          <button
            type="button"
            onClick={onLoadNativeRepo}
            disabled={loadingNativeRepo}
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#1A2530] hover:bg-[#22313F] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] whitespace-nowrap cursor-pointer disabled:opacity-60"
          >
            <FolderGit2 className="w-3.5 h-3.5 text-[#78A5A8]" />
            <span>{loadingNativeRepo ? 'Loading…' : 'Load Native Repo'}</span>
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Create Project Form */}
        <section className="lg:col-span-5 rounded-xl border border-[#253542] bg-[#141D26] p-5 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-display font-semibold text-[#EEF4F6]">01. Create Android Project</h2>
            <span className="text-xs text-[#89A0AD]">4 templates</span>
          </div>

          <form onSubmit={handleCreateProject} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              {TEMPLATES.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setTemplate(item.id)}
                  className={`text-left p-3 rounded-lg border transition-colors cursor-pointer ${
                    template === item.id
                      ? 'bg-[#1D2D39] border-[#4B9BA2] text-[#EEF4F6]'
                      : 'bg-[#10171F] border-[#253542] text-[#B3C4CE] hover:border-[#354B5C]'
                  }`}
                >
                  <p className="text-xs font-semibold">{item.label}</p>
                  <p className="text-[11px] text-[#89A0AD] mt-0.5 leading-snug">{item.desc}</p>
                </button>
              ))}
            </div>

            <div className="space-y-3">
              <label className="block space-y-1">
                <span className="text-xs font-medium text-[#B3C4CE]">Application name</span>
                <input
                  type="text"
                  value={appName}
                  onChange={(e) => setAppName(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-[#0F151C] border border-[#2A3B48] text-sm text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
                  placeholder="My Android App"
                />
              </label>

              <label className="block space-y-1">
                <span className="text-xs font-medium text-[#B3C4CE]">Package name</span>
                <input
                  type="text"
                  value={packageName}
                  onChange={(e) => setPackageName(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-[#0F151C] border border-[#2A3B48] text-sm font-mono text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
                  placeholder="com.example.myapp"
                />
              </label>
            </div>

            {formError && (
              <p role="alert" className="text-xs text-[#EF4444]">
                {formError}
              </p>
            )}

            <button
              type="submit"
              className="w-full inline-flex items-center justify-center gap-2 py-2.5 px-4 rounded-lg bg-[#3E8790] hover:bg-[#35767E] text-sm font-semibold text-white transition-colors whitespace-nowrap cursor-pointer"
            >
              <Plus className="w-4 h-4" />
              <span>Create & Open in Editor</span>
            </button>
          </form>
        </section>

        {/* Real Native Build & Verification Runner */}
        <section className="lg:col-span-7 rounded-xl border border-[#253542] bg-[#141D26] p-5 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-sm font-display font-semibold text-[#EEF4F6]">02. Native Android Build & Tests</h2>
              <p className="text-xs text-[#89A0AD] mt-0.5">
                Runs the real repository Gradle tasks and build-project.sh self-tests
              </p>
            </div>

            {buildOk !== null && (
              <div className={`inline-flex items-center gap-1.5 text-xs font-medium ${buildOk ? 'text-[#22C55E]' : 'text-[#EF4444]'}`}>
                {buildOk ? <CheckCircle2 className="w-4 h-4" /> : <XCircle className="w-4 h-4" />}
                <span>
                  {buildOk ? 'Build Passed' : 'Build Failed'}
                  {buildDurationMs ? ` · ${(buildDurationMs / 1000).toFixed(1)}s` : ''}
                </span>
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            <button
              type="button"
              disabled={building}
              onClick={() => handleRunNativeBuild('verify')}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-[#1F2F3C] hover:bg-[#283B4A] border border-[#32495A] text-xs font-semibold text-[#EEF4F6] transition-colors whitespace-nowrap cursor-pointer disabled:opacity-50"
            >
              <Play className="w-3.5 h-3.5 text-[#78A5A8]" />
              <span>{building ? 'Running…' : 'Run Unit Tests & Self-Tests'}</span>
            </button>

            <button
              type="button"
              disabled={building}
              onClick={() => handleRunNativeBuild('assemble')}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-[#3E8790] hover:bg-[#35767E] text-xs font-semibold text-white transition-colors whitespace-nowrap cursor-pointer disabled:opacity-50"
            >
              <Play className="w-3.5 h-3.5" />
              <span>{building ? 'Building APK…' : 'Assemble Debug APK'}</span>
            </button>

            {apkInfo && (
              <a
                href="/api/apk/download"
                className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#19242E] hover:bg-[#21303D] border border-[#2C3E4C] text-xs font-medium text-[#DCE7EC] whitespace-nowrap"
              >
                <Download className="w-3.5 h-3.5 text-[#78A5A8]" />
                <span>Download APK</span>
              </a>
            )}
          </div>

          {/* Live Log Console */}
          <div className="rounded-lg border border-[#22313D] bg-[#0B1016] overflow-hidden">
            <div className="flex items-center justify-between px-3 py-2 border-b border-[#1E2B36] text-xs text-[#89A0AD]">
              <span>Build Logs</span>
              <button
                type="button"
                onClick={handleCopyLogs}
                className="inline-flex items-center gap-1.5 text-xs text-[#9BB0BC] hover:text-white cursor-pointer"
              >
                <Copy className="w-3.5 h-3.5" />
                <span>{copiedLogs ? 'Copied' : 'Copy logs'}</span>
              </button>
            </div>
            <pre className="p-3.5 text-xs font-mono text-[#C8D7DF] overflow-auto max-h-[340px] whitespace-pre-wrap leading-relaxed">
              {buildLog}
            </pre>
          </div>
        </section>
      </div>
    </div>
  );
};
