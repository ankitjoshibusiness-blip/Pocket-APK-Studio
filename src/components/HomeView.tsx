import React from 'react';
import {
  ArrowRight,
  Code2,
  Download,
  FolderGit2,
  Hammer,
  PackageSearch,
  PenTool,
  Settings,
} from 'lucide-react';
import type { ApkAnalysisReport, StudioProject } from '../lib/studioEngine';

export type StudioTab = 'home' | 'workspace' | 'designer' | 'editor' | 'apk-tools' | 'settings';

interface HomeViewProps {
  project: StudioProject;
  apkInfo: ApkAnalysisReport | null;
  onNavigate: (tab: StudioTab) => void;
  onLoadNativeRepo: () => void;
  loadingNativeRepo: boolean;
}

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1) return `${mb.toFixed(2)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export const HomeView: React.FC<HomeViewProps> = ({
  project,
  apkInfo,
  onNavigate,
  onLoadNativeRepo,
  loadingNativeRepo,
}) => {
  return (
    <div className="max-w-5xl mx-auto px-6 py-8 space-y-8">
      {/* Primary Workspace Hero */}
      <section className="rounded-2xl border border-[#283946] bg-[#16202A] p-7">
        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-6">
          <div className="space-y-2 max-w-2xl">
            <p className="text-xs font-medium text-[#78A5A8]">
              Pocket APK Studio · Version 1.6 · Native Android + Web Preview
            </p>
            <h1 className="text-2xl sm:text-3xl font-display font-bold tracking-tight text-[#F1F6F8] [text-wrap:balance]">
              Manage, Create, and Edit Android Projects in One Workspace
            </h1>
            <p className="text-sm text-[#9BB0BC] leading-relaxed">
              Test screens, edit Kotlin/Java/XML files with real diagnostics, preview Compose and XML layouts live, inspect the compiled APK, and push changes straight to GitHub for automated native APK builds.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3 shrink-0">
            <button
              type="button"
              onClick={() => onNavigate('editor')}
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-[#3E8790] hover:bg-[#35767E] text-sm font-semibold text-white transition-colors whitespace-nowrap cursor-pointer"
            >
              <span>Open Code Editor</span>
              <ArrowRight className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={onLoadNativeRepo}
              disabled={loadingNativeRepo}
              className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-[#1F2D3A] hover:bg-[#273847] border border-[#314554] text-sm font-medium text-[#E4EEF2] transition-colors whitespace-nowrap cursor-pointer disabled:opacity-60"
            >
              <FolderGit2 className="w-4 h-4 text-[#78A5A8]" />
              <span>{loadingNativeRepo ? 'Loading repo…' : 'Load Native Repo Source'}</span>
            </button>
          </div>
        </div>

        <div className="mt-6 pt-5 border-t border-[#243441] grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm">
          <div>
            <p className="text-xs text-[#89A0AD]">Active Project</p>
            <p className="mt-1 font-semibold text-[#EEF4F6] truncate">{project.name}</p>
            <p className="text-xs text-[#89A0AD] font-mono tabular-nums mt-0.5">
              {project.packageName} · {project.files.length} files
            </p>
          </div>

          <div>
            <p className="text-xs text-[#89A0AD]">Compiled Native APK</p>
            <p className="mt-1 font-semibold text-[#EEF4F6]">
              {apkInfo ? `${apkInfo.fileName} (${formatBytes(apkInfo.sizeBytes)})` : 'Ready to build'}
            </p>
            <p className="text-xs text-[#89A0AD] font-mono tabular-nums mt-0.5">
              {apkInfo
                ? `minSdk ${apkInfo.minSdk} · targetSdk ${apkInfo.targetSdk} · ${apkInfo.signingVerified ? 'Signed v2' : 'Unsigned'}`
                : 'AGP 9.1.1 · Gradle 9.3.1 · SDK 36'}
            </p>
          </div>

          <div>
            <p className="text-xs text-[#89A0AD]">GitHub Actions Pipeline</p>
            <p className="mt-1 font-semibold text-[#22C55E]">Workflow aligned (Java 21 · Gradle 9.3.1)</p>
            <p className="text-xs text-[#89A0AD] mt-0.5">
              Native Android sources stay intact for GitHub APK builds
            </p>
          </div>
        </div>
      </section>

      {/* Direct Workflow Navigation Grid */}
      <section className="space-y-3">
        <h2 className="text-base font-display font-semibold text-[#EEF4F6]">Studio Modules</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <button
            type="button"
            onClick={() => onNavigate('workspace')}
            className="text-left p-5 rounded-xl border border-[#253542] bg-[#141D26] hover:bg-[#19242F] hover:border-[#3A5364] transition-colors space-y-2 cursor-pointer"
          >
            <Hammer className="w-5 h-5 text-[#78A5A8]" />
            <h3 className="text-sm font-semibold text-[#EEF4F6]">Workspace & Build APK</h3>
            <p className="text-xs text-[#9BB0BC] leading-relaxed">
              Create Kotlin, Compose, Java, or WebView templates, import/export ZIP projects, and run real Gradle build & test checks.
            </p>
          </button>

          <button
            type="button"
            onClick={() => onNavigate('editor')}
            className="text-left p-5 rounded-xl border border-[#253542] bg-[#141D26] hover:bg-[#19242F] hover:border-[#3A5364] transition-colors space-y-2 cursor-pointer"
          >
            <Code2 className="w-5 h-5 text-[#78A5A8]" />
            <h3 className="text-sm font-semibold text-[#EEF4F6]">Code Editor & Diagnostics</h3>
            <p className="text-xs text-[#9BB0BC] leading-relaxed">
              Multi-tab editor with live syntax/bracket diagnostics, XML attribute inspector, file duplication, and inline code assistant.
            </p>
          </button>

          <button
            type="button"
            onClick={() => onNavigate('designer')}
            className="text-left p-5 rounded-xl border border-[#253542] bg-[#141D26] hover:bg-[#19242F] hover:border-[#3A5364] transition-colors space-y-2 cursor-pointer"
          >
            <PenTool className="w-5 h-5 text-[#78A5A8]" />
            <h3 className="text-sm font-semibold text-[#EEF4F6]">APK Designer Canvas</h3>
            <p className="text-xs text-[#9BB0BC] leading-relaxed">
              Figma-style drag-and-drop canvas with photo uploads, filter adjustments, layers, PNG export, and Compose/XML code export.
            </p>
          </button>

          <button
            type="button"
            onClick={() => onNavigate('apk-tools')}
            className="text-left p-5 rounded-xl border border-[#253542] bg-[#141D26] hover:bg-[#19242F] hover:border-[#3A5364] transition-colors space-y-2 cursor-pointer"
          >
            <PackageSearch className="w-5 h-5 text-[#78A5A8]" />
            <h3 className="text-sm font-semibold text-[#EEF4F6]">APK Tools & Inspector</h3>
            <p className="text-xs text-[#9BB0BC] leading-relaxed">
              Inspect the compiled Pocket APK Studio binary or upload any APK to examine package metadata, permissions, and archive entries.
            </p>
          </button>

          <button
            type="button"
            onClick={() => onNavigate('settings')}
            className="text-left p-5 rounded-xl border border-[#253542] bg-[#141D26] hover:bg-[#19242F] hover:border-[#3A5364] transition-colors space-y-2 cursor-pointer"
          >
            <Settings className="w-5 h-5 text-[#78A5A8]" />
            <h3 className="text-sm font-semibold text-[#EEF4F6]">Environment & Preferences</h3>
            <p className="text-xs text-[#9BB0BC] leading-relaxed">
              Review Termux, Ubuntu, Java, and Android SDK setup checklists plus editor font size and preview defaults.
            </p>
          </button>

          <a
            href="/api/apk/download"
            className="text-left p-5 rounded-xl border border-[#253542] bg-[#141D26] hover:bg-[#19242F] hover:border-[#3A5364] transition-colors space-y-2 block"
          >
            <Download className="w-5 h-5 text-[#78A5A8]" />
            <h3 className="text-sm font-semibold text-[#EEF4F6]">Download Compiled Debug APK</h3>
            <p className="text-xs text-[#9BB0BC] leading-relaxed">
              Download the latest signed native Android APK built directly from this repository to install on any Android device.
            </p>
          </a>
        </div>
      </section>
    </div>
  );
};
