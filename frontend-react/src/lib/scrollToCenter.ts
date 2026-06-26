import { useEffect, type RefObject } from 'react';

/**
 * Centre `rowRef` vertically inside the scroll container `containerRef` whenever `key` changes —
 * used to land the ATM strike in the middle of the options tables on load and when the ATM moves.
 * Scrolls ONLY the container (computes a scrollTop delta), never the page, so it can't reintroduce
 * the outer page-scroll. No-op until both refs and a non-null key are present.
 */
export function useCenterRowInScroll(
  containerRef: RefObject<HTMLElement | null>,
  rowRef: RefObject<HTMLElement | null>,
  key: unknown,
): void {
  useEffect(() => {
    if (key == null) return;
    const container = containerRef.current;
    const row = rowRef.current;
    if (!container || !row) return;
    const cRect = container.getBoundingClientRect();
    const rRect = row.getBoundingClientRect();
    const delta = rRect.top - cRect.top - (container.clientHeight - rRect.height) / 2;
    container.scrollTop += delta;
  }, [containerRef, rowRef, key]);
}
