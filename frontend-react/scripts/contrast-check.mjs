// WCAG 2.1 contrast-ratio helper for the --ay-* theme tokens.
// Usage: node scripts/contrast-check.mjs '#767676' '#0a0a0a' [minRatio]
//   - two hex colours → prints their contrast ratio and PASS/FAIL against minRatio (default 3.0).
//   - no args → self-checks the high-contrast theme border vs surface-1 (≥3:1, the non-text UI bar).
// Pure stdlib, no deps. Exits non-zero on FAIL so it can gate CI if wired in.

import { pathToFileURL } from 'node:url';

function channelLuminance(c) {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

function relativeLuminance(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) throw new Error(`not a 6-digit hex colour: ${hex}`);
  const n = parseInt(m[1], 16);
  const r = (n >> 16) & 0xff;
  const g = (n >> 8) & 0xff;
  const b = n & 0xff;
  return 0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b);
}

export function contrastRatio(a, b) {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const hi = Math.max(la, lb);
  const lo = Math.min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}

function report(fg, bg, min) {
  const ratio = contrastRatio(fg, bg);
  const pass = ratio >= min;
  console.log(`${fg} on ${bg}: ${ratio.toFixed(3)}:1 (min ${min}:1) → ${pass ? 'PASS' : 'FAIL'}`);
  return pass;
}

// Run as a script (not when imported). pathToFileURL handles Windows drive letters + separators.
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [, , fg, bg, minArg] = process.argv;
  const min = minArg ? Number(minArg) : 3.0;
  let ok;
  if (fg && bg) {
    ok = report(fg, bg, min);
  } else {
    // Default self-check: high-contrast theme UI border vs its surface-1.
    console.log('high-contrast theme — non-text UI border vs surface-1 (WCAG 1.4.11, ≥3:1):');
    ok = report('#767676', '#0a0a0a', 3.0);
  }
  process.exit(ok ? 0 : 1);
}
