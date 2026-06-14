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

/**
 * Exact decimal subtraction `a - b` via BigInt scaling — never `parseFloat`. Used for basis
 * (FUT − spot) and mark-to-market (last tick − avg entry) where the result must stay exact.
 */
export function subtractDecimal(a: string, b: string): string {
  const scale = Math.max(fractionLen(a), fractionLen(b));
  return fromScaled(toScaled(a, scale) - toScaled(b, scale), scale);
}

/** Exact decimal × integer (e.g. per-unit P&L × qty, price × qty notional) — never `parseFloat`. */
export function multiplyByInt(value: string, n: number): string {
  const scale = fractionLen(value);
  return fromScaled(toScaled(value, scale) * BigInt(Math.trunc(n)), scale);
}

function fractionLen(value: string): number {
  const dot = value.indexOf('.');
  return dot < 0 ? 0 : value.length - dot - 1;
}

function toScaled(value: string, scale: number): bigint {
  const neg = value.trim().startsWith('-');
  const v = neg ? value.trim().slice(1) : value.trim();
  const [int, frac = ''] = v.split('.');
  const mag = BigInt((int || '0') + frac.padEnd(scale, '0').slice(0, scale));
  return neg ? -mag : mag;
}

function fromScaled(scaled: bigint, scale: number): string {
  const neg = scaled < 0n;
  const digits = (neg ? -scaled : scaled).toString().padStart(scale + 1, '0');
  const int = digits.slice(0, digits.length - scale);
  const frac = scale === 0 ? '' : digits.slice(digits.length - scale).replace(/0+$/, '');
  const body = frac ? `${int}.${frac}` : int;
  return neg && body !== '0' ? `-${body}` : body;
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
