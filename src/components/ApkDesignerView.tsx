import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlignCenter,
  AlignLeft,
  AlignRight,
  Check,
  Code2,
  Copy,
  Download,
  Eye,
  EyeOff,
  Grid3X3,
  ImagePlus,
  Layers,
  LayoutTemplate,
  Lock,
  Magnet,
  Maximize2,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  Redo2,
  Shapes,
  SlidersHorizontal,
  Trash2,
  Type,
  Undo2,
  X,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import {
  alignElementInCanvas,
  applyColorCombinationToScreen,
  createDesignerElement,
  createStarterDesignerScreen,
  generateComposeFromScreen,
  generateXmlFromScreen,
  moveElementWithSnap,
  reorderLayer,
  resizeElementFromHandle,
  type AlignAction,
  type DesignerElement,
  type DesignerElementKind,
  type DesignerScreen,
  type ResizeHandle,
  type SnapGuides,
} from '../lib/apkDesignerEngine';
import type { ColorCombination } from '../lib/colorUtils';
import { DesignerInspector } from './DesignerInspector';

interface ApkDesignerViewProps {
  packageName: string;
  onExportCodeToProject: (files: Array<{ path: string; content: string }>, openPath: string) => void;
}

const STORAGE_KEY = 'pocket_apk_designer_screens_v1';

const ELEMENT_PALETTE: Array<{
  group: string;
  items: Array<{ kind: DesignerElementKind; label: string }>;
}> = [
  {
    group: 'Android UI',
    items: [
      { kind: 'topBar', label: 'App Bar' },
      { kind: 'bottomNav', label: 'Bottom Nav' },
      { kind: 'button', label: 'Button' },
      { kind: 'outlinedButton', label: 'Outlined' },
      { kind: 'input', label: 'Text Input' },
      { kind: 'switch', label: 'Switch' },
      { kind: 'progress', label: 'Progress' },
      { kind: 'fab', label: 'FAB +' },
    ],
  },
  {
    group: 'Text, Photos & Shapes',
    items: [
      { kind: 'heading', label: 'Heading' },
      { kind: 'text', label: 'Body Text' },
      { kind: 'image', label: 'Photo' },
      { kind: 'card', label: 'Card' },
      { kind: 'rectangle', label: 'Rectangle' },
      { kind: 'circle', label: 'Circle' },
      { kind: 'divider', label: 'Divider' },
    ],
  },
];

const RESIZE_HANDLES: Array<{ handle: ResizeHandle; className: string; cursor: string }> = [
  { handle: 'nw', className: 'top-0 left-0 -translate-x-1/2 -translate-y-1/2', cursor: 'nwse-resize' },
  { handle: 'n', className: 'top-0 left-1/2 -translate-x-1/2 -translate-y-1/2', cursor: 'ns-resize' },
  { handle: 'ne', className: 'top-0 right-0 translate-x-1/2 -translate-y-1/2', cursor: 'nesw-resize' },
  { handle: 'e', className: 'top-1/2 right-0 translate-x-1/2 -translate-y-1/2', cursor: 'ew-resize' },
  { handle: 'se', className: 'bottom-0 right-0 translate-x-1/2 translate-y-1/2', cursor: 'nwse-resize' },
  { handle: 's', className: 'bottom-0 left-1/2 -translate-x-1/2 translate-y-1/2', cursor: 'ns-resize' },
  { handle: 'sw', className: 'bottom-0 left-0 -translate-x-1/2 translate-y-1/2', cursor: 'nesw-resize' },
  { handle: 'w', className: 'top-1/2 left-0 -translate-x-1/2 -translate-y-1/2', cursor: 'ew-resize' },
];

function loadSavedScreens(): DesignerScreen[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as DesignerScreen[];
      if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    }
  } catch {
    // ignore storage errors
  }
  return [createStarterDesignerScreen('dashboard')];
}

function getInitialWideLayout(): boolean {
  if (typeof window === 'undefined') return true;
  return window.innerWidth >= 1120;
}

