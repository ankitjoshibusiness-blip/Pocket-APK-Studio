import React, { useMemo, useState } from 'react';
import {
  AlertTriangle,
  AlignCenter,
  AlignLeft,
  AlignRight,
  CheckCircle2,
  Code2,
  CopyPlus,
  FileCode2,
  FilePlus2,
  FolderOpen,
  PanelRightClose,
  PanelRightOpen,
  PenTool,
  Search,
  Send,
  Smartphone,
  Trash2,
  Wand2,
  X,
} from 'lucide-react';
import {
  defaultCopyRelativePath,
  generateAiResponse,
  parseUiPreview,
  readXmlAttribute,
  scanDiagnostics,
  writeXmlAttribute,
  type AiPatchSuggestion,
  type StudioProject,
} from '../lib/studioEngine';
import type { DesignerScreen } from '../lib/apkDesignerEngine';
import {
  importAllScreensFromFile,
  importFullProjectToDesigner,
} from '../lib/fullAppImportEngine';
import { LivePhonePreview } from './LivePhonePreview';

interface CodeEditorViewProps {
  project: StudioProject;
  activePath: string;
  onSelectPath: (path: string) => void;
  onUpdateFile: (path: string, content: string) => void;
  onAddFile: (path: string, content: string) => void;
  onDeleteFile: (path: string) => void;
  onOpenInDesigner: (path: string) => void;
  onOpenScreensInDesigner: (screens: DesignerScreen[], activeScreenId?: string, label?: string) => void;
  editorFontSize: number;
  liveDiagnostics: boolean;
  defaultPreviewTheme: 'dark' | 'light';
}

function getInitialDesktopState(): boolean {
  if (typeof window === 'undefined') return true;
  return window.innerWidth >= 1024;
}

