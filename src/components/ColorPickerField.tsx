import React, { useEffect, useRef, useState } from 'react';
import { Pipette } from 'lucide-react';
import {
  COLOR_COMBINATIONS,
  hexToHsv,
  hsvToHex,
  normalizeHex,
  type ColorCombination,
} from '../lib/colorUtils';

interface ColorPickerFieldProps {
  label: string;
  value: string;
  onChange: (hex: string) => void;
  allowTransparent?: boolean;
  onApplyCombination?: (combo: ColorCombination) => void;
  comboButtonLabel?: string;
}

export const ColorPickerField: React.FC<ColorPickerFieldProps> = ({
  label,
  value,
  onChange,
  allowTransparent = false,
  onApplyCombination,
  comboButtonLabel = 'Apply',
}) => {
  const isTransparent = value === 'transparent';
  const safeHex = normalizeHex(isTransparent ? '#3E8790' : value);
  const [hsv, setHsv] = useState(() => hexToHsv(safeHex));
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<'picker' | 'combos'>('picker');
  const [hexDraft, setHexDraft] = useState(value);
  const svBoxRef = useRef<HTMLDivElement | null>(null);
  const draggingSvRef = useRef(false);

  useEffect(() => {
    setHexDraft(value);
    if (value !== 'transparent') {
      setHsv(hexToHsv(value));
    }
  }, [value]);

  const updateFromSvPointer = (clientX: number, clientY: number) => {
    const box = svBoxRef.current;
    if (!box) return;
    const rect = box.getBoundingClientRect();
    const s = Math.max(0, Math.min(100, Math.round(((clientX - rect.left) / rect.width) * 100)));
    const v = Math.max(0, Math.min(100, Math.round((1 - (clientY - rect.top) / rect.height) * 100)));
    setHsv((prev) => {
      const next = { ...prev, s, v };
      onChange(hsvToHex(next.h, next.s, next.v));
      return next;
    });
  };

  const handleSvPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    e.stopPropagation();
    draggingSvRef.current = true;
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      // ignore
    }
    updateFromSvPointer(e.clientX, e.clientY);
  };

  const handleSvPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingSvRef.current) return;
    updateFromSvPointer(e.clientX, e.clientY);
  };

  const handleSvPointerEnd = (e: React.PointerEvent<HTMLDivElement>) => {
    draggingSvRef.current = false;
    try {
      if (e.currentTarget.hasPointerCapture(e.pointerId)) {
        e.currentTarget.releasePointerCapture(e.pointerId);
      }
    } catch {
      // ignore
    }
  };

  const pureHueHex = hsvToHex(hsv.h, 100, 100);

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-1.5">
        <button
          type="button"
          aria-label={`Open ${label} color picker`}
          onClick={() => setOpen((v) => !v)}
          className="w-7 h-7 rounded-md border border-[#344A5B] shrink-0 overflow-hidden cursor-pointer relative"
          style={{
            background: isTransparent
              ? 'repeating-conic-gradient(#263542 0% 25%, #121A22 0% 50%) 50% / 10px 10px'
              : safeHex,
          }}
        />

        <input
          type="text"
          aria-label={`${label} hex value`}
          value={hexDraft}
          onChange={(e) => {
            const next = e.target.value;
            setHexDraft(next);
            if (next === 'transparent' && allowTransparent) {
              onChange('transparent');
            } else if (/^#?[0-9a-fA-F]{6}$/.test(next.trim())) {
              onChange(normalizeHex(next));
            }
          }}
          className="flex-1 min-w-0 px-2 py-1 rounded-md bg-[#0D131A] border border-[#253542] text-xs font-mono text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
        />

        <button
          type="button"
          aria-label={`Toggle ${label} picker`}
          onClick={() => setOpen((v) => !v)}
          className={`inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] border cursor-pointer shrink-0 ${
            open
              ? 'bg-[#213341] border-[#4B9BA2] text-white'
              : 'bg-[#16202A] border-[#293B49] text-[#B3C4CE] hover:text-white'
          }`}
        >
          <Pipette className="w-3 h-3" />
          <span>Pick</span>
        </button>
      </div>

      {open && (
        <div className="p-2.5 rounded-lg bg-[#0C1218] border border-[#2A3D4C] space-y-2.5">
          <div className="flex items-center justify-between gap-1">
            <div className="grid grid-cols-2 flex-1 gap-1 p-0.5 rounded bg-[#141D26] border border-[#243441]">
              <button
                type="button"
                onClick={() => setTab('picker')}
                className={`py-1 rounded text-[11px] font-medium cursor-pointer ${
                  tab === 'picker' ? 'bg-[#243746] text-white' : 'text-[#89A0AD]'
                }`}
              >
                Color Picker
              </button>
              <button
                type="button"
                onClick={() => setTab('combos')}
                className={`py-1 rounded text-[11px] font-medium cursor-pointer ${
                  tab === 'combos' ? 'bg-[#243746] text-white' : 'text-[#89A0AD]'
                }`}
              >
                Color Boxes
              </button>
            </div>
            {allowTransparent && (
              <button
                type="button"
                onClick={() => onChange('transparent')}
                className="px-2 py-1 rounded text-[10px] text-[#9BB0BC] hover:text-white bg-[#141D26] border border-[#243441] cursor-pointer"
              >
                Clear
              </button>
            )}
          </div>

          {tab === 'picker' ? (
            <div className="space-y-2.5">
              {/* 2D Saturation / Brightness Spectrum Box */}
              <div
                ref={svBoxRef}
                onPointerDown={handleSvPointerDown}
                onPointerMove={handleSvPointerMove}
                onPointerUp={handleSvPointerEnd}
                onPointerCancel={handleSvPointerEnd}
                onLostPointerCapture={handleSvPointerEnd}
                style={{
                  backgroundColor: pureHueHex,
                  backgroundImage:
                    'linear-gradient(to top, #000000, transparent), linear-gradient(to right, #FFFFFF, transparent)',
                }}
                className="relative w-full h-32 rounded-md border border-[#2C3E4C] cursor-crosshair touch-none select-none overflow-hidden"
              >
                <div
                  style={{
                    left: `${hsv.s}%`,
                    top: `${100 - hsv.v}%`,
                    backgroundColor: safeHex,
                  }}
                  className="pointer-events-none absolute w-3.5 h-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white shadow-md"
                />
              </div>

              {/* Rainbow Hue Slider */}
              <div className="space-y-1">
                <div className="flex items-center justify-between text-[10px] text-[#89A0AD]">
                  <span>Hue</span>
                  <span className="font-mono tabular-nums">{hsv.h}° · {safeHex}</span>
                </div>
                <input
                  type="range"
                  min={0}
                  max={359}
                  value={hsv.h}
                  aria-label={`${label} hue`}
                  onChange={(e) => {
                    const nextH = Number(e.target.value);
                    setHsv((prev) => {
                      const next = { ...prev, h: nextH };
                      onChange(hsvToHex(next.h, next.s, next.v));
                      return next;
                    });
                  }}
                  style={{
                    background:
                      'linear-gradient(to right, #ff0000 0%, #ffff00 17%, #00ff00 33%, #00ffff 50%, #0000ff 67%, #ff00ff 83%, #ff0000 100%)',
                  }}
                  className="w-full h-2.5 rounded-full appearance-none cursor-pointer"
                />
              </div>
            </div>
          ) : (
            <div className="space-y-2 max-h-52 overflow-y-auto pr-0.5">
              {COLOR_COMBINATIONS.map((combo) => (
                <div
                  key={combo.id}
                  className="p-2 rounded-md bg-[#131C25] border border-[#243441] space-y-1.5"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[11px] font-medium text-[#DCE7EC] truncate">
                      {combo.name}
                    </span>
                    {onApplyCombination && (
                      <button
                        type="button"
                        onClick={() => onApplyCombination(combo)}
                        className="px-2 py-0.5 rounded bg-[#233746] hover:bg-[#3E8790] text-[10px] font-medium text-white cursor-pointer shrink-0"
                      >
                        {comboButtonLabel}
                      </button>
                    )}
                  </div>
                  <div className="grid grid-cols-4 gap-1.5">
                    {(
                      [
                        ['BG', combo.background],
                        ['Card', combo.surface],
                        ['Accent', combo.accent],
                        ['Text', combo.text],
                      ] as const
                    ).map(([swatchName, hex]) => (
                      <button
                        key={swatchName}
                        type="button"
                        title={`${combo.name} ${swatchName}: ${hex}`}
                        aria-label={`Use ${combo.name} ${swatchName} ${hex}`}
                        onClick={() => onChange(hex)}
                        className="group flex flex-col items-center gap-0.5 cursor-pointer"
                      >
                        <span
                          style={{ backgroundColor: hex }}
                          className="w-full h-6 rounded border border-[#344A5B] group-hover:ring-1 group-hover:ring-[#78A5A8]"
                        />
                        <span className="text-[9px] text-[#89A0AD]">{swatchName}</span>
                      </button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
