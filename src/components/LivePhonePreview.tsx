import React, { useEffect, useMemo, useState } from 'react';
import { Layers, Moon, PenTool, Smartphone, Sun } from 'lucide-react';
import type { DesignerElement, DesignerScreen } from '../lib/apkDesignerEngine';
import type { PreviewNode, PreviewParseResult } from '../lib/studioEngine';

interface LivePhonePreviewProps {
  preview: PreviewParseResult;
  fileScreens?: DesignerScreen[];
  projectScreens?: DesignerScreen[];
  defaultTheme?: 'dark' | 'light';
  onEditInDesigner?: (screen?: DesignerScreen) => void;
  onEditFullAppInDesigner?: (screens: DesignerScreen[], activeScreenId?: string) => void;
}

type DevicePreset = {
  id: 'compact' | 'standard' | 'large';
  label: string;
  widthPx: number;
};

const DEVICE_PRESETS: DevicePreset[] = [
  { id: 'compact', label: '360dp', widthPx: 300 },
  { id: 'standard', label: '393dp', widthPx: 332 },
  { id: 'large', label: '412dp', widthPx: 360 },
];

function categoryTag(cat?: DesignerScreen['screenCategory']): string {
  switch (cat) {
    case 'tab':
      return 'Tab';
    case 'dialog':
      return 'Window';
    case 'activity':
      return 'Activity';
    case 'xml':
      return 'XML';
    default:
      return 'Page';
  }
}

