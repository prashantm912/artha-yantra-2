/**
 * Exact-decimal display helpers (C-2.25): prices arrive as JSON STRINGS and are formatted and
 * compared without ever flowing through parseFloat arithmetic. Comparison walks sign, integer
 * length, then lexicographic digits - exact for normalized decimal strings of any precision.
 */

/** Compares two decimal strings; negative/zero/positive like compareTo. */
export function compareDecimal(a: string, b: string): number {
  const na = normalize(a);
  const nb = normalize(b);
  if (na.sign !== nb.sign) {
    return na.sign < nb.sign ? -1 : 1;
  }
  const magnitude = compareMagnitude(na, nb);
  return na.sign < 0 ? -magnitude : magnitude;
}

/** Formats a decimal string to a fixed number of fraction digits (string-safe rounding-free). */
export function formatDecimal(value: string, fractionDigits: number): string {
  const { sign, int, frac } = normalize(value);
  const padded = (frac + '0'.repeat(fractionDigits)).slice(0, fractionDigits);
  const body = fractionDigits === 0 ? int : `${int}.${padded}`;
  return sign < 0 ? `-${body}` : body;
}

/** True when the value is strictly negative. */
export function isNegative(value: string): boolean {
  return normalize(value).sign < 0;
}

interface Parts {
  sign: number;
  int: string;
  frac: string;
}

function normalize(value: string): Parts {
  let v = value.trim();
  let sign = 1;
  if (v.startsWith('-')) {
    sign = -1;
    v = v.slice(1);
  }
  const [intRaw, fracRaw = ''] = v.split('.');
  const int = intRaw.replace(/^0+(?=\d)/, '');
  const frac = fracRaw.replace(/0+$/, '');
  if (int === '0' && frac === '') {
    sign = 1; // -0 normalizes to 0
  }
  return { sign, int, frac };
}

function compareMagnitude(a: Parts, b: Parts): number {
  if (a.int.length !== b.int.length) {
    return a.int.length < b.int.length ? -1 : 1;
  }
  if (a.int !== b.int) {
    return a.int < b.int ? -1 : 1;
  }
  const width = Math.max(a.frac.length, b.frac.length);
  const fa = a.frac.padEnd(width, '0');
  const fb = b.frac.padEnd(width, '0');
  return fa === fb ? 0 : fa < fb ? -1 : 1;
}
