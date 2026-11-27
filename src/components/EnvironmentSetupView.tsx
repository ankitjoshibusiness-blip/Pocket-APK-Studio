import React from 'react';
import { CheckCircle2, RotateCcw } from 'lucide-react';

interface EnvironmentSetupViewProps {
  editorFontSize: number;
  onChangeFontSize: (size: number) => void;
  liveDiagnostics: boolean;
  onToggleDiagnostics: (next: boolean) => void;
  defaultPreviewTheme: 'dark' | 'light';
  onChangePreviewTheme: (theme: 'dark' | 'light') => void;
  onResetWorkspace: () => void;
}

const TOOLCHAIN_ITEMS = [
  { name: 'Android Gradle Plugin (AGP)', value: '9.1.1', detail: 'Aligned across build.gradle.kts and GitHub Actions' },
  { name: 'Gradle Build Tool', value: '9.3.1', detail: 'Configured in workflow and local container' },
  { name: 'JDK Runtime', value: 'Temurin 21 (target 17)', detail: 'Bytecode compatibility set to Java 17' },
  { name: 'Android SDK Platform', value: 'API 36 (Android 16)', detail: 'compileSdk 36 · targetSdk 36 · minSdk 26' },
  { name: 'Build Tools', value: '36.0.0', detail: 'aapt2 and apksigner verified' },
  { name: 'Debug Signing Fallback', value: 'CI-Safe', detail: 'Uses root debug.keystore when present; default keystore on GitHub runners' },
];

export const EnvironmentSetupView: React.FC<EnvironmentSetupViewProps> = ({
  editorFontSize,
  onChangeFontSize,
  liveDiagnostics,
  onToggleDiagnostics,
  defaultPreviewTheme,
  onChangePreviewTheme,
  onResetWorkspace,
}) => {
  return (
    <div className="max-w-5xl mx-auto px-6 py-7 space-y-6">
      <div>
        <h1 className="text-xl font-display font-bold text-[#EEF4F6]">Environment & Preferences</h1>
        <p className="text-xs text-[#9BB0BC] mt-0.5">
          Native Android toolchain readiness and web studio editor preferences
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Editor Preferences */}
        <section className="lg:col-span-5 rounded-xl border border-[#253542] bg-[#141D26] p-5 space-y-5">
          <h2 className="text-sm font-display font-semibold text-[#EEF4F6]">Editor Preferences</h2>

          <div className="space-y-2">
            <div className="flex items-center justify-between text-xs">
              <span className="text-[#B3C4CE]">Editor font size</span>
              <span className="font-mono tabular-nums text-[#EEF4F6]">{editorFontSize}px</span>
            </div>
            <input
              type="range"
              min={11}
              max={18}
              value={editorFontSize}
              aria-label="Editor font size"
              onChange={(e) => onChangeFontSize(Number(e.target.value))}
              className="w-full accent-[#3E8790]"
            />
          </div>

          <div className="flex items-center justify-between gap-4 py-2 border-t border-[#22313D]">
            <div>
              <p className="text-xs font-medium text-[#EEF4F6]">Live syntax & delimiter diagnostics</p>
              <p className="text-[11px] text-[#89A0AD]">Scans XML tags, Kotlin raw strings, and nested comments</p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={liveDiagnostics}
              aria-label="Toggle live diagnostics"
              onClick={() => onToggleDiagnostics(!liveDiagnostics)}
              className={`relative w-10 h-5 rounded-full transition-colors shrink-0 cursor-pointer ${
                liveDiagnostics ? 'bg-[#3E8790]' : 'bg-[#2E404F]'
              }`}
            >
              <span
                className={`block w-4 h-4 rounded-full bg-white transition-transform ${
                  liveDiagnostics ? 'translate-x-5' : 'translate-x-0.5'
                }`}
              />
            </button>
          </div>

          <div className="flex items-center justify-between gap-4 py-2 border-t border-[#22313D]">
            <div>
              <p className="text-xs font-medium text-[#EEF4F6]">Default Live UI Preview theme</p>
              <p className="text-[11px] text-[#89A0AD]">Initial Material theme inside the phone frame</p>
            </div>
            <div className="flex items-center p-0.5 rounded-lg bg-[#0F151C] border border-[#253542]">
              <button
                type="button"
                onClick={() => onChangePreviewTheme('dark')}
                className={`px-2.5 py-1 text-xs rounded-md cursor-pointer ${
                  defaultPreviewTheme === 'dark' ? 'bg-[#253746] text-white' : 'text-[#89A0AD]'
                }`}
              >
                Dark
              </button>
              <button
                type="button"
                onClick={() => onChangePreviewTheme('light')}
                className={`px-2.5 py-1 text-xs rounded-md cursor-pointer ${
                  defaultPreviewTheme === 'light' ? 'bg-[#253746] text-white' : 'text-[#89A0AD]'
                }`}
              >
                Light
              </button>
            </div>
          </div>

          <div className="pt-3 border-t border-[#22313D]">
            <button
              type="button"
              onClick={onResetWorkspace}
              className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#1A2530] hover:bg-[#233240] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] cursor-pointer"
            >
              <RotateCcw className="w-3.5 h-3.5 text-[#78A5A8]" />
              <span>Reset Starter Project</span>
            </button>
          </div>
        </section>

        {/* Native Android Build Toolchain Checklist */}
        <section className="lg:col-span-7 rounded-xl border border-[#253542] bg-[#141D26] p-5 space-y-4">
          <h2 className="text-sm font-display font-semibold text-[#EEF4F6]">
            Native APK & GitHub Actions Toolchain
          </h2>

          <div className="divide-y divide-[#22313D]">
            {TOOLCHAIN_ITEMS.map((item) => (
              <div key={item.name} className="py-3 flex items-start justify-between gap-4">
                <div>
                  <p className="text-xs font-semibold text-[#EEF4F6]">{item.name}</p>
                  <p className="text-[11px] text-[#89A0AD] mt-0.5">{item.detail}</p>
                </div>
                <div className="inline-flex items-center gap-1.5 text-xs font-mono text-[#22C55E] shrink-0">
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  <span>{item.value}</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
};
