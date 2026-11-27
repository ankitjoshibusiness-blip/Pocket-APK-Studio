import React, { useCallback, useEffect, useState } from 'react';
import {
  Code2,
  Hammer,
  Home,
  PackageSearch,
  PenTool,
  Settings,
} from 'lucide-react';
import {
  createTemplateProject,
  type ApkAnalysisReport,
  type ProjectFile,
  type StudioProject,
} from './lib/studioEngine';
import { HomeView, type StudioTab } from './components/HomeView';
import { WorkspaceBuildView } from './components/WorkspaceBuildView';
import { ApkDesignerView } from './components/ApkDesignerView';
import { CodeEditorView } from './components/CodeEditorView';
import { ApkToolsView } from './components/ApkToolsView';
import { EnvironmentSetupView } from './components/EnvironmentSetupView';

const STORAGE_KEY = 'pocket_apk_studio_project_v1';

function loadInitialProject(): StudioProject {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as StudioProject;
      if (parsed && Array.isArray(parsed.files) && parsed.files.length > 0) {
        return parsed;
      }
    }
  } catch {
    // ignore storage errors
  }
  return createTemplateProject('compose', 'Pocket Studio Demo', 'com.pocketapkstudio.demo');
}

const NAV_ITEMS: Array<{ id: StudioTab; label: string; shortLabel: string; icon: React.ComponentType<{ className?: string }> }> = [
  { id: 'home', label: 'Home', shortLabel: 'Home', icon: Home },
  { id: 'workspace', label: 'Workspace', shortLabel: 'Build', icon: Hammer },
  { id: 'designer', label: 'APK Designer', shortLabel: 'Design', icon: PenTool },
  { id: 'editor', label: 'Editor', shortLabel: 'Code', icon: Code2 },
  { id: 'apk-tools', label: 'APK Tools', shortLabel: 'APK', icon: PackageSearch },
  { id: 'settings', label: 'Settings', shortLabel: 'Setup', icon: Settings },
];

