// Pure crossing-detection for the Advance Chart audio price alert (§advance-chart extras). Given the
// previous and latest close, decide whether an armed threshold was crossed since the last observation.
// Kept pure + framework-free so the component just plays a beep on a `true` — the rule (and the
// once-per-crossing debounce, which the caller enforces by advancing `prev`) is what the spec pins.

/** Whether the close moved onto or through `threshold` since the previous observation. Symmetric in
 *  direction: an upward cross and a downward cross both fire, and a tick that lands EXACTLY on the level
 *  (from either side) counts as a cross. A move that starts on the level and leaves it does NOT re-fire
 *  (the cross already happened). Returns false on a null prev (first observation — nothing to cross
 *  yet), a flat tick (prev === latest), or a non-finite input. */
export function crossedThreshold(prev: number | null, latest: number, threshold: number): boolean {
  if (prev === null || !Number.isFinite(prev) || !Number.isFinite(latest)) return false;
  if (prev === latest || prev === threshold) return false;
  const dPrev = prev - threshold;
  const dLatest = latest - threshold;
  // Opposite sides of the level, or the latest tick landed exactly on it (prev was strictly off it).
  return dPrev * dLatest < 0 || dLatest === 0;
}
