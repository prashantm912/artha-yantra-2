/**
 * rAF conflation (C-2.25): handlers write the LATEST value per key; one requestAnimationFrame
 * loop flushes the map at most once per frame (~16 ms). Under burst load the UI renders the
 * newest value - never a queue of stale ones. Exposed as a tiny class so stores share one
 * battle-tested implementation (ticks later, signal batches now).
 */
export class ConflationBuffer<T> {
  private readonly pending = new Map<string, T>();
  private scheduled = false;

  constructor(
    private readonly flush: (batch: Map<string, T>) => void,
    private readonly schedule: (callback: () => void) => void = (cb) =>
      typeof requestAnimationFrame === 'function'
        ? void requestAnimationFrame(() => cb())
        : void setTimeout(cb, 16),
  ) {}

  /** Stores the newest value for a key and arms the per-frame flush. */
  set(key: string, value: T): void {
    this.pending.set(key, value);
    if (!this.scheduled) {
      this.scheduled = true;
      this.schedule(() => {
        this.scheduled = false;
        const batch = new Map(this.pending);
        this.pending.clear();
        this.flush(batch);
      });
    }
  }
}
