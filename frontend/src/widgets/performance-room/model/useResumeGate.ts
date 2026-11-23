import { useEffect, useState } from 'react';
import { useStageStore } from './stageStore';

export function useResumeGate(): boolean {
  const pending = useStageStore((state) => state.localInterruption);
  const resumeAt = useStageStore((state) => state.resumeAt);
  const offset = useStageStore((state) => state.serverClockOffsetMs);
  const [released, setReleased] = useState<string | null>(null);
  useEffect(() => {
    if (resumeAt === null) return;
    const delay = Math.max(0, Date.parse(resumeAt) - (Date.now() + offset));
    const timer = setTimeout(() => setReleased(resumeAt), delay);
    return () => clearTimeout(timer);
  }, [resumeAt, offset]);
  return pending === null && (resumeAt === null || released === resumeAt);
}
