import { useEffect } from 'react';
import {
  OI_INTERVALS,
  useSymbolContext,
  type OiInterval,
} from '../stores/symbolContext.store.ts';
import { useExpiries, useUnderlyings } from '../api/instruments.ts';
import { Select } from './atoms/Select.tsx';
import { ModeToggle } from './atoms/ModeToggle.tsx';
import { DateInput } from './atoms/DateInput.tsx';

// The universal OI control bar (master plan §20): generalizes the Angular OiControlBar. Config-driven
// (showName/showExpiry/allowedIntervals) and fed by the shared cascade — selecting a name reloads its
// expiries (useExpiries) and defaults to the nearest. Mutating the context is what pages react to via
// the TanStack query key; the bar itself fires no data requests.

interface FilterBarProps {
  showName?: boolean;
  showExpiry?: boolean;
  allowedIntervals?: readonly OiInterval[];
}

const UNDERLYING_FALLBACK = ['NIFTY 50', 'NIFTY BANK'];

export function FilterBar({
  showName = true,
  showExpiry = true,
  allowedIntervals = OI_INTERVALS,
}: FilterBarProps) {
  const name = useSymbolContext((s) => s.name);
  const expiry = useSymbolContext((s) => s.expiry);
  const interval = useSymbolContext((s) => s.interval);
  const mode = useSymbolContext((s) => s.mode);
  const date = useSymbolContext((s) => s.date);
  const setName = useSymbolContext((s) => s.setName);
  const setExpiry = useSymbolContext((s) => s.setExpiry);
  const setInterval = useSymbolContext((s) => s.setInterval);
  const setMode = useSymbolContext((s) => s.setMode);
  const setDate = useSymbolContext((s) => s.setDate);

  const underlyings = useUnderlyings();
  const expiries = useExpiries(name);

  // Cascade: default expiry to the nearest once the list loads and none is selected.
  useEffect(() => {
    if (showExpiry && !expiry && expiries.data && expiries.data.length > 0) {
      setExpiry(expiries.data[0]);
    }
  }, [showExpiry, expiry, expiries.data, setExpiry]);

  const nameOptions =
    underlyings.data && underlyings.data.length > 0 ? underlyings.data : UNDERLYING_FALLBACK;

  return (
    <div className="mb-3 flex flex-wrap items-center gap-2">
      {showName && (
        <Select
          ariaLabel="Underlying"
          value={name}
          options={nameOptions}
          onChange={setName}
          placeholder="Underlying"
        />
      )}
      {showExpiry && (
        <Select
          ariaLabel="Expiry"
          value={expiry}
          options={expiries.data ?? []}
          onChange={setExpiry}
          placeholder="Expiry"
          disabled={!expiries.data?.length}
        />
      )}
      <Select
        ariaLabel="Interval"
        value={interval}
        options={[...allowedIntervals]}
        onChange={(v) => setInterval(v as OiInterval)}
      />
      <ModeToggle mode={mode} onChange={setMode} />
      {mode === 'history' && (
        <DateInput ariaLabel="History date" value={date} onChange={setDate} />
      )}
    </div>
  );
}
