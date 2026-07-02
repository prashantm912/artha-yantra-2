import type { Page } from '@playwright/test';

// Mock owner password — override via E2E_OWNER_PASSWORD to match your .env hash.
export const OWNER_PASSWORD = process.env.E2E_OWNER_PASSWORD ?? 'e2e-owner-password';

/**
 * Wait for every FINITE animation to finish before running axe. Motion keeps OPACITY fades even
 * under reduced motion (only transforms are gated), so axe raced the LoadBeat entrance and read
 * blended colours (e.g. the light-theme muted text mid-fade → 4.42:1 phantom contrast failures).
 * Infinite animations (the LiveDot breathe) are skipped; a 2s cap keeps a stuck animation from
 * hanging the spec.
 */
export async function settleAnimations(page: Page): Promise<void> {
  await page.evaluate(async () => {
    const finite = document.getAnimations().filter((a) => {
      const timing = a.effect?.getTiming?.();
      return timing != null && timing.iterations !== Infinity;
    });
    await Promise.race([
      Promise.all(finite.map((a) => a.finished.catch(() => undefined))),
      new Promise((resolve) => setTimeout(resolve, 2000)),
    ]);
  });
}
