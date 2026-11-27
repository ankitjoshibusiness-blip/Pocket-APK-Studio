import React, { useRef, useState } from 'react';
import {
  AlignCenter,
  AlignCenterHorizontal,
  AlignCenterVertical,
  AlignEndHorizontal,
  AlignEndVertical,
  AlignLeft,
  AlignRight,
  AlignStartHorizontal,
  AlignStartVertical,
  ArrowDown,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  Copy,
  ImagePlus,
  Lock,
  Trash2,
  Unlock,
  X,
} from 'lucide-react';
import type {
  AlignAction,
  DesignerElement,
  DesignerScreen,
} from '../lib/apkDesignerEngine';
import { COLOR_COMBINATIONS, type ColorCombination } from '../lib/colorUtils';
import { ColorPickerField } from './ColorPickerField';

interface DesignerInspectorProps {
  screen: DesignerScreen;
  selectedElement: DesignerElement | null;
  onUpdateScreen: (patch: Partial<DesignerScreen>) => void;
  onApplyThemeToScreen: (combo: ColorCombination) => void;
  onUpdateElement: (id: string, patch: Partial<DesignerElement>) => void;
  onAlignElement: (id: string, action: AlignAction) => void;
  onReorderElement: (id: string, dir: 'up' | 'down') => void;
  onDuplicateElement: (id: string) => void;
  onDeleteElement: (id: string) => void;
  onClose: () => void;
}

interface AccordionSectionProps {
  title: string;
  defaultOpen?: boolean;
  rightSlot?: React.ReactNode;
  children: React.ReactNode;
}

const AccordionSection: React.FC<AccordionSectionProps> = ({
  title,
  defaultOpen = true,
  rightSlot,
  children,
}) => {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <section className="border-t border-[#22313D] pt-2.5">
      <div className="flex items-center justify-between gap-2">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          className="flex items-center gap-1.5 text-[11px] font-semibold text-[#A6BAC6] hover:text-[#EEF4F6] cursor-pointer py-0.5"
        >
          {open ? (
            <ChevronDown className="w-3.5 h-3.5 text-[#78A5A8]" />
          ) : (
            <ChevronRight className="w-3.5 h-3.5 text-[#78A5A8]" />
          )}
          <span>{title}</span>
        </button>
        {rightSlot}
      </div>
      {open && <div className="mt-2.5 space-y-2.5">{children}</div>}
    </section>
  );
};

function buildComboPatchForElement(
  el: DesignerElement,
  combo: ColorCombination
): Partial<DesignerElement> {
  switch (el.kind) {
    case 'button':
    case 'fab':
    case 'circle':
      return {
        fillColor: combo.accent,
        strokeColor: combo.accent,
        textColor: combo.onAccent,
      };
    case 'outlinedButton':
      return {
        fillColor: 'transparent',
        strokeColor: combo.accent,
        textColor: combo.accent,
      };
    case 'heading':
      return {
        textColor: combo.text,
      };
    case 'text':
      return {
        textColor: combo.mutedText,
      };
    case 'progress':
      return {
        fillColor: combo.surface,
        strokeColor: combo.accent,
      };
    default:
      return {
        fillColor: combo.surface,
        strokeColor: combo.accent,
        textColor: combo.text,
      };
  }
}