export const LivePhonePreview: React.FC<LivePhonePreviewProps> = ({
  preview,
  fileScreens = [],
  projectScreens = [],
  defaultTheme = 'dark',
  onEditInDesigner,
  onEditFullAppInDesigner,
}) => {
  const [theme, setTheme] = useState<'dark' | 'light'>(defaultTheme);
  const [device, setDevice] = useState<DevicePreset['id']>('standard');
  const [scope, setScope] = useState<'file' | 'full-app'>('file');
  const [activeScreenIdx, setActiveScreenIdx] = useState(0);

  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [toggleValues, setToggleValues] = useState<Record<string, boolean>>({});
  const [sliderValues, setSliderValues] = useState<Record<string, number>>({});
  const [radioValues, setRadioValues] = useState<Record<string, string>>({});
  const [lastInteraction, setLastInteraction] = useState<string>(
    'Tap any tab, tile, or control to test live navigation and interaction.'
  );

  useEffect(() => {
    setTheme(defaultTheme);
  }, [defaultTheme]);

  const effectiveScreens = useMemo(() => {
    if (scope === 'full-app' && projectScreens.length > 0) return projectScreens;
    if (fileScreens.length > 0) return fileScreens;
    return projectScreens;
  }, [scope, fileScreens, projectScreens]);

  useEffect(() => {
    setActiveScreenIdx(0);
  }, [scope, fileScreens.length, preview.screenTitle]);

  const activeScreen = effectiveScreens[activeScreenIdx] ?? effectiveScreens[0] ?? null;
  const activePreset = DEVICE_PRESETS.find((d) => d.id === device) ?? DEVICE_PRESETS[1];
  const isLight = theme === 'light';

  // Interactive navigation helper: match clicked tile/tab label to another extracted page/window!
  const tryNavigateByLabel = (rawLabel: string) => {
    const clean = rawLabel
      .replace(/^[^\w]+/, '')
      .trim()
      .toLowerCase();
    if (!clean) return false;

    const matchIdx = effectiveScreens.findIndex((s) => {
      const sName = s.name.toLowerCase();
      if (sName === clean || sName.includes(clean) || clean.includes(sName)) return true;
      if (clean.includes('preference') && sName.includes('setting')) return true;
      if (clean.includes('create project') && sName.includes('quick start')) return true;
      if (clean.includes('device') && sName.includes('device')) return true;
      if (clean.includes('decompile') && sName.includes('decompile')) return true;
      if (clean === 'editor' && sName.includes('workspace')) return true;
      if (clean === 'tools' && sName.includes('apk tools')) return true;
      if (clean === 'preview' && sName.includes('preview')) return true;
      return false;
    });

    if (matchIdx !== -1 && matchIdx !== activeScreenIdx) {
      setActiveScreenIdx(matchIdx);
      setLastInteraction(`Navigated to "${effectiveScreens[matchIdx].name}"`);
      return true;
    }
    return false;
  };

  const renderDesignerElement = (el: DesignerElement, scale: number) => {
    if (!el.visible) return null;

    const justify =
      el.textAlign === 'center'
        ? 'center'
        : el.textAlign === 'right'
        ? 'flex-end'
        : 'flex-start';

    const baseStyle: React.CSSProperties = {
      position: 'absolute',
      left: `${el.x * scale}px`,
      top: `${el.y * scale}px`,
      width: `${el.width * scale}px`,
      height: `${el.height * scale}px`,
      opacity: el.opacity / 100,
      transform: el.rotation ? `rotate(${el.rotation}deg)` : undefined,
      borderRadius: `${Math.round(el.borderRadius * scale)}px`,
      boxShadow: el.shadow > 0 ? `0 ${Math.round(el.shadow * 0.5)}px ${Math.round(el.shadow * 1.4)}px rgba(0,0,0,0.32)` : undefined,
    };

    if (el.kind === 'bottomNav') {
      const tabs = (el.text || 'Home|Explore|Profile').split('|').filter(Boolean);
      const activeTabLabel = el.placeholder || tabs[0];
      return (
        <div
          key={el.id}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor,
            border: el.strokeWidth > 0 ? `${el.strokeWidth}px solid ${el.strokeColor}` : undefined,
          }}
          className="flex items-center justify-around gap-1 px-1.5"
        >
          {tabs.map((tab, i) => {
            const isTabActive = tab === activeTabLabel;
            const parts = tab.split(/\s+/);
            const icon = parts.length > 1 ? parts[0] : '';
            const caption = parts.length > 1 ? parts.slice(1).join(' ') : tab;
            return (
              <button
                key={`${el.id}-tab-${i}`}
                type="button"
                onClick={() => {
                  const navigated = tryNavigateByLabel(caption);
                  if (!navigated && i < effectiveScreens.length) {
                    setActiveScreenIdx(i);
                    setLastInteraction(`Switched tab to "${effectiveScreens[i].name}"`);
                  }
                }}
                style={{
                  backgroundColor: isTabActive ? '#568B91' : '#344653',
                  borderRadius: `${Math.round(10 * scale)}px`,
                  fontSize: `${Math.max(8, Math.round(9.5 * scale))}px`,
                }}
                className="flex-1 h-[82%] flex flex-col items-center justify-center text-[#EEF4F6] font-semibold leading-tight px-0.5 cursor-pointer hover:brightness-110 transition-all overflow-hidden"
              >
                {icon && <span style={{ fontSize: `${Math.max(9, Math.round(11 * scale))}px` }}>{icon}</span>}
                <span className="truncate max-w-full">{caption}</span>
              </button>
            );
          })}
        </div>
      );
    }

    if (el.kind === 'topBar') {
      return (
        <div
          key={el.id}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor,
            color: el.textColor,
            fontSize: `${Math.max(10, Math.round(el.fontSize * scale))}px`,
            justifyContent: justify,
          }}
          className="flex items-center px-3 font-display font-semibold border-b border-white/10"
        >
          <span className="truncate">{el.text}</span>
        </div>
      );
    }

    if (el.kind === 'card' || el.kind === 'rectangle' || el.kind === 'circle') {
      const clickableTile = el.name && !['Card', 'Panel', 'DialogWindow'].includes(el.name);
      return (
        <div
          key={el.id}
          onClick={() => {
            if (clickableTile) {
              if (!tryNavigateByLabel(el.name)) {
                setLastInteraction(`Selected tile "${el.name}"`);
              }
            }
          }}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor,
            border:
              el.strokeWidth > 0 && el.strokeColor !== 'transparent'
                ? `${el.strokeWidth}px solid ${el.strokeColor}`
                : undefined,
            color: el.textColor,
            fontSize: `${Math.max(10, Math.round(el.fontSize * scale))}px`,
            fontWeight: el.fontWeight,
            justifyContent: justify,
            textAlign: el.textAlign,
          }}
          className={`flex items-center px-2 ${clickableTile ? 'cursor-pointer hover:brightness-105 transition-all' : ''}`}
        >
          {el.text ? <span className="w-full truncate">{el.text}</span> : null}
        </div>
      );
    }

    if (el.kind === 'heading' || el.kind === 'text') {
      return (
        <div
          key={el.id}
          onClick={() => {
            if (el.text) tryNavigateByLabel(el.text);
          }}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor !== 'transparent' ? el.fillColor : undefined,
            border:
              el.strokeWidth > 0 && el.strokeColor !== 'transparent'
                ? `${el.strokeWidth}px solid ${el.strokeColor}`
                : undefined,
            color: el.textColor,
            fontSize: `${Math.max(8, Math.round(el.fontSize * scale))}px`,
            fontWeight: el.fontWeight,
            textAlign: el.textAlign,
            justifyContent: justify,
          }}
          className="flex items-center leading-snug overflow-hidden whitespace-pre-wrap"
        >
          <span className="w-full break-words">{el.text}</span>
        </div>
      );
    }

    if (el.kind === 'button' || el.kind === 'outlinedButton' || el.kind === 'fab') {
      return (
        <button
          key={el.id}
          type="button"
          onClick={() => {
            if (el.text === '‹') {
              setActiveScreenIdx(0);
              setLastInteraction('Returned to Home');
              return;
            }
            if (el.text === '→') {
              if (!tryNavigateByLabel('Workspace') && effectiveScreens.length > 1) {
                setActiveScreenIdx(1);
              }
              return;
            }
            if (!tryNavigateByLabel(el.text)) {
              setLastInteraction(`Pressed "${el.text}"`);
            }
          }}
          style={{
            ...baseStyle,
            backgroundColor: el.kind === 'outlinedButton' ? 'transparent' : el.fillColor,
            border:
              el.kind === 'outlinedButton'
                ? `${Math.max(1, el.strokeWidth)}px solid ${el.strokeColor || '#4B9BA2'}`
                : el.strokeWidth > 0 && el.strokeColor !== 'transparent'
                ? `${el.strokeWidth}px solid ${el.strokeColor}`
                : undefined,
            color: el.textColor,
            fontSize: `${Math.max(9, Math.round(el.fontSize * scale))}px`,
            fontWeight: el.fontWeight,
            justifyContent: justify,
          }}
          className="flex items-center px-2 cursor-pointer hover:brightness-110 active:scale-[0.99] transition-all overflow-hidden"
        >
          <span className="truncate">{el.text}</span>
        </button>
      );
    }

    if (el.kind === 'input') {
      const val = fieldValues[el.id] ?? el.text ?? '';
      return (
        <input
          key={el.id}
          type="text"
          value={val}
          placeholder={el.placeholder ?? 'Enter text'}
          aria-label={el.name || el.placeholder || 'Preview input'}
          onChange={(e) => {
            setFieldValues((prev) => ({ ...prev, [el.id]: e.target.value }));
            setLastInteraction(`Typed "${e.target.value}" in ${el.name}`);
          }}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor,
            border: `${Math.max(1, el.strokeWidth)}px solid ${el.strokeColor || '#344A5B'}`,
            color: el.textColor,
            fontSize: `${Math.max(9, Math.round(el.fontSize * scale))}px`,
          }}
          className="px-2.5 outline-none focus:ring-1 focus:ring-[#4B9BA2]"
        />
      );
    }

    if (el.kind === 'switch') {
      const checked = toggleValues[el.id] ?? Boolean(el.checked);
      return (
        <div
          key={el.id}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor,
            color: el.textColor,
            fontSize: `${Math.max(9, Math.round(el.fontSize * scale))}px`,
          }}
          className="flex items-center justify-between px-2.5"
        >
          <span className="truncate">{el.text}</span>
          <button
            type="button"
            role="switch"
            aria-checked={checked}
            aria-label={el.text || 'Toggle switch'}
            onClick={() => {
              const next = !checked;
              setToggleValues((prev) => ({ ...prev, [el.id]: next }));
              setLastInteraction(`${el.text} set to ${next ? 'ON' : 'OFF'}`);
            }}
            className={`w-8 h-4 rounded-full p-0.5 transition-colors cursor-pointer shrink-0 ${
              checked ? 'bg-[#3E8790]' : 'bg-[#364A58]'
            }`}
          >
            <span
              className={`block w-3 h-3 rounded-full bg-white transition-transform ${
                checked ? 'translate-x-4' : 'translate-x-0'
              }`}
            />
          </button>
        </div>
      );
    }

    if (el.kind === 'progress') {
      const pct = Math.min(100, Math.max(4, el.progress ?? 65));
      return (
        <div
          key={el.id}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor || '#1E2C37',
          }}
          className="overflow-hidden"
        >
          <div
            style={{
              width: `${pct}%`,
              height: '100%',
              backgroundColor: el.strokeColor || '#78A5A8',
              borderRadius: 'inherit',
            }}
          />
        </div>
      );
    }

    if (el.kind === 'image') {
      return (
        <div
          key={el.id}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor || '#263643',
            color: '#9BB0BC',
            fontSize: `${Math.max(8, Math.round(10 * scale))}px`,
          }}
          className="flex items-center justify-center border border-white/10 font-medium text-center px-1"
        >
          {el.imageUrl ? (
            <img src={el.imageUrl} alt={el.text} className="w-full h-full object-cover" />
          ) : (
            <span className="truncate">{el.text || 'Image'}</span>
          )}
        </div>
      );
    }

    if (el.kind === 'divider') {
      return (
        <div
          key={el.id}
          style={{
            ...baseStyle,
            backgroundColor: el.fillColor || '#2E4252',
          }}
        />
      );
    }

    return null;
  };

  const renderLegacyNode = (node: PreviewNode) => {
    switch (node.kind) {
      case 'topBar':
        return (
          <div
            key={node.id}
            className={`-mx-4 -mt-4 mb-2 px-4 py-3 border-b font-display font-semibold text-sm ${
              isLight
                ? 'bg-[#EEF3F6] text-[#142029] border-[#D6E1E7]'
                : 'bg-[#1D2A35] text-[#EEF4F6] border-[#2C3E4C]'
            }`}
          >
            {node.text}
          </div>
        );
      case 'text':
        return (
          <p
            key={node.id}
            className="leading-relaxed break-words"
            style={{
              color: node.textColor ?? (isLight ? '#18242D' : '#E6EFF2'),
              backgroundColor: node.backgroundColor,
              fontSize: node.fontSize ? `${Math.min(Math.max(node.fontSize * 0.85, 11), 24)}px` : '13px',
              fontWeight: node.bold ? 600 : 400,
            }}
          >
            {node.text}
          </p>
        );
      case 'button':
        return (
          <button
            key={node.id}
            type="button"
            onClick={() => setLastInteraction(`Pressed "${node.text}"`)}
            className="w-full py-2.5 px-4 rounded-xl text-xs font-semibold text-white bg-[#3E8790] hover:bg-[#35767E] transition-all cursor-pointer"
          >
            {node.text}
          </button>
        );
      case 'outlinedButton':
        return (
          <button
            key={node.id}
            type="button"
            onClick={() => setLastInteraction(`Pressed "${node.text}"`)}
            className="w-full py-2.5 px-4 rounded-xl text-xs font-semibold border border-[#5D9DA3] text-[#9ED1D5] cursor-pointer"
          >
            {node.text}
          </button>
        );
      case 'textField': {
        const val = fieldValues[node.id] ?? node.text ?? '';
        return (
          <input
            key={node.id}
            type="text"
            value={val}
            placeholder={node.hint ?? 'Enter value'}
            onChange={(e) => setFieldValues((prev) => ({ ...prev, [node.id]: e.target.value }))}
            className="w-full px-3 py-2 rounded-lg text-xs border bg-[#121A22] border-[#2D3E4C] text-[#EEF4F6]"
          />
        );
      }
      case 'switch': {
        const checked = toggleValues[node.id] ?? Boolean(node.checked);
        return (
          <div key={node.id} className="flex items-center justify-between py-1">
            <span className="text-xs">{node.text ?? 'Toggle'}</span>
            <button
              type="button"
              role="switch"
              aria-checked={checked}
              onClick={() => setToggleValues((prev) => ({ ...prev, [node.id]: !checked }))}
              className={`w-10 h-5 rounded-full p-0.5 cursor-pointer ${checked ? 'bg-[#3E8790]' : 'bg-[#334655]'}`}
            >
              <span className={`block w-4 h-4 rounded-full bg-white ${checked ? 'translate-x-5' : ''}`} />
            </button>
          </div>
        );
      }
      case 'slider': {
        const val = sliderValues[node.id] ?? node.value ?? 60;
        return (
          <input
            key={node.id}
            type="range"
            min={0}
            max={100}
            value={val}
            onChange={(e) => setSliderValues((prev) => ({ ...prev, [node.id]: Number(e.target.value) }))}
            className="w-full accent-[#3E8790]"
          />
        );
      }
      case 'radioGroup': {
        const opts = node.options ?? ['Option 1', 'Option 2'];
        const selected = radioValues[node.id] ?? opts[0];
        return (
          <div key={node.id} className="space-y-1">
            {opts.map((opt) => (
              <label key={opt} className="flex items-center gap-2 text-xs">
                <input
                  type="radio"
                  name={node.id}
                  checked={selected === opt}
                  onChange={() => setRadioValues((prev) => ({ ...prev, [node.id]: opt }))}
                />
                <span>{opt}</span>
              </label>
            ))}
          </div>
        );
      }
      case 'progress':
        return (
          <div key={node.id} className="w-full h-1.5 rounded-full bg-[#263643] overflow-hidden">
            <div className="h-full bg-[#4B9BA2]" style={{ width: `${node.value ?? 68}%` }} />
          </div>
        );
      case 'divider':
        return <hr key={node.id} className="border-[#2A3B48] my-1" />;
      default:
        return null;
    }
  };

  const phoneInnerW = activePreset.widthPx - 12;
  const screenScale = activeScreen ? phoneInnerW / activeScreen.width : 1;
  const scaledHeight = activeScreen ? Math.round(activeScreen.height * screenScale) : 460;

  return (
    <div className="flex flex-col h-full">
      {/* Top Preview Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 border-b border-[#253542] bg-[#141D26]">
        <div className="flex items-center gap-2 text-xs text-[#9BB0BC] min-w-0">
          <Smartphone className="w-3.5 h-3.5 text-[#78A5A8] shrink-0" aria-hidden="true" />
          <span className="font-medium text-[#EEF4F6] truncate">
            {activeScreen ? activeScreen.name : preview.screenTitle}
          </span>
          {effectiveScreens.length > 0 && (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-[#1E2E3A] text-[#9ED1D5] font-mono shrink-0">
              {effectiveScreens.length} screens
            </span>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          {projectScreens.length > fileScreens.length && (
            <div className="flex items-center p-0.5 rounded-lg bg-[#0F151C] border border-[#253542]">
              <button
                type="button"
                onClick={() => setScope('file')}
                className={`px-2 py-1 text-[11px] rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                  scope === 'file' ? 'bg-[#253746] text-[#EEF4F6]' : 'text-[#89A0AD] hover:text-[#EEF4F6]'
                }`}
              >
                File ({fileScreens.length})
              </button>
              <button
                type="button"
                onClick={() => setScope('full-app')}
                className={`px-2 py-1 text-[11px] rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                  scope === 'full-app' ? 'bg-[#253746] text-[#EEF4F6]' : 'text-[#89A0AD] hover:text-[#EEF4F6]'
                }`}
              >
                Full App ({projectScreens.length})
              </button>
            </div>
          )}

          <div className="flex items-center p-0.5 rounded-lg bg-[#0F151C] border border-[#253542]">
            {DEVICE_PRESETS.map((preset) => (
              <button
                key={preset.id}
                type="button"
                onClick={() => setDevice(preset.id)}
                className={`px-1.5 py-1 text-[10px] font-mono tabular-nums rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                  device === preset.id
                    ? 'bg-[#253746] text-[#EEF4F6]'
                    : 'text-[#89A0AD] hover:text-[#EEF4F6]'
                }`}
              >
                {preset.label}
              </button>
            ))}
          </div>

          {onEditFullAppInDesigner && effectiveScreens.length > 1 && (
            <button
              type="button"
              onClick={() => onEditFullAppInDesigner(effectiveScreens, activeScreen?.id)}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold bg-[#3E8790] hover:bg-[#35767E] text-white transition-colors whitespace-nowrap cursor-pointer"
            >
              <Layers className="w-3.5 h-3.5" />
              <span>Edit All ({effectiveScreens.length})</span>
            </button>
          )}

          {onEditInDesigner && (
            <button
              type="button"
              onClick={() => onEditInDesigner(activeScreen ?? undefined)}
              className={`inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold transition-colors whitespace-nowrap cursor-pointer ${
                effectiveScreens.length > 1 && onEditFullAppInDesigner
                  ? 'bg-[#1D2D39] border border-[#324A5C] text-[#DCE7EC] hover:bg-[#263A49]'
                  : 'bg-[#3E8790] hover:bg-[#35767E] text-white'
              }`}
            >
              <PenTool className="w-3.5 h-3.5" />
              <span>Edit in Designer</span>
            </button>
          )}

          <button
            type="button"
            onClick={() => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'))}
            aria-label={isLight ? 'Switch preview to dark theme' : 'Switch preview to light theme'}
            className="inline-flex items-center gap-1 px-2 py-1.5 rounded-lg text-[11px] font-medium bg-[#1C2834] border border-[#2C3E4C] text-[#DCE7EC] hover:bg-[#233240] transition-colors whitespace-nowrap cursor-pointer"
          >
            {isLight ? <Moon className="w-3.5 h-3.5" /> : <Sun className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>

      {/* Multi-Page / Tab / Window Switcher Strip */}
      {effectiveScreens.length > 1 && (
        <div
          aria-label="Preview screens, tabs and windows"
          className="flex items-center gap-1.5 px-3 py-2 border-b border-[#20303C] bg-[#101820] overflow-x-auto"
        >
          {effectiveScreens.map((scr, idx) => {
            const active = idx === activeScreenIdx;
            return (
              <button
                key={scr.id}
                type="button"
                onClick={() => {
                  setActiveScreenIdx(idx);
                  setLastInteraction(`Viewing ${categoryTag(scr.screenCategory)}: "${scr.name}"`);
                }}
                className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium transition-colors whitespace-nowrap shrink-0 cursor-pointer border ${
                  active
                    ? 'bg-[#223945] border-[#4B9BA2] text-[#F1F7F9]'
                    : 'bg-[#15202A] border-[#243441] text-[#9AB0BC] hover:text-[#EEF4F6] hover:border-[#344A5B]'
                }`}
              >
                <span
                  className={`text-[9px] uppercase tracking-wider px-1 py-0.5 rounded font-mono ${
                    scr.screenCategory === 'dialog'
                      ? 'bg-[#3C2F1E] text-[#F5C77A]'
                      : scr.screenCategory === 'tab'
                      ? 'bg-[#1B3940] text-[#8CD0D6]'
                      : 'bg-[#22303D] text-[#9FB4C2]'
                  }`}
                >
                  {categoryTag(scr.screenCategory)}
                </span>
                <span>{scr.name}</span>
              </button>
            );
          })}
        </div>
      )}

      {/* Interactive Phone Frame */}
      <div className="flex-1 overflow-auto p-4 flex flex-col items-center justify-start bg-[#0C1218]">
        <div
          className="rounded-[28px] border-[6px] border-[#253441] shadow-xl overflow-hidden transition-all duration-200 shrink-0"
          style={{
            width: `${activePreset.widthPx}px`,
            maxWidth: '100%',
            backgroundColor: activeScreen
              ? isLight && activeScreen.backgroundColor === '#10171F'
                ? '#F8FAFB'
                : activeScreen.backgroundColor
              : isLight
              ? '#F8FAFB'
              : '#141D25',
          }}
        >
          {activeScreen && activeScreen.elements.length > 0 ? (
            <div
              className="relative overflow-hidden select-none"
              style={{
                width: `${phoneInnerW}px`,
                height: `${scaledHeight}px`,
              }}
            >
              {/* Status bar */}
              <div className="absolute top-0 left-0 right-0 h-7 px-4 flex items-center justify-between text-[9px] font-mono text-[#9AB0BC] z-10 pointer-events-none">
                <span>12:30</span>
                <span>5G · 100%</span>
              </div>

              {activeScreen.elements.map((el) => renderDesignerElement(el, screenScale))}
            </div>
          ) : (
            <>
              <div
                className={`flex items-center justify-between px-5 py-2 text-[10px] font-mono tabular-nums ${
                  isLight ? 'bg-[#E5EDF2] text-[#354B59]' : 'bg-[#101820] text-[#8DA2AF]'
                }`}
              >
                <span>12:30</span>
                <span>5G · 100%</span>
              </div>
              <div className="p-4 min-h-[430px] flex flex-col gap-3">
                {preview.nodes.length > 0 ? (
                  preview.nodes.map(renderLegacyNode)
                ) : (
                  <div className="flex-1 flex flex-col items-center justify-center text-center px-4 py-10 space-y-2">
                    <p className={`text-sm font-medium ${isLight ? 'text-[#283B47]' : 'text-[#DCE7EC]'}`}>
                      No previewable UI in active file
                    </p>
                    <p className={`text-xs leading-relaxed ${isLight ? 'text-[#5D7280]' : 'text-[#8DA2AF]'}`}>
                      {preview.warnings[0] ?? 'Select an Activity, XML layout, or @Composable Kotlin file.'}
                    </p>
                  </div>
                )}
              </div>
            </>
          )}

          {/* Android navigation gesture bar */}
          <div className="py-2 flex justify-center bg-black/25">
            <div className="w-24 h-1 rounded-full bg-[#4A6070]" />
          </div>
        </div>

        <p className="mt-3 text-xs text-[#8DA2AF] text-center max-w-sm">
          {lastInteraction}
        </p>
      </div>
    </div>
  );
};