export const ApkDesignerView: React.FC<ApkDesignerViewProps> = ({
  packageName,
  onExportCodeToProject,
}) => {
  const [screens, setScreens] = useState<DesignerScreen[]>(loadSavedScreens);
  const [activeScreenId, setActiveScreenId] = useState<string>(() => screens[0]?.id ?? '');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [leftTab, setLeftTab] = useState<'elements' | 'layers' | 'templates'>('elements');

  // Collapsible panel states — default closed on narrow screens so canvas is 100% visible immediately!
  const [leftPanelOpen, setLeftPanelOpen] = useState<boolean>(getInitialWideLayout);
  const [rightPanelOpen, setRightPanelOpen] = useState<boolean>(false);

  const [zoom, setZoom] = useState(0.72);
  const [autoFitEnabled, setAutoFitEnabled] = useState(true);
  const [showGrid, setShowGrid] = useState(true);
  const [snapToGrid, setSnapToGrid] = useState(true);
  const [guides, setGuides] = useState<SnapGuides>({
    verticalCenter: false,
    horizontalCenter: false,
    verticalX: null,
    horizontalY: null,
  });
  const [history, setHistory] = useState<DesignerScreen[][]>([]);
  const [future, setFuture] = useState<DesignerScreen[][]>([]);
  const [exportModalOpen, setExportModalOpen] = useState(false);
  const [exportTab, setExportTab] = useState<'compose' | 'xml'>('compose');
  const [toastMsg, setToastMsg] = useState<string | null>(null);

  const quickPhotoInputRef = useRef<HTMLInputElement | null>(null);
  const canvasViewportRef = useRef<HTMLDivElement | null>(null);

  const activeScreen = useMemo(
    () => screens.find((s) => s.id === activeScreenId) ?? screens[0],
    [screens, activeScreenId]
  );

  const selectedElement = useMemo(
    () => activeScreen?.elements.find((el) => el.id === selectedId) ?? null,
    [activeScreen, selectedId]
  );

  const computeFitZoom = useCallback(() => {
    const container = canvasViewportRef.current;
    if (!container) return 0.72;
    const padX = 36;
    const padY = 84;
    const availW = Math.max(180, container.clientWidth - padX);
    const availH = Math.max(240, container.clientHeight - padY);
    const ratio = Math.min(availW / activeScreen.width, availH / activeScreen.height, 1.0);
    return Math.max(0.35, Math.min(1.25, Number(ratio.toFixed(2))));
  }, [activeScreen.height, activeScreen.width]);

  // Keep canvas auto-fitted when container resizes or panels collapse/open
  useEffect(() => {
    const el = canvasViewportRef.current;
    if (!el) return;
    const updateFit = () => {
      if (autoFitEnabled) {
        setZoom(computeFitZoom());
      }
    };
    updateFit();
    const observer = new ResizeObserver(updateFit);
    observer.observe(el);
    return () => observer.disconnect();
  }, [autoFitEnabled, computeFitZoom, leftPanelOpen, rightPanelOpen]);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(screens));
    } catch {
      // ignore storage quota errors
    }
  }, [screens]);

  const showToast = useCallback((msg: string) => {
    setToastMsg(msg);
    setTimeout(() => setToastMsg((curr) => (curr === msg ? null : curr)), 2200);
  }, []);

  const commitScreens = useCallback(
    (updater: (prev: DesignerScreen[]) => DesignerScreen[], recordHistory = true) => {
      setScreens((prev) => {
        if (recordHistory) {
          setHistory((h) => [...h.slice(-24), prev]);
          setFuture([]);
        }
        return updater(prev);
      });
    },
    []
  );

  const updateActiveScreen = useCallback(
    (updater: (screen: DesignerScreen) => DesignerScreen, recordHistory = true) => {
      commitScreens(
        (prev) => prev.map((s) => (s.id === activeScreen.id ? updater(s) : s)),
        recordHistory
      );
    },
    [activeScreen.id, commitScreens]
  );

  const handleUndo = useCallback(() => {
    setHistory((h) => {
      if (h.length === 0) return h;
      const previous = h[h.length - 1];
      setScreens((curr) => {
        setFuture((f) => [curr, ...f.slice(0, 24)]);
        return previous;
      });
      return h.slice(0, -1);
    });
  }, []);

  const handleRedo = useCallback(() => {
    setFuture((f) => {
      if (f.length === 0) return f;
      const next = f[0];
      setScreens((curr) => {
        setHistory((h) => [...h.slice(-24), curr]);
        return next;
      });
      return f.slice(1);
    });
  }, []);

  const handleAddElement = useCallback(
    (kind: DesignerElementKind) => {
      const next = createDesignerElement(
        kind,
        activeScreen.width,
        activeScreen.height,
        activeScreen.elements.length
      );
      updateActiveScreen((s) => ({ ...s, elements: [...s.elements, next] }));
      setSelectedId(next.id);
      if (typeof window !== 'undefined' && window.innerWidth < 1024) {
        setLeftPanelOpen(false);
      }
      showToast(`Added ${next.name}`);
    },
    [activeScreen.elements.length, activeScreen.height, activeScreen.width, showToast, updateActiveScreen]
  );

  const handleQuickPhotoDrop = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        const next = {
          ...createDesignerElement('image', activeScreen.width, activeScreen.height, activeScreen.elements.length),
          name: file.name.replace(/\.[^.]+$/, ''),
          imageUrl: reader.result,
        };
        updateActiveScreen((s) => ({ ...s, elements: [...s.elements, next] }));
        setSelectedId(next.id);
        if (typeof window !== 'undefined' && window.innerWidth < 1024) {
          setLeftPanelOpen(false);
        }
        showToast(`Added photo "${next.name}"`);
      }
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  const handleUpdateElement = useCallback(
    (id: string, patch: Partial<DesignerElement>, recordHistory = true) => {
      updateActiveScreen(
        (s) => ({
          ...s,
          elements: s.elements.map((el) => (el.id === id ? { ...el, ...patch } : el)),
        }),
        recordHistory
      );
    },
    [updateActiveScreen]
  );

  const handleDuplicateElement = useCallback(
    (id: string) => {
      const source = activeScreen.elements.find((e) => e.id === id);
      if (!source) return;
      const copy: DesignerElement = {
        ...source,
        id: `${source.kind}-${Date.now().toString(36)}`,
        name: `${source.name} Copy`,
        x: Math.min(activeScreen.width - 24, source.x + 16),
        y: Math.min(activeScreen.height - 24, source.y + 16),
      };
      updateActiveScreen((s) => ({ ...s, elements: [...s.elements, copy] }));
      setSelectedId(copy.id);
    },
    [activeScreen.elements, activeScreen.height, activeScreen.width, updateActiveScreen]
  );

  const handleDeleteElement = useCallback(
    (id: string) => {
      updateActiveScreen((s) => ({
        ...s,
        elements: s.elements.filter((el) => el.id !== id),
      }));
      if (selectedId === id) setSelectedId(null);
    },
    [selectedId, updateActiveScreen]
  );

  const handleAlignElement = useCallback(
    (id: string, action: AlignAction) => {
      const el = activeScreen.elements.find((e) => e.id === id);
      if (!el || el.locked) return;
      const pos = alignElementInCanvas(el, action, activeScreen);
      handleUpdateElement(id, pos);
    },
    [activeScreen, handleUpdateElement]
  );

  const handleReorderElement = useCallback(
    (id: string, dir: 'up' | 'down') => {
      updateActiveScreen((s) => ({ ...s, elements: reorderLayer(s.elements, id, dir) }));
    },
    [updateActiveScreen]
  );

  const handleApplyThemeToScreen = useCallback(
    (combo: ColorCombination) => {
      updateActiveScreen((s) => applyColorCombinationToScreen(s, combo));
      showToast(`Applied ${combo.name} colors`);
    },
    [showToast, updateActiveScreen]
  );

  // Pointer drag & resize handling (touch-safe with guaranteed cleanup)
  const interactionRef = useRef<{
    mode: 'drag' | 'resize';
    id: string;
    pointerId: number;
    target: HTMLElement | null;
    handle?: ResizeHandle;
    startClientX: number;
    startClientY: number;
    initial: DesignerElement;
    snapshotSaved: boolean;
  } | null>(null);

  const endInteraction = useCallback(() => {
    const state = interactionRef.current;
    if (state?.target) {
      try {
        if (state.target.hasPointerCapture(state.pointerId)) {
          state.target.releasePointerCapture(state.pointerId);
        }
      } catch {
        // ignore capture release errors on unmounted nodes
      }
    }
    interactionRef.current = null;
    setGuides({ verticalCenter: false, horizontalCenter: false, verticalX: null, horizontalY: null });
  }, []);

  useEffect(() => {
    window.addEventListener('pointerup', endInteraction);
    window.addEventListener('pointercancel', endInteraction);
    window.addEventListener('blur', endInteraction);
    return () => {
      window.removeEventListener('pointerup', endInteraction);
      window.removeEventListener('pointercancel', endInteraction);
      window.removeEventListener('blur', endInteraction);
    };
  }, [endInteraction]);

  const startDrag = (e: React.PointerEvent, el: DesignerElement) => {
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    e.stopPropagation();
    setSelectedId(el.id);
    if (el.locked) return;
    const target = e.currentTarget as HTMLElement;
    try {
      target.setPointerCapture(e.pointerId);
    } catch {
      // ignore if capture is rejected by browser
    }
    interactionRef.current = {
      mode: 'drag',
      id: el.id,
      pointerId: e.pointerId,
      target,
      startClientX: e.clientX,
      startClientY: e.clientY,
      initial: { ...el },
      snapshotSaved: false,
    };
  };

  const startResize = (e: React.PointerEvent, el: DesignerElement, handle: ResizeHandle) => {
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    e.stopPropagation();
    if (el.locked) return;
    const target = e.currentTarget as HTMLElement;
    try {
      target.setPointerCapture(e.pointerId);
    } catch {
      // ignore if capture is rejected by browser
    }
    interactionRef.current = {
      mode: 'resize',
      id: el.id,
      pointerId: e.pointerId,
      target,
      handle,
      startClientX: e.clientX,
      startClientY: e.clientY,
      initial: { ...el },
      snapshotSaved: false,
    };
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    const state = interactionRef.current;
    if (!state || state.pointerId !== e.pointerId) return;

    const dx = (e.clientX - state.startClientX) / zoom;
    const dy = (e.clientY - state.startClientY) / zoom;

    if (!state.snapshotSaved && (Math.abs(dx) > 1 || Math.abs(dy) > 1)) {
      setHistory((h) => [...h.slice(-24), screens]);
      setFuture([]);
      state.snapshotSaved = true;
    }

    if (state.mode === 'drag') {
      const moved = moveElementWithSnap(
        state.initial,
        state.initial.x + dx,
        state.initial.y + dy,
        activeScreen,
        snapToGrid
      );
      setGuides(moved.guides);
      handleUpdateElement(state.id, { x: moved.x, y: moved.y }, false);
    } else if (state.mode === 'resize' && state.handle) {
      const resized = resizeElementFromHandle(state.initial, state.handle, dx, dy, snapToGrid);
      handleUpdateElement(state.id, resized, false);
    }
  };

  // Keyboard shortcuts
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) handleRedo();
        else handleUndo();
        return;
      }
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'd' && selectedElement) {
        e.preventDefault();
        handleDuplicateElement(selectedElement.id);
        return;
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && selectedElement && !selectedElement.locked) {
        e.preventDefault();
        handleDeleteElement(selectedElement.id);
        return;
      }
      if (selectedElement && !selectedElement.locked && ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
        e.preventDefault();
        const step = e.shiftKey ? 8 : 1;
        const dx = e.key === 'ArrowLeft' ? -step : e.key === 'ArrowRight' ? step : 0;
        const dy = e.key === 'ArrowUp' ? -step : e.key === 'ArrowDown' ? step : 0;
        handleUpdateElement(selectedElement.id, {
          x: selectedElement.x + dx,
          y: selectedElement.y + dy,
        });
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [handleDeleteElement, handleDuplicateElement, handleRedo, handleUndo, handleUpdateElement, selectedElement]);

  // Export PNG via HTML5 2D Canvas
  const handleExportPng = async () => {
    const scale = 2;
    const canvas = document.createElement('canvas');
    canvas.width = activeScreen.width * scale;
    canvas.height = activeScreen.height * scale;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.scale(scale, scale);

    ctx.fillStyle = activeScreen.backgroundColor;
    ctx.fillRect(0, 0, activeScreen.width, activeScreen.height);

    const drawRoundRect = (x: number, y: number, w: number, h: number, r: number) => {
      const radius = Math.min(r, w / 2, h / 2);
      ctx.beginPath();
      ctx.moveTo(x + radius, y);
      ctx.arcTo(x + w, y, x + w, y + h, radius);
      ctx.arcTo(x + w, y + h, x, y + h, radius);
      ctx.arcTo(x, y + h, x, y, radius);
      ctx.arcTo(x, y, x + w, y, radius);
      ctx.closePath();
    };

    for (const el of activeScreen.elements) {
      if (!el.visible) continue;
      ctx.save();
      ctx.globalAlpha = el.opacity / 100;
      ctx.translate(el.x + el.width / 2, el.y + el.height / 2);
      ctx.rotate((el.rotation * Math.PI) / 180);
      ctx.translate(-el.width / 2, -el.height / 2);

      if (el.fillColor !== 'transparent') {
        drawRoundRect(0, 0, el.width, el.height, el.borderRadius);
        ctx.fillStyle = el.fillColor;
        ctx.fill();
      }

      if (el.kind === 'image' && el.imageUrl) {
        try {
          const img = await new Promise<HTMLImageElement>((resolve, reject) => {
            const im = new Image();
            im.onload = () => resolve(im);
            im.onerror = reject;
            im.src = el.imageUrl!;
          });
          ctx.save();
          drawRoundRect(0, 0, el.width, el.height, el.borderRadius);
          ctx.clip();
          ctx.filter = `brightness(${el.brightness}%) contrast(${el.contrast}%) saturate(${el.saturation}%) blur(${el.blur}px)`;
          ctx.drawImage(img, 0, 0, el.width, el.height);
          ctx.restore();
        } catch {
          // fallback fill already drawn
        }
      }

      if (el.strokeWidth > 0) {
        drawRoundRect(0, 0, el.width, el.height, el.borderRadius);
        ctx.strokeStyle = el.strokeColor;
        ctx.lineWidth = el.strokeWidth;
        ctx.stroke();
      }

      if (el.text || el.placeholder) {
        ctx.fillStyle = el.text ? el.textColor : '#7B909D';
        ctx.font = `${el.fontWeight} ${el.fontSize}px sans-serif`;
        ctx.textAlign = el.textAlign;
        ctx.textBaseline = 'middle';
        const tx = el.textAlign === 'center' ? el.width / 2 : el.textAlign === 'right' ? el.width - 12 : 12;
        ctx.fillText((el.text || el.placeholder || '').slice(0, 48), tx, el.height / 2);
      }
      ctx.restore();
    }

    const link = document.createElement('a');
    link.download = `${activeScreen.name.replace(/[^a-zA-Z0-9_-]+/g, '_')}.png`;
    link.href = canvas.toDataURL('image/png');
    link.click();
    showToast('Exported PNG image');
  };

  const generatedCompose = useMemo(
    () => generateComposeFromScreen(activeScreen, packageName),
    [activeScreen, packageName]
  );

  const generatedXml = useMemo(() => generateXmlFromScreen(activeScreen), [activeScreen]);

  const handleSendToEditor = () => {
    const slug =
      activeScreen.name
        .replace(/[^a-zA-Z0-9]+/g, ' ')
        .trim()
        .split(/\s+/)
        .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
        .join('') || 'DesignedScreen';
    const xmlSlug = slug.toLowerCase();
    const pkgPath = packageName.replace(/\./g, '/');
    const composePath = `app/src/main/java/${pkgPath}/${slug}.kt`;
    const xmlPath = `app/src/main/res/layout/activity_${xmlSlug}.xml`;

    onExportCodeToProject(
      [
        { path: composePath, content: generatedCompose },
        { path: xmlPath, content: generatedXml },
      ],
      exportTab === 'compose' ? composePath : xmlPath
    );
    setExportModalOpen(false);
  };

  const renderCanvasElement = (el: DesignerElement) => {
    if (!el.visible) return null;
    const isSelected = el.id === selectedId;
    const filterCss = `brightness(${el.brightness}%) contrast(${el.contrast}%) saturate(${el.saturation}%) blur(${el.blur}px)`;

    return (
      <div
        key={el.id}
        data-testid={`designer-el-${el.id}`}
        onPointerDown={(e) => startDrag(e, el)}
        onPointerMove={handlePointerMove}
        onPointerUp={endInteraction}
        onPointerCancel={endInteraction}
        onLostPointerCapture={endInteraction}
        onDoubleClick={(e) => {
          e.stopPropagation();
          setSelectedId(el.id);
          setRightPanelOpen(true);
        }}
        style={{
          left: `${el.x}px`,
          top: `${el.y}px`,
          width: `${el.width}px`,
          height: `${el.height}px`,
          opacity: el.opacity / 100,
          transform: el.rotation ? `rotate(${el.rotation}deg)` : undefined,
        }}
        className={`absolute select-none touch-none ${el.locked ? 'cursor-default' : 'cursor-move'}`}
      >
        <div
          style={{
            backgroundColor: el.fillColor,
            borderRadius: `${el.borderRadius}px`,
            border: el.strokeWidth > 0 ? `${el.strokeWidth}px solid ${el.strokeColor}` : 'none',
            boxShadow: el.shadow > 0 ? `0 ${Math.round(el.shadow * 0.6)}px ${el.shadow * 2}px rgba(0,0,0,0.38)` : 'none',
            filter: el.kind !== 'image' && (el.brightness !== 100 || el.contrast !== 100 || el.saturation !== 100 || el.blur > 0) ? filterCss : undefined,
          }}
          className="w-full h-full overflow-hidden flex items-center"
        >
          {el.kind === 'image' ? (
            el.imageUrl ? (
              <img
                src={el.imageUrl}
                alt={el.name}
                draggable={false}
                style={{
                  objectFit: el.imageFit ?? 'cover',
                  filter: filterCss,
                }}
                className="w-full h-full pointer-events-none"
              />
            ) : (
              <div
                style={{ textAlign: el.textAlign }}
                className={`w-full h-full flex flex-col justify-center gap-1.5 text-[#89A0AD] px-3 bg-gradient-to-br from-[#1B2B37] to-[#121D26] ${
                  el.textAlign === 'left'
                    ? 'items-start'
                    : el.textAlign === 'right'
                    ? 'items-end'
                    : 'items-center'
                }`}
              >
                <ImagePlus className="w-5 h-5 text-[#78A5A8]" />
                <span className="text-[11px] font-medium w-full">{el.text || 'Upload Photo'}</span>
              </div>
            )
          ) : el.kind === 'switch' ? (
            <div className="w-full px-3.5 flex items-center justify-between gap-2">
              <span
                style={{
                  color: el.textColor,
                  fontSize: `${el.fontSize}px`,
                  fontWeight: el.fontWeight,
                  textAlign: el.textAlign,
                }}
                className="flex-1 truncate"
              >
                {el.text}
              </span>
              <span className={`w-9 h-5 rounded-full flex items-center px-0.5 shrink-0 ${el.checked ? 'bg-[#3E8790] justify-end' : 'bg-[#334756] justify-start'}`}>
                <span className="w-4 h-4 rounded-full bg-white" />
              </span>
            </div>
          ) : el.kind === 'progress' ? (
            <div className="w-full h-full relative overflow-hidden" style={{ borderRadius: `${el.borderRadius}px` }}>
              <div
                className="h-full"
                style={{
                  width: `${Math.min(100, Math.max(0, el.progress ?? 70))}%`,
                  backgroundColor: el.strokeColor || '#4B9BA2',
                }}
              />
            </div>
          ) : el.kind === 'bottomNav' ? (
            <div
              className="w-full px-4 grid grid-cols-3"
              style={{ color: el.textColor, fontSize: `${el.fontSize}px`, textAlign: el.textAlign }}
            >
              {(el.text || 'Home | Search | Profile')
                .split('|')
                .slice(0, 4)
                .map((tab, idx) => (
                  <span key={idx} className={idx === 0 ? 'text-[#78A5A8] font-semibold' : ''}>
                    {tab.trim()}
                  </span>
                ))}
            </div>
          ) : el.kind === 'input' ? (
            <span
              className="w-full px-3.5 truncate"
              style={{ color: '#7A909E', fontSize: `${el.fontSize}px`, textAlign: el.textAlign }}
            >
              {el.placeholder || 'Enter value…'}
            </span>
          ) : el.kind === 'divider' || el.kind === 'card' || el.kind === 'rectangle' ? (
            el.text ? (
              <span
                style={{
                  color: el.textColor,
                  fontSize: `${el.fontSize}px`,
                  fontWeight: el.fontWeight,
                  textAlign: el.textAlign,
                }}
                className="w-full px-3 truncate"
              >
                {el.text}
              </span>
            ) : null
          ) : (
            <span
              style={{
                color: el.textColor,
                fontSize: `${el.fontSize}px`,
                fontWeight: el.fontWeight,
                textAlign: el.textAlign,
              }}
              className={`w-full leading-snug whitespace-pre-wrap break-words ${
                el.kind === 'topBar' ? 'px-4' : el.kind === 'heading' || el.kind === 'text' ? 'px-1' : 'px-3'
              }`}
            >
              {el.text}
            </span>
          )}
        </div>

        {/* Figma Selection Box & 8 Touch-Friendly Resize Handles */}
        {isSelected && (
          <div className="pointer-events-none absolute inset-0 ring-2 ring-[#4B9BA2] rounded-[2px]">
            <div className="absolute -top-6 left-0 px-1.5 py-0.5 rounded bg-[#3E8790] text-[10px] font-mono tabular-nums text-white whitespace-nowrap">
              {el.name} · {el.width}×{el.height}
            </div>
            {!el.locked &&
              RESIZE_HANDLES.map(({ handle, className, cursor }) => (
                <div
                  key={handle}
                  onPointerDown={(e) => startResize(e, el, handle)}
                  onPointerMove={handlePointerMove}
                  onPointerUp={endInteraction}
                  onPointerCancel={endInteraction}
                  onLostPointerCapture={endInteraction}
                  style={{ cursor }}
                  className={`pointer-events-auto touch-none absolute w-6 h-6 flex items-center justify-center ${className}`}
                >
                  <span className="w-2.5 h-2.5 bg-white border-2 border-[#3E8790] rounded-[2px] shadow-sm" />
                </div>
              ))}
          </div>
        )}
      </div>
    );
  };

  const leftPanelContent = (
    <div className="h-full flex flex-col bg-[#121A22]">
      <div className="flex items-center justify-between px-2.5 py-2 border-b border-[#22313D] shrink-0">
        <div className="grid grid-cols-3 flex-1 gap-1 bg-[#0F151C] p-0.5 rounded-md border border-[#22313D]">
          {(
            [
              ['elements', 'Add', Shapes],
              ['layers', 'Layers', Layers],
              ['templates', 'Presets', LayoutTemplate],
            ] as const
          ).map(([id, label, Icon]) => (
            <button
              key={id}
              type="button"
              onClick={() => setLeftTab(id)}
              className={`inline-flex items-center justify-center gap-1 py-1 rounded text-[11px] font-medium transition-colors cursor-pointer ${
                leftTab === id ? 'bg-[#20303E] text-white' : 'text-[#89A0AD] hover:text-[#DCE7EC]'
              }`}
            >
              <Icon className="w-3 h-3" />
              <span>{label}</span>
            </button>
          ))}
        </div>
        <button
          type="button"
          aria-label="Collapse elements panel"
          onClick={() => setLeftPanelOpen(false)}
          className="ml-1.5 p-1 rounded text-[#89A0AD] hover:text-white hover:bg-[#1E2B37] cursor-pointer"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-2.5 space-y-3">
        {leftTab === 'elements' && (
          <>
            <button
              type="button"
              onClick={() => quickPhotoInputRef.current?.click()}
              className="w-full inline-flex items-center justify-center gap-1.5 py-1.5 px-2.5 rounded-lg bg-[#1D2E3B] hover:bg-[#253B4B] border border-[#355164] text-xs font-semibold text-[#E8F2F5] cursor-pointer"
            >
              <ImagePlus className="w-3.5 h-3.5 text-[#78A5A8]" />
              <span>Upload Photo</span>
            </button>

            {ELEMENT_PALETTE.map((group) => (
              <div key={group.group} className="space-y-1.5">
                <p className="text-[10px] font-semibold text-[#7F97A5] px-0.5">{group.group}</p>
                <div className="grid grid-cols-2 gap-1.5">
                  {group.items.map((item) => (
                    <button
                      key={item.kind}
                      type="button"
                      onClick={() => handleAddElement(item.kind)}
                      className="text-left px-2 py-1.5 rounded-md bg-[#16202A] hover:bg-[#1E2C39] border border-[#253542] hover:border-[#4B9BA2] text-[11px] text-[#DCE7EC] transition-colors cursor-pointer truncate"
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </>
        )}

        {leftTab === 'layers' && (
          <div className="space-y-1">
            {activeScreen.elements.length === 0 ? (
              <p className="text-xs text-[#89A0AD] py-4 text-center">No layers yet.</p>
            ) : (
              [...activeScreen.elements].reverse().map((el) => {
                const active = el.id === selectedId;
                return (
                  <div
                    key={el.id}
                    className={`flex items-center justify-between gap-1 px-2 py-1.5 rounded-md text-xs ${
                      active ? 'bg-[#1F3140] text-white font-medium' : 'text-[#A8BCC8] hover:bg-[#18232D]'
                    }`}
                  >
                    <button
                      type="button"
                      onClick={() => setSelectedId(el.id)}
                      className="flex-1 min-w-0 text-left truncate cursor-pointer"
                    >
                      {el.name}
                    </button>
                    <div className="flex items-center gap-0.5 shrink-0">
                      {el.locked && <Lock className="w-3 h-3 text-[#F59E0B] mr-0.5" />}
                      <button
                        type="button"
                        aria-label={el.visible ? `Hide ${el.name}` : `Show ${el.name}`}
                        onClick={() => handleUpdateElement(el.id, { visible: !el.visible })}
                        className="p-1 text-[#89A0AD] hover:text-white cursor-pointer"
                      >
                        {el.visible ? <Eye className="w-3.5 h-3.5" /> : <EyeOff className="w-3.5 h-3.5" />}
                      </button>
                      <button
                        type="button"
                        aria-label={`Delete ${el.name}`}
                        onClick={() => handleDeleteElement(el.id)}
                        className="p-1 text-[#89A0AD] hover:text-[#EF4444] cursor-pointer"
                      >
                        <Trash2 className="w-3 h-3" />
                      </button>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        )}

        {leftTab === 'templates' && (
          <div className="space-y-2">
            {(
              [
                ['dashboard', 'Home Dashboard', 'App bar, hero card, photo, switch & FAB'],
                ['login', 'Sign In Screen', 'Avatar, email/password fields & buttons'],
                ['product', 'App Showcase', 'Hero photo, title, progress card & nav'],
                ['blank', 'Blank Canvas', 'Clean empty phone artboard'],
              ] as const
            ).map(([preset, title, desc]) => (
              <button
                key={preset}
                type="button"
                onClick={() => {
                  const next = createStarterDesignerScreen(preset, activeScreen.width, activeScreen.height);
                  next.id = activeScreen.id;
                  updateActiveScreen(() => next);
                  setSelectedId(null);
                  if (typeof window !== 'undefined' && window.innerWidth < 1024) {
                    setLeftPanelOpen(false);
                  }
                  showToast(`Loaded "${title}"`);
                }}
                className="w-full text-left p-2.5 rounded-lg bg-[#16202A] hover:bg-[#1E2C39] border border-[#253542] hover:border-[#4B9BA2] transition-colors cursor-pointer"
              >
                <p className="text-xs font-semibold text-[#EEF4F6]">{title}</p>
                <p className="text-[11px] text-[#89A0AD] mt-0.5 leading-snug">{desc}</p>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );

  return (
    <div className="h-[calc(100dvh-112px)] md:h-[calc(100dvh-56px)] flex flex-col overflow-hidden bg-[#0B1016]">
      <input
        ref={quickPhotoInputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={handleQuickPhotoDrop}
      />

      {/* Ultra-Compact Single-Row Top Toolbar */}
      <div className="h-11 px-2.5 border-b border-[#243441] bg-[#141D26] flex items-center justify-between gap-2 overflow-x-auto shrink-0">
        {/* Left Controls: Panel Toggle + Screen Switcher */}
        <div className="flex items-center gap-1.5 shrink-0">
          <button
            type="button"
            aria-label={leftPanelOpen ? 'Collapse elements and layers panel' : 'Open elements and layers panel'}
            onClick={() => setLeftPanelOpen((v) => !v)}
            className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium border transition-colors whitespace-nowrap cursor-pointer ${
              leftPanelOpen
                ? 'bg-[#213341] border-[#4B9BA2] text-white'
                : 'bg-[#10171F] border-[#293B49] text-[#C8D7DF] hover:text-white'
            }`}
          >
            {leftPanelOpen ? <PanelLeftClose className="w-3.5 h-3.5" /> : <PanelLeftOpen className="w-3.5 h-3.5" />}
            <span>Tools</span>
          </button>

          <select
            aria-label="Active screen"
            value={activeScreen.id}
            onChange={(e) => {
              setActiveScreenId(e.target.value);
              setSelectedId(null);
            }}
            className="px-2 py-1 rounded-md bg-[#0F151C] border border-[#293B49] text-xs text-[#EEF4F6] outline-none max-w-[130px] sm:max-w-[170px]"
          >
            {screens.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>

          <button
            type="button"
            aria-label="Add new screen"
            onClick={() => {
              const blank = createStarterDesignerScreen('blank', activeScreen.width, activeScreen.height);
              blank.name = `Screen ${screens.length + 1}`;
              commitScreens((prev) => [...prev, blank]);
              setActiveScreenId(blank.id);
              setSelectedId(null);
            }}
            className="p-1.5 rounded-md bg-[#10171F] hover:bg-[#1E2B37] border border-[#293B49] text-[#9BB0BC] hover:text-white cursor-pointer"
          >
            <Plus className="w-3.5 h-3.5" />
          </button>
        </div>

        {/* Center Controls: History + Grid/Snap + Zoom/Fit */}
        <div className="flex items-center gap-1 shrink-0">
          <button
            type="button"
            aria-label="Undo"
            disabled={history.length === 0}
            onClick={handleUndo}
            className="p-1.5 rounded-md text-[#9BB0BC] hover:text-white hover:bg-[#1E2B37] disabled:opacity-35 cursor-pointer"
          >
            <Undo2 className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Redo"
            disabled={future.length === 0}
            onClick={handleRedo}
            className="p-1.5 rounded-md text-[#9BB0BC] hover:text-white hover:bg-[#1E2B37] disabled:opacity-35 cursor-pointer"
          >
            <Redo2 className="w-3.5 h-3.5" />
          </button>

          <span className="w-px h-4 bg-[#263643] mx-0.5" />

          <button
            type="button"
            aria-label="Toggle canvas grid"
            onClick={() => setShowGrid((v) => !v)}
            className={`p-1.5 rounded-md border cursor-pointer ${
              showGrid ? 'bg-[#203240] border-[#4B9BA2] text-white' : 'bg-[#0F151C] border-transparent text-[#89A0AD]'
            }`}
          >
            <Grid3X3 className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            aria-label="Toggle 8px snap"
            onClick={() => setSnapToGrid((v) => !v)}
            className={`p-1.5 rounded-md border cursor-pointer ${
              snapToGrid ? 'bg-[#203240] border-[#4B9BA2] text-white' : 'bg-[#0F151C] border-transparent text-[#89A0AD]'
            }`}
          >
            <Magnet className="w-3.5 h-3.5" />
          </button>

          <span className="w-px h-4 bg-[#263643] mx-0.5" />

          <div className="flex items-center rounded-md bg-[#0F151C] border border-[#253542]">
            <button
              type="button"
              aria-label="Zoom out"
              onClick={() => {
                setAutoFitEnabled(false);
                setZoom((z) => Math.max(0.35, Number((z - 0.1).toFixed(2))));
              }}
              className="p-1 text-[#9BB0BC] hover:text-white cursor-pointer"
            >
              <ZoomOut className="w-3.5 h-3.5" />
            </button>
            <button
              type="button"
              aria-label="Fit canvas to screen"
              onClick={() => {
                setAutoFitEnabled(true);
                setZoom(computeFitZoom());
              }}
              className="px-1.5 py-0.5 text-[11px] font-mono tabular-nums text-[#DCE7EC] hover:text-white cursor-pointer inline-flex items-center gap-1"
            >
              <span>{Math.round(zoom * 100)}%</span>
              <Maximize2 className="w-2.5 h-2.5 text-[#78A5A8]" />
            </button>
            <button
              type="button"
              aria-label="Zoom in"
              onClick={() => {
                setAutoFitEnabled(false);
                setZoom((z) => Math.min(1.5, Number((z + 0.1).toFixed(2))));
              }}
              className="p-1 text-[#9BB0BC] hover:text-white cursor-pointer"
            >
              <ZoomIn className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* Right Controls: Export + Inspector Toggle */}
        <div className="flex items-center gap-1.5 shrink-0">
          <button
            type="button"
            onClick={handleExportPng}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md bg-[#1A2632] hover:bg-[#233240] border border-[#2C3E4C] text-xs font-medium text-[#E4EEF2] whitespace-nowrap cursor-pointer"
          >
            <Download className="w-3.5 h-3.5 text-[#78A5A8]" />
            <span>PNG</span>
          </button>

          <button
            type="button"
            onClick={() => setExportModalOpen(true)}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md bg-[#3E8790] hover:bg-[#35767E] text-xs font-semibold text-white whitespace-nowrap cursor-pointer"
          >
            <Code2 className="w-3.5 h-3.5" />
            <span>Code</span>
          </button>

          <button
            type="button"
            aria-label={rightPanelOpen ? 'Collapse style inspector' : 'Open style inspector'}
            onClick={() => setRightPanelOpen((v) => !v)}
            className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium border transition-colors whitespace-nowrap cursor-pointer ${
              rightPanelOpen
                ? 'bg-[#213341] border-[#4B9BA2] text-white'
                : 'bg-[#10171F] border-[#293B49] text-[#C8D7DF] hover:text-white'
            }`}
          >
            {rightPanelOpen ? <PanelRightClose className="w-3.5 h-3.5" /> : <PanelRightOpen className="w-3.5 h-3.5" />}
            <span>Style</span>
          </button>
        </div>
      </div>

      {/* Main Canvas-First Workspace (Panels Dock on Desktop, Overlay Drawers on Narrow Screens) */}
      <div className="flex-1 min-h-0 flex relative overflow-hidden">
        {/* Desktop Docked Left Sidebar */}
        {leftPanelOpen && (
          <aside className="hidden lg:block w-[232px] border-r border-[#253542] shrink-0 h-full overflow-hidden">
            {leftPanelContent}
          </aside>
        )}

        {/* Mobile/Tablet Overlay Left Drawer */}
        {leftPanelOpen && (
          <div className="lg:hidden absolute inset-0 z-30 flex">
            <aside className="w-[252px] max-w-[82vw] h-full border-r border-[#2C3E4C] shadow-2xl">
              {leftPanelContent}
            </aside>
            <button
              type="button"
              aria-label="Close tools drawer"
              onClick={() => setLeftPanelOpen(false)}
              className="flex-1 bg-black/50 cursor-pointer"
            />
          </div>
        )}

        {/* Center Full-Height Interactive Canvas */}
        <div
          ref={canvasViewportRef}
          onPointerDown={() => setSelectedId(null)}
          style={
            showGrid
              ? {
                  backgroundImage: 'radial-gradient(rgba(120, 165, 168, 0.22) 1px, transparent 1px)',
                  backgroundSize: '20px 20px',
                }
              : undefined
          }
          className="flex-1 min-w-0 min-h-0 h-full overflow-auto touch-pan-x touch-pan-y bg-[#0B1016] relative flex items-center justify-center p-4 pb-16"
        >
          {toastMsg && (
            <div className="fixed top-28 left-1/2 -translate-x-1/2 z-40 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#1D2D3A] border border-[#4B9BA2] text-xs font-medium text-[#EEF4F6] shadow-lg">
              <Check className="w-3.5 h-3.5 text-[#22C55E]" />
              <span>{toastMsg}</span>
            </div>
          )}

          {/* Scaled Phone Artboard — Always Centered & Visible */}
          <div
            style={{
              width: `${activeScreen.width * zoom}px`,
              height: `${activeScreen.height * zoom}px`,
            }}
            className="relative shrink-0"
          >
            <div
              onPointerDown={(e) => {
                e.stopPropagation();
                setSelectedId(null);
              }}
              style={{
                width: `${activeScreen.width}px`,
                height: `${activeScreen.height}px`,
                backgroundColor: activeScreen.backgroundColor,
                transform: `scale(${zoom})`,
                transformOrigin: 'top left',
              }}
              className="relative rounded-[30px] border-[6px] border-[#273847] shadow-2xl overflow-hidden"
            >
              {showGrid && (
                <div
                  className="pointer-events-none absolute inset-0 opacity-30"
                  style={{
                    backgroundImage:
                      'linear-gradient(to right, rgba(120,165,168,0.14) 1px, transparent 1px), linear-gradient(to bottom, rgba(120,165,168,0.14) 1px, transparent 1px)',
                    backgroundSize: '16px 16px',
                  }}
                />
              )}

              {/* Realistic Android Status Bar */}
              <div className="pointer-events-none absolute top-0 inset-x-0 h-9 px-5 flex items-center justify-between text-[11px] font-mono tabular-nums text-[#9BB0BC] z-10">
                <span>12:30</span>
                <span>5G · 100%</span>
              </div>

              {activeScreen.elements.map(renderCanvasElement)}

              {/* Smart Center Snap Guides */}
              {guides.verticalCenter && guides.verticalX !== null && (
                <div
                  style={{ left: `${guides.verticalX}px` }}
                  className="pointer-events-none absolute inset-y-0 w-px bg-[#4B9BA2] z-20"
                />
              )}
              {guides.horizontalCenter && guides.horizontalY !== null && (
                <div
                  style={{ top: `${guides.horizontalY}px` }}
                  className="pointer-events-none absolute inset-x-0 h-px bg-[#4B9BA2] z-20"
                />
              )}
            </div>
          </div>

          {/* Compact Floating Bottom Action Dock (Contextual Quick Actions without Blocking Canvas) */}
          <div
            onPointerDown={(e) => e.stopPropagation()}
            className="absolute bottom-3 left-1/2 -translate-x-1/2 z-20 flex items-center gap-1 px-2 py-1.5 rounded-xl bg-[#141D26]/95 border border-[#2C3E4C] shadow-xl backdrop-blur-sm max-w-[95%]"
          >
            {selectedElement ? (
              <>
                <span className="hidden sm:inline text-[11px] font-medium text-[#9BB0BC] px-1.5 truncate max-w-[100px]">
                  {selectedElement.name}
                </span>
                <span className="hidden sm:inline w-px h-4 bg-[#263643]" />
                {selectedElement.kind !== 'progress' && selectedElement.kind !== 'divider' && (
                  <>
                    <div className="flex items-center gap-0.5 bg-[#0D131A] p-0.5 rounded-md border border-[#243441]">
                      {(
                        [
                          ['left', 'Align text left', AlignLeft],
                          ['center', 'Align text center', AlignCenter],
                          ['right', 'Align text right', AlignRight],
                        ] as const
                      ).map(([val, label, Icon]) => (
                        <button
                          key={val}
                          type="button"
                          aria-label={label}
                          onClick={() => handleUpdateElement(selectedElement.id, { textAlign: val })}
                          className={`p-1 rounded cursor-pointer ${
                            selectedElement.textAlign === val
                              ? 'bg-[#3E8790] text-white'
                              : 'text-[#9BB0BC] hover:text-white'
                          }`}
                        >
                          <Icon className="w-3.5 h-3.5" />
                        </button>
                      ))}
                    </div>
                    <span className="w-px h-4 bg-[#263643]" />
                  </>
                )}
                <button
                  type="button"
                  onClick={() => setRightPanelOpen(true)}
                  className="inline-flex items-center gap-1 px-2 py-1 rounded-md bg-[#3E8790] hover:bg-[#35767E] text-[11px] font-semibold text-white whitespace-nowrap cursor-pointer"
                >
                  <SlidersHorizontal className="w-3 h-3" />
                  <span>Style</span>
                </button>
                <button
                  type="button"
                  aria-label="Duplicate selected"
                  onClick={() => handleDuplicateElement(selectedElement.id)}
                  className="p-1.5 rounded-md text-[#C8D7DF] hover:text-white hover:bg-[#1F2D3A] cursor-pointer"
                >
                  <Copy className="w-3.5 h-3.5" />
                </button>
                <button
                  type="button"
                  aria-label="Delete selected"
                  onClick={() => handleDeleteElement(selectedElement.id)}
                  className="p-1.5 rounded-md text-[#C8D7DF] hover:text-[#EF4444] hover:bg-[#1F2D3A] cursor-pointer"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </>
            ) : (
              <>
                <button
                  type="button"
                  onClick={() => handleAddElement('heading')}
                  className="inline-flex items-center gap-1 px-2 py-1 rounded-md hover:bg-[#1F2D3A] text-[11px] text-[#DCE7EC] whitespace-nowrap cursor-pointer"
                >
                  <Type className="w-3 h-3 text-[#78A5A8]" />
                  <span>Text</span>
                </button>
                <button
                  type="button"
                  onClick={() => handleAddElement('button')}
                  className="inline-flex items-center gap-1 px-2 py-1 rounded-md hover:bg-[#1F2D3A] text-[11px] text-[#DCE7EC] whitespace-nowrap cursor-pointer"
                >
                  <Plus className="w-3 h-3 text-[#78A5A8]" />
                  <span>Button</span>
                </button>
                <button
                  type="button"
                  onClick={() => quickPhotoInputRef.current?.click()}
                  className="inline-flex items-center gap-1 px-2 py-1 rounded-md hover:bg-[#1F2D3A] text-[11px] text-[#DCE7EC] whitespace-nowrap cursor-pointer"
                >
                  <ImagePlus className="w-3 h-3 text-[#78A5A8]" />
                  <span>Photo</span>
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setLeftTab('elements');
                    setLeftPanelOpen(true);
                  }}
                  className="inline-flex items-center gap-1 px-2 py-1 rounded-md bg-[#1F2E3B] hover:bg-[#283B4B] text-[11px] font-medium text-white whitespace-nowrap cursor-pointer"
                >
                  <Shapes className="w-3 h-3 text-[#78A5A8]" />
                  <span>All Tools</span>
                </button>
              </>
            )}
          </div>
        </div>

        {/* Desktop Docked Right Inspector */}
        {rightPanelOpen && (
          <aside className="hidden lg:block w-[272px] border-l border-[#253542] shrink-0 h-full overflow-hidden">
            <DesignerInspector
              screen={activeScreen}
              selectedElement={selectedElement}
              onUpdateScreen={(patch) => updateActiveScreen((s) => ({ ...s, ...patch }))}
              onApplyThemeToScreen={handleApplyThemeToScreen}
              onUpdateElement={(id, patch) => handleUpdateElement(id, patch)}
              onAlignElement={handleAlignElement}
              onReorderElement={handleReorderElement}
              onDuplicateElement={handleDuplicateElement}
              onDeleteElement={handleDeleteElement}
              onClose={() => setRightPanelOpen(false)}
            />
          </aside>
        )}

        {/* Mobile/Tablet Overlay Right Inspector Drawer */}
        {rightPanelOpen && (
          <div className="lg:hidden absolute inset-0 z-30 flex justify-end">
            <button
              type="button"
              aria-label="Close style inspector drawer"
              onClick={() => setRightPanelOpen(false)}
              className="flex-1 bg-black/50 cursor-pointer"
            />
            <aside className="w-[288px] max-w-[88vw] h-full border-l border-[#2C3E4C] shadow-2xl">
              <DesignerInspector
                screen={activeScreen}
                selectedElement={selectedElement}
                onUpdateScreen={(patch) => updateActiveScreen((s) => ({ ...s, ...patch }))}
                onApplyThemeToScreen={handleApplyThemeToScreen}
                onUpdateElement={(id, patch) => handleUpdateElement(id, patch)}
                onAlignElement={handleAlignElement}
                onReorderElement={handleReorderElement}
                onDuplicateElement={handleDuplicateElement}
                onDeleteElement={handleDeleteElement}
                onClose={() => setRightPanelOpen(false)}
              />
            </aside>
          </div>
        )}
      </div>

      {/* Export to Android Code Modal */}
      {exportModalOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Export Android UI code"
          className="fixed inset-0 z-50 bg-black/65 flex items-center justify-center p-4"
        >
          <div className="w-full max-w-3xl rounded-xl border border-[#2C3E4C] bg-[#141D26] overflow-hidden shadow-2xl flex flex-col max-h-[85vh]">
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-[#243441]">
              <div>
                <h2 className="text-sm font-display font-semibold text-[#EEF4F6]">
                  Export "{activeScreen.name}" to Android Project
                </h2>
                <p className="text-xs text-[#89A0AD]">
                  Adds Jetpack Compose Kotlin and Android XML layouts directly into Code Editor
                </p>
              </div>
              <button
                type="button"
                aria-label="Close export modal"
                onClick={() => setExportModalOpen(false)}
                className="p-1.5 rounded text-[#89A0AD] hover:text-white cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex items-center justify-between px-5 py-2.5 border-b border-[#22313D] bg-[#10171F]">
              <div className="flex items-center gap-1 p-0.5 rounded-lg bg-[#16202A] border border-[#253542]">
                <button
                  type="button"
                  onClick={() => setExportTab('compose')}
                  className={`px-3 py-1 rounded text-xs font-medium cursor-pointer ${
                    exportTab === 'compose' ? 'bg-[#3E8790] text-white' : 'text-[#9BB0BC]'
                  }`}
                >
                  Jetpack Compose (.kt)
                </button>
                <button
                  type="button"
                  onClick={() => setExportTab('xml')}
                  className={`px-3 py-1 rounded text-xs font-medium cursor-pointer ${
                    exportTab === 'xml' ? 'bg-[#3E8790] text-white' : 'text-[#9BB0BC]'
                  }`}
                >
                  Android XML (.xml)
                </button>
              </div>

              <button
                type="button"
                onClick={handleSendToEditor}
                className="px-4 py-1.5 rounded-lg bg-[#3E8790] hover:bg-[#35767E] text-xs font-semibold text-white cursor-pointer"
              >
                Add to Project & Open Editor
              </button>
            </div>

            <pre className="flex-1 overflow-auto p-4 text-xs font-mono text-[#D0DFE7] bg-[#0B1016] leading-relaxed">
              {exportTab === 'compose' ? generatedCompose : generatedXml}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
};
