import type { VocalAudioEngine } from '@/features/vocal-audio-engine';

export interface ConnectionInterruption {
  interruptionId: string;
  roomId: number;
  performanceId: number;
  participantId: number;
  interruptedAt: string;
  playbackPositionMs: number;
}

const KEY = 'ssasukae:performer-interruption';
let currentEngine: VocalAudioEngine | null = null;
let memory: ConnectionInterruption | null = null;

export function registerPerformerEngine(engine: VocalAudioEngine | null) {
  currentEngine = engine;
}

export function readInterruption(): ConnectionInterruption | null {
  if (memory) return memory;
  try {
    if (typeof window === 'undefined') return null;
    const value = JSON.parse(window.sessionStorage.getItem(KEY) ?? 'null') as ConnectionInterruption | null;
    if (value && Number.isFinite(value.playbackPositionMs) && value.playbackPositionMs >= 0
        && Number.isFinite(Date.parse(value.interruptedAt)) && typeof value.interruptionId === 'string') memory = value;
  } catch { /* Storage may be unavailable; the in-memory record still supports reconnects. */ }
  return memory;
}

export function captureInterruption(input: Omit<ConnectionInterruption, 'interruptionId' | 'playbackPositionMs'>,
  fallbackPositionMs: number): ConnectionInterruption {
  const old = readInterruption();
  if (old?.roomId === input.roomId && old.performanceId === input.performanceId && old.participantId === input.participantId) return old;
  const position = currentEngine?.getMrPositionMs() ?? fallbackPositionMs;
  currentEngine?.stopMr();
  currentEngine?.closeMic();
  memory = { ...input, interruptionId: crypto.randomUUID(), playbackPositionMs: Math.max(0, Math.floor(position)) };
  try { window.sessionStorage.setItem(KEY, JSON.stringify(memory)); } catch { /* Keep memory fallback. */ }
  return memory;
}

export function clearInterruption(interruptionId: string) {
  if (readInterruption()?.interruptionId !== interruptionId) return;
  memory = null;
  try { window.sessionStorage.removeItem(KEY); } catch { /* Keep memory fallback. */ }
}
