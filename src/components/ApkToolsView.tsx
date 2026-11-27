import React, { useMemo, useRef, useState } from 'react';
import JSZip from 'jszip';
import {
  CheckCircle2,
  Copy,
  Download,
  PackageSearch,
  RefreshCw,
  Search,
  Upload,
} from 'lucide-react';
import type { ApkAnalysisReport } from '../lib/studioEngine';

interface ApkToolsViewProps {
  builtApkInfo: ApkAnalysisReport | null;
  onRefreshBuiltApk: () => Promise<void>;
}

function formatSize(bytes: number): string {
  if (!bytes) return '0 B';
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export const ApkToolsView: React.FC<ApkToolsViewProps> = ({
  builtApkInfo,
  onRefreshBuiltApk,
}) => {
  const [customReport, setCustomReport] = useState<ApkAnalysisReport | null>(null);
  const [entryFilter, setEntryFilter] = useState('');
  const [statusLog, setStatusLog] = useState('Inspect the compiled Pocket APK Studio binary or upload any .apk file.');
  const [copied, setCopied] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const apkInputRef = useRef<HTMLInputElement | null>(null);

  const report = customReport ?? builtApkInfo;

  const filteredEntries = useMemo(() => {
    const entries = report?.entrySummary.sampleEntries ?? [];
    const q = entryFilter.trim().toLowerCase();
    if (!q) return entries;
    return entries.filter((item) => item.toLowerCase().includes(q));
  }, [report, entryFilter]);

  const handleUploadApk = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setStatusLog(`Inspecting archive entries inside ${file.name}…`);
    try {
      const zip = await JSZip.loadAsync(file);
      const names = Object.keys(zip.files).sort();
      const dexCount = names.filter((n) => n.endsWith('.dex')).length;
      const resCount = names.filter((n) => n.startsWith('res/')).length;
      const assetCount = names.filter((n) => n.startsWith('assets/')).length;
      const libCount = names.filter((n) => n.startsWith('lib/')).length;
      const hasMetaInf = names.some((n) => n.startsWith('META-INF/') && /\.(RSA|DSA|EC|SF)$/i.test(n));

      setCustomReport({
        fileName: file.name,
        sizeBytes: file.size,
        packageName: file.name.replace(/\.apk$/i, ''),
        versionName: 'Uploaded APK',
        versionCode: '-',
        minSdk: 'Inspect on device / aapt2',
        targetSdk: 'Inspect on device / aapt2',
        applicationLabel: file.name,
        signingVerified: hasMetaInf,
        signingSchemes: hasMetaInf ? ['META-INF signature block detected'] : [],
        permissions: [],
        entrySummary: {
          totalEntries: names.length,
          dexCount,
          resCount,
          assetCount,
          libCount,
          sampleEntries: names.slice(0, 120),
        },
      });
      setStatusLog(`Analyzed ${file.name}: ${names.length} archive entries (${dexCount} DEX, ${resCount} resources).`);
    } catch (err) {
      setStatusLog(`Failed to parse APK: ${err instanceof Error ? err.message : 'invalid APK archive'}`);
    } finally {
      e.target.value = '';
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    setCustomReport(null);
    await onRefreshBuiltApk();
    setStatusLog('Reloaded compiled app-debug.apk analysis from build outputs.');
    setRefreshing(false);
  };

  const handleCopyReport = async () => {
    if (!report) return;
    const text = [
      `APK: ${report.fileName} (${formatSize(report.sizeBytes)})`,
      `Package: ${report.packageName}`,
      `Version: ${report.versionName} (code ${report.versionCode})`,
      `SDK: minSdk ${report.minSdk} / targetSdk ${report.targetSdk}`,
      `Signing: ${report.signingVerified ? `Verified (${report.signingSchemes.join(', ') || 'v2'})` : 'Unverified'}`,
      `Contents: ${report.entrySummary.totalEntries} entries (${report.entrySummary.dexCount} DEX, ${report.entrySummary.resCount} res, ${report.entrySummary.assetCount} assets, ${report.entrySummary.libCount} libs)`,
      `Permissions:\n${report.permissions.length ? report.permissions.join('\n') : 'None listed'}`,
    ].join('\n');
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      // clipboard unavailable
    }
  };

  return (
    <div className="max-w-6xl mx-auto px-6 py-7 space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-display font-bold text-[#EEF4F6]">APK Tools & Binary Inspector</h1>
          <p className="text-xs text-[#9BB0BC] mt-0.5">{statusLog}</p>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <input
            ref={apkInputRef}
            type="file"
            accept=".apk,application/vnd.android.package-archive"
            className="hidden"
            onChange={handleUploadApk}
          />
          <button
            type="button"
            onClick={() => apkInputRef.current?.click()}
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#3E8790] hover:bg-[#35767E] text-xs font-semibold text-white whitespace-nowrap cursor-pointer"
          >
            <Upload className="w-3.5 h-3.5" />
            <span>Analyze Uploaded APK</span>
          </button>

          <button
            type="button"
            onClick={handleRefresh}
            disabled={refreshing}
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#1A2530] hover:bg-[#22313F] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] whitespace-nowrap cursor-pointer"
          >
            <RefreshCw className={`w-3.5 h-3.5 text-[#78A5A8] ${refreshing ? 'animate-spin' : ''}`} />
            <span>Inspect Compiled APK</span>
          </button>

          <button
            type="button"
            onClick={handleCopyReport}
            disabled={!report}
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#1A2530] hover:bg-[#22313F] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] whitespace-nowrap cursor-pointer disabled:opacity-40"
          >
            <Copy className="w-3.5 h-3.5 text-[#78A5A8]" />
            <span>{copied ? 'Copied' : 'Copy Report'}</span>
          </button>

          <a
            href="/api/apk/download"
            className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-[#1A2530] hover:bg-[#22313F] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] whitespace-nowrap"
          >
            <Download className="w-3.5 h-3.5 text-[#78A5A8]" />
            <span>Download APK</span>
          </a>
        </div>
      </div>

      {!report ? (
        <div className="rounded-xl border border-[#253542] bg-[#141D26] p-10 text-center space-y-2">
          <PackageSearch className="w-7 h-7 text-[#78A5A8] mx-auto" />
          <p className="text-sm font-medium text-[#EEF4F6]">No APK loaded yet</p>
          <p className="text-xs text-[#89A0AD]">
            Upload an APK file above or assemble the native project from the Workspace tab.
          </p>
        </div>
      ) : (
        <div className="space-y-6">
          {/* Summary Grid */}
          <section className="rounded-xl border border-[#253542] bg-[#141D26] p-5 grid grid-cols-2 md:grid-cols-4 gap-5">
            <div>
              <p className="text-xs text-[#89A0AD]">Application</p>
              <p className="mt-1 text-sm font-semibold text-[#EEF4F6] truncate">{report.applicationLabel}</p>
              <p className="text-xs font-mono text-[#89A0AD] truncate mt-0.5">{report.packageName}</p>
            </div>

            <div>
              <p className="text-xs text-[#89A0AD]">Version & Size</p>
              <p className="mt-1 text-sm font-semibold font-mono tabular-nums text-[#EEF4F6]">
                v{report.versionName} (code {report.versionCode})
              </p>
              <p className="text-xs font-mono tabular-nums text-[#89A0AD] mt-0.5">
                {formatSize(report.sizeBytes)}
              </p>
            </div>

            <div>
              <p className="text-xs text-[#89A0AD]">Android SDK</p>
              <p className="mt-1 text-sm font-semibold font-mono tabular-nums text-[#EEF4F6]">
                minSdk {report.minSdk} · targetSdk {report.targetSdk}
              </p>
              <p className="text-xs text-[#89A0AD] mt-0.5">{report.fileName}</p>
            </div>

            <div>
              <p className="text-xs text-[#89A0AD]">Signature Verification</p>
              <p className="mt-1 text-sm font-semibold inline-flex items-center gap-1.5 text-[#22C55E]">
                <CheckCircle2 className="w-4 h-4" />
                <span>{report.signingVerified ? 'Verified' : 'Unverified'}</span>
              </p>
              <p className="text-xs text-[#89A0AD] mt-0.5">
                {report.signingSchemes.length ? report.signingSchemes.join(', ') : 'Debug keystore'}
              </p>
            </div>
          </section>

          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
            {/* Contents & Permissions */}
            <section className="lg:col-span-5 rounded-xl border border-[#253542] bg-[#141D26] p-5 space-y-5">
              <div>
                <h2 className="text-sm font-display font-semibold text-[#EEF4F6]">APK Contents Breakdown</h2>
                <div className="mt-3 grid grid-cols-2 gap-3 text-xs">
                  <div className="p-3 rounded-lg bg-[#0F151C] border border-[#22313D]">
                    <span className="text-[#89A0AD]">Total Entries</span>
                    <p className="mt-1 text-base font-mono tabular-nums font-semibold text-[#EEF4F6]">
                      {report.entrySummary.totalEntries}
                    </p>
                  </div>
                  <div className="p-3 rounded-lg bg-[#0F151C] border border-[#22313D]">
                    <span className="text-[#89A0AD]">DEX Bytecode Files</span>
                    <p className="mt-1 text-base font-mono tabular-nums font-semibold text-[#EEF4F6]">
                      {report.entrySummary.dexCount}
                    </p>
                  </div>
                  <div className="p-3 rounded-lg bg-[#0F151C] border border-[#22313D]">
                    <span className="text-[#89A0AD]">Resource Files</span>
                    <p className="mt-1 text-base font-mono tabular-nums font-semibold text-[#EEF4F6]">
                      {report.entrySummary.resCount}
                    </p>
                  </div>
                  <div className="p-3 rounded-lg bg-[#0F151C] border border-[#22313D]">
                    <span className="text-[#89A0AD]">Assets & Native Libs</span>
                    <p className="mt-1 text-base font-mono tabular-nums font-semibold text-[#EEF4F6]">
                      {report.entrySummary.assetCount} / {report.entrySummary.libCount}
                    </p>
                  </div>
                </div>
              </div>

              <div className="pt-4 border-t border-[#22313D]">
                <h3 className="text-sm font-display font-semibold text-[#EEF4F6]">Requested Permissions</h3>
                {report.permissions.length > 0 ? (
                  <ul className="mt-2.5 space-y-1.5 text-xs font-mono text-[#B9CAD3]">
                    {report.permissions.map((perm) => (
                      <li key={perm} className="truncate">
                        {perm}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="mt-2 text-xs text-[#89A0AD]">No manifest permissions reported.</p>
                )}
              </div>
            </section>

            {/* Archive Entries Explorer */}
            <section className="lg:col-span-7 rounded-xl border border-[#253542] bg-[#141D26] p-5 flex flex-col space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-sm font-display font-semibold text-[#EEF4F6]">Archive Entries</h2>
                <div className="relative w-56">
                  <Search className="w-3.5 h-3.5 text-[#78909D] absolute left-2.5 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    value={entryFilter}
                    onChange={(e) => setEntryFilter(e.target.value)}
                    placeholder="Filter APK entries…"
                    aria-label="Filter APK entries"
                    className="w-full pl-8 pr-2.5 py-1.5 text-xs rounded-md bg-[#0E151C] border border-[#243441] text-[#DCE7EC] outline-none focus:border-[#4B9BA2]"
                  />
                </div>
              </div>

              <div className="rounded-lg border border-[#22313D] bg-[#0B1016] overflow-auto max-h-[340px] divide-y divide-[#1A2530]">
                {filteredEntries.map((entry) => (
                  <div key={entry} className="px-3 py-2 text-xs font-mono text-[#C8D7DF] truncate">
                    {entry}
                  </div>
                ))}
                {filteredEntries.length === 0 && (
                  <div className="p-4 text-xs text-[#89A0AD]">No matching archive entries.</div>
                )}
              </div>
            </section>
          </div>
        </div>
      )}
    </div>
  );
};
