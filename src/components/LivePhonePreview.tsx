import React, { useEffect, useState } from 'react';
import { Moon, Smartphone, Sun } from 'lucide-react';
import type { PreviewNode, PreviewParseResult } from '../lib/studioEngine';

interface LivePhonePreviewProps {
  preview: PreviewParseResult;
  defaultTheme?: 'dark' | 'light';
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

export const LivePhonePreview: React.FC<LivePhonePreviewProps> = ({
  preview,
  defaultTheme = 'dark',
}) => {
  const [theme, setTheme] = useState<'dark' | 'light'>(defaultTheme);
  const [device, setDevice] = useState<DevicePreset['id']>('standard');
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [toggleValues, setToggleValues] = useState<Record<string, boolean>>({});
  const [sliderValues, setSliderValues] = useState<Record<string, number>>({});
  const [radioValues, setRadioValues] = useState<Record<string, string>>({});
  const [lastInteraction, setLastInteraction] = useState<string>('Tap any preview control to test live interaction.');

  useEffect(() => {
    setTheme(defaultTheme);
  }, [defaultTheme]);

  const activePreset = DEVICE_PRESETS.find((d) => d.id === device) ?? DEVICE_PRESETS[1];
  const isLight = theme === 'light';

  const renderNode = (node: PreviewNode) => {
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
            className="w-full py-2.5 px-4 rounded-xl text-xs font-semibold text-white bg-[#3E8790] hover:bg-[#35767E] active:scale-[0.99] transition-all whitespace-nowrap cursor-pointer"
            style={{
              backgroundColor: node.backgroundColor,
              color: node.textColor,
            }}
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
            className={`w-full py-2.5 px-4 rounded-xl text-xs font-semibold border transition-colors whitespace-nowrap cursor-pointer ${
              isLight
                ? 'border-[#3E8790] text-[#265D66] hover:bg-[#E7F2F4]'
                : 'border-[#5D9DA3] text-[#9ED1D5] hover:bg-[#22343F]'
            }`}
          >
            {node.text}
          </button>
        );

      case 'textField': {
        const val = fieldValues[node.id] ?? node.text ?? '';
        return (
          <label key={node.id} className="block space-y-1">
            {node.hint && (
              <span className={`block text-[11px] font-medium ${isLight ? 'text-[#4B5E6B]' : 'text-[#9BB0BC]'}`}>
                {node.hint}
              </span>
            )}
            <input
              type="text"
              value={val}
              placeholder={node.hint ?? 'Enter value'}
              onChange={(e) => {
                setFieldValues((prev) => ({ ...prev, [node.id]: e.target.value }));
                setLastInteraction(`Edited input: "${e.target.value}"`);
              }}
              className={`w-full px-3 py-2 rounded-lg text-xs border outline-none focus:ring-2 focus:ring-[#4B9BA2] ${
                isLight
                  ? 'bg-white border-[#CBD7DE] text-[#142029]'
                  : 'bg-[#121A22] border-[#2D3E4C] text-[#EEF4F6]'
              }`}
            />
          </label>
        );
      }

      case 'switch': {
        const checked = toggleValues[node.id] ?? Boolean(node.checked);
        return (
          <div key={node.id} className="flex items-center justify-between gap-3 py-1">
            <span className={`text-xs ${isLight ? 'text-[#18242D]' : 'text-[#DCE7EC]'}`}>
              {node.text ?? 'Toggle'}
            </span>
            <button
              type="button"
              role="switch"
              aria-checked={checked}
              aria-label={node.text ?? 'Toggle switch'}
              onClick={() => {
                const next = !checked;
                setToggleValues((prev) => ({ ...prev, [node.id]: next }));
                setLastInteraction(`${node.text ?? 'Switch'} set to ${next ? 'ON' : 'OFF'}`);
              }}
              className={`relative w-10 h-5 rounded-full transition-colors cursor-pointer ${
                checked ? 'bg-[#3E8790]' : isLight ? 'bg-[#C4D1D9]' : 'bg-[#334655]'
              }`}
            >
              <span
                className={`block w-4 h-4 rounded-full bg-white transition-transform ${
                  checked ? 'translate-x-5' : 'translate-x-0.5'
                }`}
              />
            </button>
          </div>
        );
      }

      case 'checkbox': {
        const checked = toggleValues[node.id] ?? Boolean(node.checked);
        return (
          <label key={node.id} className="flex items-center gap-2.5 py-1 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={checked}
              onChange={(e) => {
                setToggleValues((prev) => ({ ...prev, [node.id]: e.target.checked }));
                setLastInteraction(`${node.text ?? 'Checkbox'} ${e.target.checked ? 'checked' : 'unchecked'}`);
              }}
              className="w-4 h-4 accent-[#3E8790] rounded"
            />
            <span className={`text-xs ${isLight ? 'text-[#18242D]' : 'text-[#DCE7EC]'}`}>
              {node.text ?? 'Option'}
            </span>
          </label>
        );
      }

      case 'slider': {
        const val = sliderValues[node.id] ?? node.value ?? 60;
        return (
          <div key={node.id} className="space-y-1 py-1">
            <div className="flex items-center justify-between text-[11px]">
              <span className={isLight ? 'text-[#4B5E6B]' : 'text-[#9BB0BC]'}>{node.text ?? 'Slider'}</span>
              <span className="font-mono tabular-nums font-medium">{val}%</span>
            </div>
            <input
              type="range"
              min={0}
              max={100}
              value={val}
              aria-label={node.text ?? 'Preview slider'}
              onChange={(e) => {
                const next = Number(e.target.value);
                setSliderValues((prev) => ({ ...prev, [node.id]: next }));
                setLastInteraction(`Slider adjusted to ${next}%`);
              }}
              className="w-full accent-[#3E8790]"
            />
          </div>
        );
      }

      case 'progress': {
        const pct = Math.min(100, Math.max(0, node.value ?? 68));
        return (
          <div key={node.id} className="space-y-1 py-1">
            <div className={`w-full h-1.5 rounded-full overflow-hidden ${isLight ? 'bg-[#DCE6EC]' : 'bg-[#263643]'}`}>
              <div className="h-full bg-[#4B9BA2]" style={{ width: `${pct}%` }} />
            </div>
          </div>
        );
      }

      case 'radioGroup': {
        const opts = node.options ?? ['Option 1', 'Option 2'];
        const selected = radioValues[node.id] ?? opts[0];
        return (
          <div key={node.id} className="space-y-1.5 py-1">
            {opts.map((opt) => (
              <label key={opt} className="flex items-center gap-2 text-xs cursor-pointer">
                <input
                  type="radio"
                  name={node.id}
                  checked={selected === opt}
                  onChange={() => {
                    setRadioValues((prev) => ({ ...prev, [node.id]: opt }));
                    setLastInteraction(`Selected "${opt}"`);
                  }}
                  className="accent-[#3E8790]"
                />
                <span className={isLight ? 'text-[#18242D]' : 'text-[#DCE7EC]'}>{opt}</span>
              </label>
            ))}
          </div>
        );
      }

      case 'divider':
        return (
          <hr
            key={node.id}
            className={isLight ? 'border-[#D9E3E9] my-1' : 'border-[#2A3B48] my-1'}
          />
        );

      default:
        return null;
    }
  };

  return (
    <div className="flex flex-col h-full">
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 border-b border-[#253542] bg-[#141D26]">
        <div className="flex items-center gap-2 text-xs text-[#9BB0BC]">
          <Smartphone className="w-3.5 h-3.5 text-[#78A5A8]" aria-hidden="true" />
          <span className="font-medium text-[#EEF4F6]">{preview.screenTitle}</span>
          <span aria-hidden="true">·</span>
          <span>{preview.sourceType === 'none' ? 'Waiting for layout' : preview.sourceType.toUpperCase()}</span>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center p-0.5 rounded-lg bg-[#0F151C] border border-[#253542]">
            {DEVICE_PRESETS.map((preset) => (
              <button
                key={preset.id}
                type="button"
                onClick={() => setDevice(preset.id)}
                className={`px-2 py-1 text-[11px] font-mono tabular-nums rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                  device === preset.id
                    ? 'bg-[#253746] text-[#EEF4F6]'
                    : 'text-[#89A0AD] hover:text-[#EEF4F6]'
                }`}
              >
                {preset.label}
              </button>
            ))}
          </div>

          <button
            type="button"
            onClick={() => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'))}
            aria-label={isLight ? 'Switch preview to dark theme' : 'Switch preview to light theme'}
            className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-[#1C2834] border border-[#2C3E4C] text-[#DCE7EC] hover:bg-[#233240] transition-colors whitespace-nowrap cursor-pointer"
          >
            {isLight ? <Moon className="w-3.5 h-3.5" /> : <Sun className="w-3.5 h-3.5" />}
            <span>{isLight ? 'Dark' : 'Light'}</span>
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto p-5 flex flex-col items-center justify-start bg-[#0C1218]">
        <div
          className={`rounded-[28px] border-[6px] border-[#253441] shadow-xl overflow-hidden transition-all duration-200 ${
            isLight ? 'bg-[#F8FAFB] text-[#142029]' : 'bg-[#141D25] text-[#EEF4F6]'
          }`}
          style={{ width: `${activePreset.widthPx}px`, maxWidth: '100%' }}
        >
          {/* Android status bar */}
          <div
            className={`flex items-center justify-between px-5 py-2 text-[10px] font-mono tabular-nums ${
              isLight ? 'bg-[#E5EDF2] text-[#354B59]' : 'bg-[#101820] text-[#8DA2AF]'
            }`}
          >
            <span>12:30</span>
            <span>5G · 100%</span>
          </div>

          {/* Screen body */}
          <div className="p-4 min-h-[430px] flex flex-col gap-3">
            {preview.nodes.length > 0 ? (
              preview.nodes.map(renderNode)
            ) : (
              <div className="flex-1 flex flex-col items-center justify-center text-center px-4 py-10 space-y-2">
                <p className={`text-sm font-medium ${isLight ? 'text-[#283B47]' : 'text-[#DCE7EC]'}`}>
                  No previewable UI in active file
                </p>
                <p className={`text-xs leading-relaxed ${isLight ? 'text-[#5D7280]' : 'text-[#8DA2AF]'}`}>
                  {preview.warnings[0] ?? 'Select an XML layout or @Composable Kotlin file to preview its UI live.'}
                </p>
              </div>
            )}
          </div>

          {/* Android navigation gesture bar */}
          <div className={`py-2 flex justify-center ${isLight ? 'bg-[#F0F4F7]' : 'bg-[#101820]'}`}>
            <div className={`w-24 h-1 rounded-full ${isLight ? 'bg-[#BAC8D1]' : 'bg-[#364958]'}`} />
          </div>
        </div>

        <p className="mt-3 text-xs text-[#8DA2AF] text-center max-w-sm">
          {lastInteraction}
        </p>
      </div>
    </div>
  );
};