export const DesignerInspector: React.FC<DesignerInspectorProps> = ({
  screen,
  selectedElement,
  onUpdateScreen,
  onApplyThemeToScreen,
  onUpdateElement,
  onAlignElement,
  onReorderElement,
  onDuplicateElement,
  onDeleteElement,
  onClose,
}) => {
  const imageInputRef = useRef<HTMLInputElement | null>(null);

  const handlePhotoUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !selectedElement) return;
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        onUpdateElement(selectedElement.id, {
          imageUrl: reader.result,
          name: file.name.replace(/\.[^.]+$/, ''),
        });
      }
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  if (!selectedElement) {
    return (
      <div className="h-full flex flex-col bg-[#121A22]">
        <div className="flex items-center justify-between px-3.5 py-2.5 border-b border-[#22313D] shrink-0">
          <span className="text-xs font-display font-semibold text-[#EEF4F6]">Screen Style</span>
          <button
            type="button"
            aria-label="Collapse inspector panel"
            onClick={onClose}
            className="p-1 rounded text-[#89A0AD] hover:text-white hover:bg-[#1E2B37] cursor-pointer"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-3.5 space-y-3.5">
          <label className="block space-y-1">
            <span className="text-[11px] text-[#9BB0BC]">Screen name</span>
            <input
              type="text"
              value={screen.name}
              onChange={(e) => onUpdateScreen({ name: e.target.value })}
              className="w-full px-2.5 py-1.5 rounded-md bg-[#0D131A] border border-[#273846] text-xs text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
            />
          </label>

          <div className="grid grid-cols-2 gap-2">
            <label className="block space-y-1">
              <span className="text-[11px] text-[#89A0AD]">Width</span>
              <input
                type="number"
                min={280}
                max={900}
                value={screen.width}
                onChange={(e) =>
                  onUpdateScreen({
                    width: Math.max(280, Math.min(900, Number(e.target.value) || 390)),
                  })
                }
                className="w-full px-2 py-1.5 rounded-md bg-[#0D131A] border border-[#273846] text-xs font-mono tabular-nums text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
              />
            </label>
            <label className="block space-y-1">
              <span className="text-[11px] text-[#89A0AD]">Height</span>
              <input
                type="number"
                min={500}
                max={1400}
                value={screen.height}
                onChange={(e) =>
                  onUpdateScreen({
                    height: Math.max(500, Math.min(1400, Number(e.target.value) || 800)),
                  })
                }
                className="w-full px-2 py-1.5 rounded-md bg-[#0D131A] border border-[#273846] text-xs font-mono tabular-nums text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
              />
            </label>
          </div>

          <AccordionSection title="Canvas Background" defaultOpen>
            <ColorPickerField
              label="Canvas background"
              value={screen.backgroundColor}
              onChange={(hex) => onUpdateScreen({ backgroundColor: hex })}
              onApplyCombination={onApplyThemeToScreen}
              comboButtonLabel="Apply Theme"
            />
          </AccordionSection>

          <AccordionSection title="Color Combination Boxes" defaultOpen>
            <p className="text-[11px] text-[#89A0AD] leading-snug">
              Tap a color box to apply matching background, card, accent &amp; text colors.
            </p>
            <div className="grid grid-cols-2 gap-2">
              {COLOR_COMBINATIONS.map((combo) => (
                <button
                  key={combo.id}
                  type="button"
                  onClick={() => onApplyThemeToScreen(combo)}
                  className="text-left p-2 rounded-lg bg-[#0D131A] hover:bg-[#17222D] border border-[#253542] hover:border-[#4B9BA2] transition-colors cursor-pointer space-y-1.5"
                >
                  <div className="grid grid-cols-4 gap-0.5 h-5 rounded overflow-hidden border border-[#2C3E4C]">
                    <span style={{ backgroundColor: combo.background }} />
                    <span style={{ backgroundColor: combo.surface }} />
                    <span style={{ backgroundColor: combo.accent }} />
                    <span style={{ backgroundColor: combo.text }} />
                  </div>
                  <span className="block text-[10px] font-medium text-[#DCE7EC] truncate">
                    {combo.name}
                  </span>
                </button>
              ))}
            </div>
          </AccordionSection>
        </div>
      </div>
    );
  }

  const el = selectedElement;
  const hasText = [
    'topBar',
    'bottomNav',
    'circle',
    'heading',
    'text',
    'button',
    'outlinedButton',
    'switch',
    'fab',
    'image',
    'card',
    'rectangle',
  ].includes(el.kind);
  const supportsTextAlign = hasText || el.kind === 'input';
  const showPhotoFilters =
    el.kind === 'image' || el.kind === 'card' || el.kind === 'rectangle' || el.kind === 'circle';

  return (
    <div className="h-full flex flex-col bg-[#121A22]">
      {/* Compact Sticky Header */}
      <div className="flex items-center justify-between gap-1.5 px-3 py-2 border-b border-[#22313D] shrink-0">
        <input
          type="text"
          aria-label="Layer name"
          value={el.name}
          onChange={(e) => onUpdateElement(el.id, { name: e.target.value })}
          className="flex-1 min-w-0 px-1.5 py-1 rounded bg-[#0D131A] border border-transparent hover:border-[#2A3B48] focus:border-[#4B9BA2] text-xs font-semibold text-[#EEF4F6] outline-none"
        />
        <div className="flex items-center gap-0.5 shrink-0">
          <button
            type="button"
            aria-label={el.locked ? 'Unlock layer' : 'Lock layer'}
            onClick={() => onUpdateElement(el.id, { locked: !el.locked })}
            className="p-1 rounded text-[#9BB0BC] hover:text-white hover:bg-[#1E2B37] cursor-pointer"
          >
            {el.locked ? (
              <Lock className="w-3.5 h-3.5 text-[#F59E0B]" />
            ) : (
              <Unlock className="w-3.5 h-3.5" />
            )}
          </button>
          <button
            type="button"
            aria-label="Duplicate layer"
            onClick={() => onDuplicateElement(el.id)}
            className="p-1 rounded text-[#9BB0BC] hover:text-white hover:bg-[#1E2B37] cursor-pointer"
          >
            <Copy className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Delete layer"
            onClick={() => onDeleteElement(el.id)}
            className="p-1 rounded text-[#9BB0BC] hover:text-[#EF4444] hover:bg-[#1E2B37] cursor-pointer"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Collapse inspector panel"
            onClick={onClose}
            className="p-1 rounded text-[#89A0AD] hover:text-white hover:bg-[#1E2B37] cursor-pointer"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Scrollable Accordion Controls */}
      <div className="flex-1 overflow-y-auto px-3 py-2.5 space-y-3">
        {/* Quick Canvas Position Align & Z-Order */}
        <div className="flex items-center justify-between gap-0.5 p-1 rounded-lg bg-[#0D131A] border border-[#22313D]">
          <button
            type="button"
            aria-label="Align box left"
            onClick={() => onAlignElement(el.id, 'left')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <AlignStartHorizontal className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Align box horizontal center"
            onClick={() => onAlignElement(el.id, 'centerX')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <AlignCenterHorizontal className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Align box right"
            onClick={() => onAlignElement(el.id, 'right')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <AlignEndHorizontal className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Align box top"
            onClick={() => onAlignElement(el.id, 'top')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <AlignStartVertical className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Align box vertical center"
            onClick={() => onAlignElement(el.id, 'centerY')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <AlignCenterVertical className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Align box bottom"
            onClick={() => onAlignElement(el.id, 'bottom')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <AlignEndVertical className="w-3.5 h-3.5" />
          </button>
          <span className="w-px h-3.5 bg-[#253542]" />
          <button
            type="button"
            aria-label="Bring layer forward"
            onClick={() => onReorderElement(el.id, 'up')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <ArrowUp className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Send layer backward"
            onClick={() => onReorderElement(el.id, 'down')}
            className="p-1 rounded hover:bg-[#1C2834] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <ArrowDown className="w-3.5 h-3.5" />
          </button>
        </div>

        {/* Photo Upload (Top Priority for Image Frames) */}
        {el.kind === 'image' && (
          <AccordionSection title="Photo Asset" defaultOpen>
            <input
              ref={imageInputRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={handlePhotoUpload}
            />
            <button
              type="button"
              onClick={() => imageInputRef.current?.click()}
              className="w-full inline-flex items-center justify-center gap-1.5 py-1.5 px-2.5 rounded-lg bg-[#3E8790] hover:bg-[#35767E] text-xs font-semibold text-white cursor-pointer"
            >
              <ImagePlus className="w-3.5 h-3.5" />
              <span>{el.imageUrl ? 'Replace Photo' : 'Upload Photo'}</span>
            </button>

            <div className="flex items-center gap-1.5">
              {(['cover', 'contain'] as const).map((fit) => (
                <button
                  key={fit}
                  type="button"
                  onClick={() => onUpdateElement(el.id, { imageFit: fit })}
                  className={`flex-1 py-1 rounded text-xs capitalize border cursor-pointer ${
                    (el.imageFit ?? 'cover') === fit
                      ? 'bg-[#213341] border-[#4B9BA2] text-white'
                      : 'bg-[#0D131A] border-[#253542] text-[#89A0AD]'
                  }`}
                >
                  {fit}
                </button>
              ))}
            </div>
          </AccordionSection>
        )}

        {/* Content & Typography */}
        {(hasText || el.kind === 'input' || el.kind === 'progress') && (
          <AccordionSection title="Content & Type" defaultOpen>
            {el.kind === 'input' ? (
              <input
                type="text"
                aria-label="Placeholder hint"
                value={el.placeholder ?? ''}
                onChange={(e) => onUpdateElement(el.id, { placeholder: e.target.value })}
                className="w-full px-2 py-1.5 rounded bg-[#0D131A] border border-[#253542] text-xs text-[#EEF4F6] outline-none focus:border-[#4B9BA2]"
                placeholder="Placeholder text"
              />
            ) : el.kind === 'progress' ? (
              <div className="space-y-1">
                <div className="flex justify-between text-[11px]">
                  <span className="text-[#89A0AD]">Progress</span>
                  <span className="font-mono tabular-nums text-[#DCE7EC]">{el.progress ?? 70}%</span>
                </div>
                <input
                  type="range"
                  min={0}
                  max={100}
                  value={el.progress ?? 70}
                  aria-label="Progress percentage"
                  onChange={(e) => onUpdateElement(el.id, { progress: Number(e.target.value) })}
                  className="w-full accent-[#3E8790]"
                />
              </div>
            ) : (
              <textarea
                rows={2}
                aria-label="Element text"
                value={el.text}
                onChange={(e) => onUpdateElement(el.id, { text: e.target.value })}
                placeholder="Type label or text..."
                className="w-full px-2 py-1.5 rounded bg-[#0D131A] border border-[#253542] text-xs text-[#EEF4F6] outline-none focus:border-[#4B9BA2] resize-none"
              />
            )}

            {el.kind === 'switch' && (
              <label className="flex items-center justify-between text-xs text-[#DCE7EC]">
                <span>Checked</span>
                <input
                  type="checkbox"
                  checked={Boolean(el.checked)}
                  onChange={(e) => onUpdateElement(el.id, { checked: e.target.checked })}
                  className="accent-[#3E8790]"
                />
              </label>
            )}

            {/* Text Alignment Controls */}
            {supportsTextAlign && (
              <div className="space-y-1">
                <span className="text-[10px] font-medium text-[#89A0AD]">Text Alignment</span>
                <div className="grid grid-cols-3 gap-1 p-0.5 rounded-lg bg-[#0D131A] border border-[#253542]">
                  {(
                    [
                      ['left', 'Left', AlignLeft],
                      ['center', 'Center', AlignCenter],
                      ['right', 'Right', AlignRight],
                    ] as const
                  ).map(([alignVal, alignLabel, Icon]) => {
                    const active = el.textAlign === alignVal;
                    return (
                      <button
                        key={alignVal}
                        type="button"
                        aria-label={`Align text ${alignLabel.toLowerCase()}`}
                        aria-pressed={active}
                        onClick={() => onUpdateElement(el.id, { textAlign: alignVal })}
                        className={`inline-flex items-center justify-center gap-1 py-1.5 rounded-md text-[11px] font-medium transition-colors cursor-pointer ${
                          active
                            ? 'bg-[#3E8790] text-white'
                            : 'text-[#9BB0BC] hover:text-white hover:bg-[#192530]'
                        }`}
                      >
                        <Icon className="w-3.5 h-3.5" />
                        <span>{alignLabel}</span>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {hasText && (
              <>
                <div className="grid grid-cols-2 gap-1.5">
                  <label className="flex items-center gap-1 px-2 py-1 rounded bg-[#0D131A] border border-[#253542]">
                    <span className="text-[10px] text-[#7D95A3]">Size</span>
                    <input
                      type="number"
                      min={10}
                      max={48}
                      value={el.fontSize}
                      onChange={(e) =>
                        onUpdateElement(el.id, {
                          fontSize: Math.max(10, Math.min(48, Number(e.target.value) || 14)),
                        })
                      }
                      className="w-full bg-transparent text-xs font-mono tabular-nums text-[#EEF4F6] outline-none text-right"
                    />
                  </label>
                  <select
                    aria-label="Font weight"
                    value={el.fontWeight}
                    onChange={(e) =>
                      onUpdateElement(el.id, {
                        fontWeight: e.target.value as DesignerElement['fontWeight'],
                      })
                    }
                    className="px-2 py-1 rounded bg-[#0D131A] border border-[#253542] text-xs text-[#EEF4F6] outline-none"
                  >
                    <option value="400">Regular</option>
                    <option value="500">Medium</option>
                    <option value="600">SemiBold</option>
                    <option value="700">Bold</option>
                  </select>
                </div>

                <div className="space-y-1">
                  <span className="text-[10px] font-medium text-[#89A0AD]">Text Color</span>
                  <ColorPickerField
                    label="Text color"
                    value={el.textColor}
                    onChange={(hex) => onUpdateElement(el.id, { textColor: hex })}
                    onApplyCombination={(combo) =>
                      onUpdateElement(el.id, buildComboPatchForElement(el, combo))
                    }
                    comboButtonLabel="Pair"
                  />
                </div>
              </>
            )}
          </AccordionSection>
        )}

        {/* Fill & Stroke */}
        <AccordionSection
          title="Fill & Stroke"
          defaultOpen
          rightSlot={
            <button
              type="button"
              onClick={() => onUpdateElement(el.id, { fillColor: 'transparent' })}
              className="text-[10px] text-[#78A5A8] hover:underline cursor-pointer"
            >
              Clear Fill
            </button>
          }
        >
          <div className="space-y-1">
            <span className="text-[10px] font-medium text-[#89A0AD]">Fill Color</span>
            <ColorPickerField
              label="Fill color"
              value={el.fillColor}
              allowTransparent
              onChange={(hex) => onUpdateElement(el.id, { fillColor: hex })}
              onApplyCombination={(combo) =>
                onUpdateElement(el.id, buildComboPatchForElement(el, combo))
              }
              comboButtonLabel="Pair"
            />
          </div>

          <div className="space-y-1.5">
            <div className="flex items-center justify-between gap-2">
              <span className="text-[10px] font-medium text-[#89A0AD]">Border / Stroke</span>
              <label className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-[#0D131A] border border-[#253542]">
                <span className="text-[10px] text-[#7D95A3]">W</span>
                <input
                  type="number"
                  min={0}
                  max={12}
                  value={el.strokeWidth}
                  onChange={(e) =>
                    onUpdateElement(el.id, {
                      strokeWidth: Math.max(0, Math.min(12, Number(e.target.value) || 0)),
                    })
                  }
                  className="w-10 bg-transparent text-xs font-mono tabular-nums text-[#EEF4F6] outline-none text-right"
                />
              </label>
            </div>
            <ColorPickerField
              label="Stroke color"
              value={el.strokeColor}
              onChange={(hex) =>
                onUpdateElement(el.id, {
                  strokeColor: hex,
                  strokeWidth: el.strokeWidth === 0 ? 1 : el.strokeWidth,
                })
              }
            />
          </div>
        </AccordionSection>

        {/* Color Combination Boxes */}
        <AccordionSection title="Color Combination Boxes" defaultOpen>
          <div className="grid grid-cols-2 gap-1.5">
            {COLOR_COMBINATIONS.map((combo) => (
              <button
                key={combo.id}
                type="button"
                onClick={() => onUpdateElement(el.id, buildComboPatchForElement(el, combo))}
                className="text-left p-1.5 rounded-lg bg-[#0D131A] hover:bg-[#17222D] border border-[#253542] hover:border-[#4B9BA2] transition-colors cursor-pointer space-y-1"
              >
                <div className="grid grid-cols-4 gap-0.5 h-4 rounded overflow-hidden border border-[#2C3E4C]">
                  <span style={{ backgroundColor: combo.background }} />
                  <span style={{ backgroundColor: combo.surface }} />
                  <span style={{ backgroundColor: combo.accent }} />
                  <span style={{ backgroundColor: combo.text }} />
                </div>
                <span className="block text-[10px] font-medium text-[#DCE7EC] truncate">
                  {combo.name}
                </span>
              </button>
            ))}
          </div>
        </AccordionSection>

        {/* Layout & Transform */}
        <AccordionSection title="Layout & Transform" defaultOpen={false}>
          <div className="grid grid-cols-2 gap-1.5">
            {(
              [
                ['X', 'x'],
                ['Y', 'y'],
                ['W', 'width'],
                ['H', 'height'],
                ['Rot °', 'rotation'],
                ['Rad', 'borderRadius'],
              ] as const
            ).map(([label, key]) => (
              <label
                key={key}
                className="flex items-center gap-1 px-2 py-1 rounded bg-[#0D131A] border border-[#253542]"
              >
                <span className="text-[10px] text-[#7D95A3] w-8 shrink-0">{label}</span>
                <input
                  type="number"
                  value={el[key]}
                  onChange={(e) => {
                    const val = Number(e.target.value);
                    if (!Number.isFinite(val)) return;
                    if (key === 'width') onUpdateElement(el.id, { width: Math.max(24, val) });
                    else if (key === 'height') onUpdateElement(el.id, { height: Math.max(2, val) });
                    else if (key === 'borderRadius')
                      onUpdateElement(el.id, { borderRadius: Math.max(0, val) });
                    else onUpdateElement(el.id, { [key]: val } as Partial<DesignerElement>);
                  }}
                  className="w-full min-w-0 bg-transparent text-xs font-mono tabular-nums text-[#EEF4F6] outline-none text-right"
                />
              </label>
            ))}
          </div>

          <div className="space-y-1">
            <div className="flex justify-between text-[11px]">
              <span className="text-[#89A0AD]">Opacity</span>
              <span className="font-mono tabular-nums text-[#DCE7EC]">{el.opacity}%</span>
            </div>
            <input
              type="range"
              min={10}
              max={100}
              value={el.opacity}
              aria-label="Layer opacity"
              onChange={(e) => onUpdateElement(el.id, { opacity: Number(e.target.value) })}
              className="w-full accent-[#3E8790]"
            />
          </div>

          <div className="space-y-1">
            <div className="flex justify-between text-[11px]">
              <span className="text-[#89A0AD]">Shadow</span>
              <span className="font-mono tabular-nums text-[#DCE7EC]">{el.shadow}dp</span>
            </div>
            <input
              type="range"
              min={0}
              max={24}
              value={el.shadow}
              aria-label="Shadow elevation"
              onChange={(e) => onUpdateElement(el.id, { shadow: Number(e.target.value) })}
              className="w-full accent-[#3E8790]"
            />
          </div>
        </AccordionSection>

        {/* Photo Adjustments */}
        {showPhotoFilters && (
          <AccordionSection
            title="Photo Filters"
            defaultOpen={el.kind === 'image'}
            rightSlot={
              <button
                type="button"
                onClick={() =>
                  onUpdateElement(el.id, {
                    brightness: 100,
                    contrast: 100,
                    saturation: 100,
                    blur: 0,
                  })
                }
                className="text-[10px] text-[#78A5A8] hover:underline cursor-pointer"
              >
                Reset
              </button>
            }
          >
            {(
              [
                ['Brightness', 'brightness', 50, 160, '%'],
                ['Contrast', 'contrast', 50, 160, '%'],
                ['Saturation', 'saturation', 0, 200, '%'],
                ['Blur', 'blur', 0, 12, 'px'],
              ] as const
            ).map(([label, key, min, max, unit]) => (
              <div key={key} className="space-y-0.5">
                <div className="flex justify-between text-[11px]">
                  <span className="text-[#89A0AD]">{label}</span>
                  <span className="font-mono tabular-nums text-[#DCE7EC]">
                    {el[key]}
                    {unit}
                  </span>
                </div>
                <input
                  type="range"
                  min={min}
                  max={max}
                  value={el[key]}
                  aria-label={label}
                  onChange={(e) =>
                    onUpdateElement(el.id, {
                      [key]: Number(e.target.value),
                    } as Partial<DesignerElement>)
                  }
                  className="w-full accent-[#3E8790]"
                />
              </div>
            ))}
          </AccordionSection>
        )}
      </div>
    </div>
  );
};