export const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<StudioTab>('home');
  const [project, setProject] = useState<StudioProject>(loadInitialProject);
  const [activeFilePath, setActiveFilePath] = useState<string>(() => project.files[0]?.path ?? '');
  const [apkInfo, setApkInfo] = useState<ApkAnalysisReport | null>(null);
  const [loadingNativeRepo, setLoadingNativeRepo] = useState(false);

  const [editorFontSize, setEditorFontSize] = useState(13);
  const [liveDiagnostics, setLiveDiagnostics] = useState(true);
  const [defaultPreviewTheme, setDefaultPreviewTheme] = useState<'dark' | 'light'>('dark');

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(project));
    } catch {
      // ignore storage quota
    }
  }, [project]);

  const fetchApkInfo = useCallback(async () => {
    try {
      const res = await fetch('/api/apk/info');
      if (!res.ok) return;
      const data = (await res.json()) as ApkAnalysisReport & { exists?: boolean };
      if (data.exists) {
        setApkInfo(data);
      }
    } catch {
      // ignore offline
    }
  }, []);

  useEffect(() => {
    void fetchApkInfo();
  }, [fetchApkInfo]);

  const handleLoadNativeRepo = useCallback(async () => {
    setLoadingNativeRepo(true);
    try {
      const res = await fetch('/api/repo/files');
      if (!res.ok) return;
      const data = (await res.json()) as {
        projectName: string;
        packageName: string;
        files: ProjectFile[];
      };
      if (data.files?.length) {
        const next: StudioProject = {
          id: 'native-repo',
          name: data.projectName,
          packageName: data.packageName,
          template: 'native-repo',
          updatedAt: new Date().toISOString(),
          files: data.files,
        };
        setProject(next);
        const preferred =
          data.files.find((f) => f.path.endsWith('MainActivity.kt'))?.path ?? data.files[0].path;
        setActiveFilePath(preferred);
        setActiveTab('editor');
      }
    } finally {
      setLoadingNativeRepo(false);
    }
  }, []);

  const handleReplaceProject = (next: StudioProject) => {
    setProject(next);
    setActiveFilePath(next.files[0]?.path ?? '');
  };

  const handleUpdateFile = (path: string, content: string) => {
    setProject((prev) => ({
      ...prev,
      updatedAt: new Date().toISOString(),
      files: prev.files.map((f) => (f.path === path ? { ...f, content } : f)),
    }));
  };

  const handleAddFile = (path: string, content: string) => {
    setProject((prev) => {
      if (prev.files.some((f) => f.path === path)) return prev;
      return {
        ...prev,
        updatedAt: new Date().toISOString(),
        files: [...prev.files, { path, content }].sort((a, b) => a.path.localeCompare(b.path)),
      };
    });
  };

  const handleDeleteFile = (path: string) => {
    setProject((prev) => {
      const remaining = prev.files.filter((f) => f.path !== path);
      if (remaining.length === 0) return prev;
      if (activeFilePath === path) {
        setActiveFilePath(remaining[0].path);
      }
      return {
        ...prev,
        updatedAt: new Date().toISOString(),
        files: remaining,
      };
    });
  };

  const handleResetWorkspace = () => {
    const fresh = createTemplateProject('compose', 'Pocket Studio Demo', 'com.pocketapkstudio.demo');
    setProject(fresh);
    setActiveFilePath(fresh.files[0]?.path ?? '');
    setActiveTab('editor');
  };

  const handleExportDesignToProject = (
    exportedFiles: Array<{ path: string; content: string }>,
    openPath: string
  ) => {
    setProject((prev) => {
      const map = new Map(prev.files.map((f) => [f.path, f]));
      for (const ef of exportedFiles) {
        map.set(ef.path, ef);
      }
      return {
        ...prev,
        updatedAt: new Date().toISOString(),
        files: Array.from(map.values()).sort((a, b) => a.path.localeCompare(b.path)),
      };
    });
    setActiveFilePath(openPath);
    setActiveTab('editor');
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#0F151C] text-[#EEF4F6]">
      {/* Strict 3-Zone Top Bar */}
      <header className="h-14 px-5 border-b border-[#243441] bg-[#131C24] flex items-center justify-between gap-8 shrink-0">
        <a
          href="#home"
          onClick={(e) => {
            e.preventDefault();
            setActiveTab('home');
          }}
          className="font-display font-bold text-base tracking-tight text-[#F1F6F8] whitespace-nowrap shrink-0"
        >
          Pocket APK Studio
        </a>

        <nav aria-label="Primary navigation" className="hidden md:flex items-center gap-6 text-sm font-medium">
          {NAV_ITEMS.map((item) => {
            const active = activeTab === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setActiveTab(item.id)}
                className={`py-1 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                  active
                    ? 'text-[#F1F6F8] border-b-2 border-[#4B9BA2]'
                    : 'text-[#9BB0BC] hover:text-[#EEF4F6]'
                }`}
              >
                {item.label}
              </button>
            );
          })}
        </nav>

        <div className="flex items-center shrink-0">
          <button
            type="button"
            onClick={() => setActiveTab('workspace')}
            className="px-4 py-2 rounded-lg bg-[#3E8790] hover:bg-[#35767E] text-xs font-semibold text-white transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            Build APK
          </button>
        </div>
      </header>

      {/* Main Content */}
      <main
        className={`flex-1 min-h-0 ${
          activeTab === 'designer' || activeTab === 'editor' ? 'overflow-hidden' : 'pb-16 md:pb-0'
        }`}
      >
        {activeTab === 'home' && (
          <HomeView
            project={project}
            apkInfo={apkInfo}
            onNavigate={setActiveTab}
            onLoadNativeRepo={handleLoadNativeRepo}
            loadingNativeRepo={loadingNativeRepo}
          />
        )}

        {activeTab === 'workspace' && (
          <WorkspaceBuildView
            project={project}
            apkInfo={apkInfo}
            onReplaceProject={handleReplaceProject}
            onOpenEditor={() => setActiveTab('editor')}
            onLoadNativeRepo={handleLoadNativeRepo}
            loadingNativeRepo={loadingNativeRepo}
            onRefreshApkInfo={fetchApkInfo}
          />
        )}

        {activeTab === 'designer' && (
          <ApkDesignerView
            packageName={project.packageName}
            onExportCodeToProject={handleExportDesignToProject}
          />
        )}

        {activeTab === 'editor' && (
          <CodeEditorView
            project={project}
            activePath={activeFilePath}
            onSelectPath={setActiveFilePath}
            onUpdateFile={handleUpdateFile}
            onAddFile={handleAddFile}
            onDeleteFile={handleDeleteFile}
            editorFontSize={editorFontSize}
            liveDiagnostics={liveDiagnostics}
            defaultPreviewTheme={defaultPreviewTheme}
          />
        )}

        {activeTab === 'apk-tools' && (
          <ApkToolsView builtApkInfo={apkInfo} onRefreshBuiltApk={fetchApkInfo} />
        )}

        {activeTab === 'settings' && (
          <EnvironmentSetupView
            editorFontSize={editorFontSize}
            onChangeFontSize={setEditorFontSize}
            liveDiagnostics={liveDiagnostics}
            onToggleDiagnostics={setLiveDiagnostics}
            defaultPreviewTheme={defaultPreviewTheme}
            onChangePreviewTheme={setDefaultPreviewTheme}
            onResetWorkspace={handleResetWorkspace}
          />
        )}
      </main>

      {/* Mobile Bottom Navigation Bar */}
      <nav
        aria-label="Mobile navigation"
        className="md:hidden fixed bottom-0 inset-x-0 h-14 bg-[#131C24] border-t border-[#243441] grid grid-cols-6 z-20"
      >
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon;
          const active = activeTab === item.id;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => setActiveTab(item.id)}
              className={`flex flex-col items-center justify-center gap-0.5 text-[10px] font-medium whitespace-nowrap cursor-pointer ${
                active ? 'text-[#78A5A8]' : 'text-[#89A0AD]'
              }`}
            >
              <Icon className="w-4 h-4" />
              <span>{item.shortLabel}</span>
            </button>
          );
        })}
      </nav>
    </div>
  );
};

export default App;