export const CodeEditorView: React.FC<CodeEditorViewProps> = ({
  project,
  activePath,
  onSelectPath,
  onUpdateFile,
  onAddFile,
  onDeleteFile,
  onOpenInDesigner,
  onOpenScreensInDesigner,
  editorFontSize,
  liveDiagnostics,
  defaultPreviewTheme,
}) => {
  const [filterText, setFilterText] = useState('');
  const [showFiles, setShowFiles] = useState<boolean>(getInitialDesktopState);
  const [showPreviewDesktop, setShowPreviewDesktop] = useState(true);
  const [mobilePane, setMobilePane] = useState<'code' | 'preview'>('code');
  const [showAssistant, setShowAssistant] = useState(false);
  const [newFileOpen, setNewFileOpen] = useState(false);
  const [newFileName, setNewFileName] = useState('');
  const [assistantInput, setAssistantInput] = useState('');
  const [assistantMessages, setAssistantMessages] = useState<
    Array<{ role: 'user' | 'helper'; text: string; suggestion?: AiPatchSuggestion }>
  >([
    {
      role: 'helper',
      text: 'Ask me to check syntax errors or insert a Compose/XML Button patch into your active file.',
    },
  ]);

  const activeFile = useMemo(
    () => project.files.find((f) => f.path === activePath) ?? project.files[0],
    [project.files, activePath]
  );

  const colorsXml = useMemo(
    () => project.files.find((f) => f.path.endsWith('colors.xml'))?.content ?? '',
    [project.files]
  );

  const filteredFiles = useMemo(() => {
    const q = filterText.trim().toLowerCase();
    if (!q) return project.files;
    return project.files.filter((f) => f.path.toLowerCase().includes(q));
  }, [project.files, filterText]);

  const diagnostics = useMemo(() => {
    if (!liveDiagnostics || !activeFile) return [];
    return scanDiagnostics(activeFile.content, activeFile.path);
  }, [activeFile, liveDiagnostics]);

  const previewResult = useMemo(() => {
    if (!activeFile) {
      return { sourceType: 'none' as const, screenTitle: 'No File', nodes: [], warnings: [] };
    }
    return parseUiPreview(activeFile.content, activeFile.path, colorsXml);
  }, [activeFile, colorsXml]);

  const fileScreens = useMemo(() => {
    if (!activeFile) return [];
    return importAllScreensFromFile({
      filePath: activeFile.path,
      content: activeFile.content,
      projectFiles: project.files,
    }).screens;
  }, [activeFile, project.files]);

  const projectScreens = useMemo(() => {
    return importFullProjectToDesigner(project.files, activeFile?.path).screens;
  }, [project.files, activeFile?.path]);

  const isXmlFile = activeFile?.path.toLowerCase().endsWith('.xml') ?? false;
  const xmlTextAttr = isXmlFile && activeFile ? readXmlAttribute(activeFile.content, 'text') : null;
  const xmlGravityAttr =
    isXmlFile && activeFile ? readXmlAttribute(activeFile.content, 'gravity') : null;

  const lineCount = activeFile ? activeFile.content.split('\n').length : 0;

  const handleSelectFile = (path: string) => {
    onSelectPath(path);
    if (typeof window !== 'undefined' && window.innerWidth < 1024) {
      setShowFiles(false);
    }
  };

  const handleCreateFile = (e: React.FormEvent) => {
    e.preventDefault();
    const cleaned = newFileName.trim().replace(/^\/+/, '');
    if (!cleaned) return;
    if (project.files.some((f) => f.path === cleaned)) {
      handleSelectFile(cleaned);
      setNewFileOpen(false);
      setNewFileName('');
      return;
    }
    const starter = cleaned.endsWith('.xml')
      ? `<?xml version="1.0" encoding="utf-8"?>\n<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n    android:layout_width="match_parent"\n    android:layout_height="match_parent"\n    android:orientation="vertical"\n    android:padding="16dp">\n\n    <TextView\n        android:layout_width="match_parent"\n        android:layout_height="wrap_content"\n        android:gravity="start"\n        android:text="New Screen" />\n</LinearLayout>\n`
      : cleaned.endsWith('.kt')
      ? `package ${project.packageName}\n\nimport androidx.compose.material3.Text\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun NewScreen() {\n    Text("New Composable Screen")\n}\n`
      : '';
    onAddFile(cleaned, starter);
    handleSelectFile(cleaned);
    setNewFileOpen(false);
    setNewFileName('');
  };

  const handleDuplicateActive = () => {
    if (!activeFile) return;
    const target = defaultCopyRelativePath(activeFile.path);
    onAddFile(target, activeFile.content);
    handleSelectFile(target);
  };

  const handleAssistantSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const prompt = assistantInput.trim();
    if (!prompt) return;
    const suggestion = generateAiResponse(prompt, activeFile, project);
    setAssistantMessages((prev) => [
      ...prev,
      { role: 'user', text: prompt },
      { role: 'helper', text: suggestion.reply, suggestion },
    ]);
    setAssistantInput('');
  };

  const fileExplorerContent = (
    <div className="h-full bg-[#121A22] flex flex-col">
      <div className="p-3 border-b border-[#22313D] space-y-2.5 shrink-0">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-display font-semibold text-[#EEF4F6] truncate">
            {project.name}
          </span>
          <div className="flex items-center gap-1 shrink-0">
            <button
              type="button"
              aria-label="Create new file"
              onClick={() => setNewFileOpen((v) => !v)}
              className="p-1.5 rounded-md text-[#9BB0BC] hover:text-white hover:bg-[#1E2B37] transition-colors cursor-pointer"
            >
              <FilePlus2 className="w-3.5 h-3.5" />
            </button>
            <button
              type="button"
              aria-label="Duplicate active file"
              onClick={handleDuplicateActive}
              disabled={!activeFile}
              className="p-1.5 rounded-md text-[#9BB0BC] hover:text-white hover:bg-[#1E2B37] transition-colors cursor-pointer disabled:opacity-40"
            >
              <CopyPlus className="w-3.5 h-3.5" />
            </button>
            <button
              type="button"
              aria-label="Close file explorer"
              onClick={() => setShowFiles(false)}
              className="p-1.5 rounded-md text-[#9BB0BC] hover:text-white hover:bg-[#1E2B37] transition-colors cursor-pointer lg:hidden"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {newFileOpen && (
          <form onSubmit={handleCreateFile} className="flex items-center gap-1.5">
            <input
              type="text"
              value={newFileName}
              onChange={(e) => setNewFileName(e.target.value)}
              placeholder="app/src/main/res/layout/screen.xml"
              className="flex-1 min-w-0 px-2 py-1 text-xs rounded bg-[#0C1218] border border-[#344B5C] text-[#EEF4F6] outline-none"
            />
            <button
              type="submit"
              className="px-2 py-1 text-xs font-medium rounded bg-[#3E8790] text-white cursor-pointer"
            >
              Add
            </button>
          </form>
        )}

        <div className="relative">
          <Search className="w-3.5 h-3.5 text-[#78909D] absolute left-2.5 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={filterText}
            onChange={(e) => setFilterText(e.target.value)}
            placeholder="Filter files…"
            aria-label="Filter project files"
            className="w-full pl-8 pr-2.5 py-1.5 text-xs rounded-md bg-[#0E151C] border border-[#243441] text-[#DCE7EC] placeholder:text-[#6D8390] outline-none focus:border-[#4B9BA2]"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-1.5 space-y-0.5">
        {filteredFiles.map((file) => {
          const isActive = activeFile?.path === file.path;
          const fileName = file.path.split('/').pop() ?? file.path;
          const folder = file.path.includes('/')
            ? file.path.slice(0, file.path.lastIndexOf('/'))
            : '';
          return (
            <div
              key={file.path}
              className={`group flex items-center justify-between gap-1 rounded-md px-2 py-1.5 text-xs transition-colors ${
                isActive
                  ? 'bg-[#1D2D3A] text-[#F1F6F8] font-medium'
                  : 'text-[#A2B6C2] hover:bg-[#18232D] hover:text-[#EEF4F6]'
              }`}
            >
              <button
                type="button"
                onClick={() => handleSelectFile(file.path)}
                className="flex-1 min-w-0 text-left flex items-center gap-2 cursor-pointer"
              >
                <FileCode2 className="w-3.5 h-3.5 shrink-0 text-[#78A5A8]" />
                <span className="truncate">
                  <span>{fileName}</span>
                  {folder && (
                    <span className="block text-[10px] text-[#738A96] truncate font-normal">
                      {folder}
                    </span>
                  )}
                </span>
              </button>
              {project.files.length > 1 && (
                <button
                  type="button"
                  aria-label={`Delete ${file.path}`}
                  onClick={() => onDeleteFile(file.path)}
                  className="opacity-70 lg:opacity-0 group-hover:opacity-100 p-1 rounded text-[#89A0AD] hover:text-[#EF4444] transition-opacity cursor-pointer"
                >
                  <Trash2 className="w-3 h-3" />
                </button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );

  return (
    <div className="h-[calc(100dvh-112px)] md:h-[calc(100dvh-56px)] flex relative overflow-hidden bg-[#0F151C]">
      {/* Desktop Docked File Explorer Sidebar */}
      {showFiles && (
        <aside className="hidden lg:block w-64 border-r border-[#253542] shrink-0 h-full overflow-hidden">
          {fileExplorerContent}
        </aside>
      )}

      {/* Mobile / Tablet Overlay File Explorer Drawer */}
      {showFiles && (
        <div className="lg:hidden absolute inset-0 z-30 flex">
          <aside className="w-[270px] max-w-[82vw] h-full border-r border-[#2C3E4C] shadow-2xl">
            {fileExplorerContent}
          </aside>
          <button
            type="button"
            aria-label="Close files drawer"
            onClick={() => setShowFiles(false)}
            className="flex-1 bg-black/50 cursor-pointer"
          />
        </div>
      )}

      {/* Main Editor Column */}
      <div className="flex-1 min-w-0 min-h-0 flex flex-col bg-[#0F151C]">
        {/* Editor Toolbar */}
        <div className="flex items-center justify-between gap-2 px-3 py-2 border-b border-[#253542] bg-[#141D26] overflow-x-auto shrink-0">
          <div className="flex items-center gap-2 text-xs min-w-0">
            <button
              type="button"
              onClick={() => setShowFiles((v) => !v)}
              aria-label={showFiles ? 'Hide project files' : 'Show project files'}
              className={`inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap cursor-pointer shrink-0 ${
                showFiles
                  ? 'bg-[#213341] border-[#4B9BA2] text-white'
                  : 'bg-[#10171F] border-[#2C3E4C] text-[#C8D7DF] hover:text-white'
              }`}
            >
              <FolderOpen className="w-3.5 h-3.5 text-[#78A5A8]" />
              <span>Files</span>
            </button>

            <span className="font-mono text-[#EEF4F6] truncate max-w-[140px] sm:max-w-[260px]">
              {activeFile?.path.split('/').pop() ?? 'No file'}
            </span>
            <span className="hidden sm:inline text-[#6F8694]" aria-hidden="true">
              ·
            </span>
            <span className="hidden sm:inline font-mono tabular-nums text-[#89A0AD] whitespace-nowrap">
              {lineCount} lines
            </span>
            <span className="hidden md:inline text-[#6F8694]" aria-hidden="true">
              ·
            </span>
            {diagnostics.length === 0 ? (
              <span className="hidden md:inline-flex items-center gap-1 text-[#22C55E] whitespace-nowrap">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>0 issues</span>
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 text-[#F59E0B] whitespace-nowrap">
                <AlertTriangle className="w-3.5 h-3.5" />
                <span>
                  {diagnostics.length} issue{diagnostics.length > 1 ? 's' : ''}
                </span>
              </span>
            )}
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            {/* Mobile / Tablet Code vs Preview Switcher */}
            <div className="flex lg:hidden items-center p-0.5 rounded-lg bg-[#0E151C] border border-[#253542]">
              <button
                type="button"
                onClick={() => setMobilePane('code')}
                className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium cursor-pointer ${
                  mobilePane === 'code' ? 'bg-[#3E8790] text-white' : 'text-[#9BB0BC]'
                }`}
              >
                <Code2 className="w-3.5 h-3.5" />
                <span>Code</span>
              </button>
              <button
                type="button"
                onClick={() => setMobilePane('preview')}
                className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium cursor-pointer ${
                  mobilePane === 'preview' ? 'bg-[#3E8790] text-white' : 'text-[#9BB0BC]'
                }`}
              >
                <Smartphone className="w-3.5 h-3.5" />
                <span>Preview</span>
              </button>
            </div>

            {activeFile && /\.(kt|java|xml)$/i.test(activeFile.path) && (
              <button
                type="button"
                onClick={() => onOpenInDesigner(activeFile.path)}
                className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-semibold bg-[#3E8790] hover:bg-[#35767E] text-white transition-colors whitespace-nowrap cursor-pointer"
              >
                <PenTool className="w-3.5 h-3.5" />
                <span>Edit in Designer</span>
              </button>
            )}

            <button
              type="button"
              onClick={() => setShowAssistant((v) => !v)}
              className={`inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap cursor-pointer ${
                showAssistant
                  ? 'bg-[#223544] border-[#4B9BA2] text-white'
                  : 'bg-[#1A2530] border-[#2C3E4C] text-[#C8D7DF] hover:text-white'
              }`}
            >
              <Wand2 className="w-3.5 h-3.5 text-[#78A5A8]" />
              <span className="hidden sm:inline">Code Helper</span>
            </button>

            <button
              type="button"
              onClick={() => setShowPreviewDesktop((v) => !v)}
              className="hidden lg:inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-[#1A2530] border border-[#2C3E4C] text-[#C8D7DF] hover:text-white transition-colors whitespace-nowrap cursor-pointer"
            >
              {showPreviewDesktop ? (
                <PanelRightClose className="w-3.5 h-3.5 text-[#78A5A8]" />
              ) : (
                <PanelRightOpen className="w-3.5 h-3.5 text-[#78A5A8]" />
              )}
              <span>{showPreviewDesktop ? 'Hide Preview' : 'Show Preview'}</span>
            </button>
          </div>
        </div>

        {/* XML Quick Attribute Bar (Text + Text Alignment) */}
        {isXmlFile && activeFile && xmlTextAttr !== null && (
          <div className="flex flex-wrap items-center gap-2.5 px-3 py-2 border-b border-[#22313D] bg-[#121A22] text-xs shrink-0">
            <label className="inline-flex items-center gap-1.5">
              <span className="text-[#9BB0BC] font-mono text-[11px]">text</span>
              <input
                type="text"
                value={xmlTextAttr}
                onChange={(e) =>
                  onUpdateFile(
                    activeFile.path,
                    writeXmlAttribute(activeFile.content, 'text', e.target.value)
                  )
                }
                className="px-2 py-1 rounded bg-[#0C1218] border border-[#2C3E4C] text-[#EEF4F6] outline-none focus:border-[#4B9BA2] w-44 sm:w-56"
              />
            </label>

            <div className="inline-flex items-center gap-1">
              <span className="text-[#9BB0BC] font-mono text-[11px]">align</span>
              <div className="inline-flex items-center p-0.5 rounded bg-[#0C1218] border border-[#2C3E4C]">
                {(
                  [
                    ['start', 'Align left', AlignLeft],
                    ['center', 'Align center', AlignCenter],
                    ['end', 'Align right', AlignRight],
                  ] as const
                ).map(([grav, label, Icon]) => {
                  const active = (xmlGravityAttr ?? 'start') === grav;
                  return (
                    <button
                      key={grav}
                      type="button"
                      aria-label={label}
                      onClick={() =>
                        onUpdateFile(
                          activeFile.path,
                          writeXmlAttribute(activeFile.content, 'gravity', grav)
                        )
                      }
                      className={`p-1 rounded cursor-pointer ${
                        active ? 'bg-[#3E8790] text-white' : 'text-[#9BB0BC] hover:text-white'
                      }`}
                    >
                      <Icon className="w-3.5 h-3.5" />
                    </button>
                  );
                })}
              </div>
            </div>
          </div>
        )}

        {/* Mobile Preview Mode */}
        {mobilePane === 'preview' && (
          <div className="flex-1 min-h-0 overflow-auto lg:hidden">
            <LivePhonePreview
              preview={previewResult}
              fileScreens={fileScreens}
              projectScreens={projectScreens}
              defaultTheme={defaultPreviewTheme}
              onEditInDesigner={(screen) => {
                if (screen) {
                  onOpenScreensInDesigner([screen], screen.id, screen.name);
                } else if (activeFile) {
                  onOpenInDesigner(activeFile.path);
                }
              }}
              onEditFullAppInDesigner={(screens, activeId) =>
                onOpenScreensInDesigner(screens, activeId, project.name)
              }
            />
          </div>
        )}

        {/* Touch-Friendly Code Textarea (Always visible on desktop, visible on mobile when mobilePane === 'code') */}
        <div
          className={`flex-1 min-h-0 flex overflow-hidden ${
            mobilePane === 'preview' ? 'hidden lg:flex' : 'flex'
          }`}
        >
          {activeFile ? (
            <textarea
              aria-label={`Code editor for ${activeFile.path}`}
              spellCheck={false}
              autoCapitalize="off"
              autoCorrect="off"
              value={activeFile.content}
              onChange={(e) => onUpdateFile(activeFile.path, e.target.value)}
              style={{ fontSize: `${editorFontSize}px` }}
              className="code-surface flex-1 w-full h-full resize-none bg-[#0D131A] text-[#E6EFF2] p-3.5 sm:p-4 leading-6 outline-none overflow-auto touch-manipulation"
            />
          ) : (
            <div className="flex-1 flex items-center justify-center text-sm text-[#89A0AD]">
              Select a file to begin editing.
            </div>
          )}
        </div>

        {/* Diagnostics Strip */}
        {diagnostics.length > 0 && (
          <div className="border-t border-[#2A3B48] bg-[#141C24] px-4 py-2 max-h-28 overflow-auto space-y-1 shrink-0">
            {diagnostics.map((d, idx) => (
              <div key={`${d.line}-${idx}`} className="flex items-start gap-2 text-xs">
                <span className="font-mono tabular-nums text-[#F59E0B] shrink-0">L{d.line}</span>
                <span className="text-[#E6EFF2]">{d.message}</span>
              </div>
            ))}
          </div>
        )}

        {/* Optional Inline Local Code Helper */}
        {showAssistant && (
          <div className="border-t border-[#283946] bg-[#131C25] p-3 space-y-2 shrink-0">
            <div className="max-h-32 overflow-auto space-y-2">
              {assistantMessages.map((msg, idx) => (
                <div key={idx} className="text-xs space-y-1">
                  <p
                    className={
                      msg.role === 'user'
                        ? 'text-[#78A5A8] font-medium'
                        : 'text-[#DCE7EC] whitespace-pre-wrap'
                    }
                  >
                    {msg.role === 'user' ? `You: ${msg.text}` : msg.text}
                  </p>
                  {msg.suggestion?.updatedContent && msg.suggestion.targetPath && (
                    <button
                      type="button"
                      onClick={() =>
                        onUpdateFile(msg.suggestion!.targetPath!, msg.suggestion!.updatedContent!)
                      }
                      className="px-2.5 py-1 rounded bg-[#3E8790] hover:bg-[#35767E] text-white text-[11px] font-medium cursor-pointer"
                    >
                      Apply Patch: {msg.suggestion.patchLabel}
                    </button>
                  )}
                </div>
              ))}
            </div>
            <form onSubmit={handleAssistantSubmit} className="flex items-center gap-2">
              <input
                type="text"
                value={assistantInput}
                onChange={(e) => setAssistantInput(e.target.value)}
                placeholder='Try "Check for errors" or "Add a button"…'
                className="flex-1 min-w-0 px-3 py-1.5 rounded-lg bg-[#0D131A] border border-[#2C3E4C] text-xs text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
              />
              <button
                type="submit"
                aria-label="Send helper query"
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#3E8790] hover:bg-[#35767E] text-xs font-medium text-white cursor-pointer shrink-0"
              >
                <Send className="w-3.5 h-3.5" />
                <span>Run</span>
              </button>
            </form>
          </div>
        )}
      </div>

      {/* Desktop Live UI Preview Pane */}
      {showPreviewDesktop && (
        <aside className="hidden lg:block w-[400px] xl:w-[440px] border-l border-[#253542] bg-[#0F151C] shrink-0 h-full overflow-hidden">
          <LivePhonePreview
            preview={previewResult}
            fileScreens={fileScreens}
            projectScreens={projectScreens}
            defaultTheme={defaultPreviewTheme}
            onEditInDesigner={(screen) => {
              if (screen) {
                onOpenScreensInDesigner([screen], screen.id, screen.name);
              } else if (activeFile) {
                onOpenInDesigner(activeFile.path);
              }
            }}
            onEditFullAppInDesigner={(screens, activeId) =>
              onOpenScreensInDesigner(screens, activeId, project.name)
            }
          />
        </aside>
      )}
    </div>
  );
};
