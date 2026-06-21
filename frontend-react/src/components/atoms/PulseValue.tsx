import { useEffect, useRef, useState } from 'react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';

// LTP cell that flashes (.ay-pulse, reduced-motion aware) when its decimal-string value changes — the
// oipulse animated price cell. Bold to read as the primary number. Decimal-string in, never parseFloat.
interface PulseValueProps {
  value: string | null;
  digits?: number;
}

export function PulseValue({ value, digits = 2 }: PulseValueProps) {
  const prev = useRef(value);
  const [flash, setFlash] = useState(false);

  useEffect(() => {
    if (value != null && prev.current != null && value !== prev.current) {
      setFlash(true);
      const t = setTimeout(() => setFlash(false), 500);
      prev.current = value;
      return () => clearTimeout(t);
    }
    prev.current = value;
    return undefined;
  }, [value]);

  return (
    <span className={cn('rounded px-1 font-semibold tabular-nums', flash && 'ay-pulse')}>
      {value != null ? formatDecimal(value, digits) : '—'}
    </span>
  );
}
