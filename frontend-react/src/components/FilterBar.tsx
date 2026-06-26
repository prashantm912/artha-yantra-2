import { useEffect } from 'react';
import { ChevronDown } from 'lucide-react';
import {
  OI_INTERVALS,
  useSymbolContext,
  type OiInterval,
} from '../stores/symbolContext.store.ts';
import { useExpiries, useUnderlyings } from '../api/instruments.ts';
import { useDefaultDate } from '../api/marketCalendar.ts';
import { cn } from '../lib/cn.ts';
import { ModeToggle } from './atoms/ModeToggle.tsx';
import { DateInput } from './atoms/DateInput.tsx';

// The universal OI control bar (master plan §20): generalizes the Angular OiControlBar. Config-driven
// (showName/showExpiry/allowedIntervals) and fed by the shared cascade — selecting a name reloads its
// expiries (useExpiries) and defaults to the nearest. Mutating the context is what pages react to via
// the TanStack query key; the bar itself fires no data requests.

interface FilterBarProps {
  showName?: boolean;
  showExpiry?: boolean;
  showInterval?: boolean;
  allowedIntervals?: readonly OiInterval[];
  /** Restrict the Name select to this exact list (e.g. Connecting Dots = indices only). */
  names?: readonly string[];
}

const UNDERLYING_FALLBACK = ['NIFTY 50', 'NIFTY BANK'];

// Bar-local native <select> styled for the revamped control look (§20). Kept native (real <select>):
// the options-chain e2e drives these with .locator('option')/.selectOption()/.inputValue() + an
// empty-options regression check + axe — so this MUST stay a true select, NOT a Radix popover. Styled
// inline here (not via the shared Select atom, which BacktestRunner also uses): appearance-none strips
// the OS chrome, a pointer-events-none ChevronDown is the custom caret, focus rides the global
// :focus-visible rule (index.css §1.4).
function FilterSelect({
  ariaLabel,
  value,
  options,
  onChange,
  placeholder,
  disabled,
  title,
}: {
  ariaLabel: string;
  value: string | null;
  options: readonly string[];
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  title?: string;
}) {
  return (
    <div className="relative">
      <select
        aria-label={ariaLabel}
        title={title}
        disabled={disabled}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className={cn(
          'h-9 appearance-none rounded-md border border-ay-border bg-surface-2 pl-2 pr-7',
          'text-body-sm text-ay-text disabled:opacity-50',
        )}
      >
        {(placeholder || value === null) && (
          <option value="" disabled>
            {placeholder ?? 'Select…'}
          </option>
        )}
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
      <ChevronDown
        aria-hidden="true"
        className="pointer-events-none absolute right-2 top-1/2 size-4 -translate-y-1/2 text-ay-muted"
      />
    </div>
  );
}

export function FilterBar({
  showName = true,
  showExpiry = true,
  showInterval = true,
  allowedIntervals = OI_INTERVALS,
  names,
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
  const defaultDate = useDefaultDate();

  // Cascade: default expiry to the nearest once the list loads and none is selected.
  useEffect(() => {
    if (showExpiry && !expiry && expiries.data && expiries.data.length > 0) {
      setExpiry(expiries.data[0]);
    }
  }, [showExpiry, expiry, expiries.data, setExpiry]);

  // Entering history mode with no date picked → default to the last trading session (#12), so a
  // weekend/holiday History view lands on real data instead of an empty 'today'.
  useEffect(() => {
    if (mode === 'history' && !date) {
      setDate(defaultDate);
    }
  }, [mode, date, defaultDate, setDate]);

  const nameOptions = names
    ? [...names]
    : underlyings.data && underlyings.data.length > 0
      ? underlyings.data
      : UNDERLYING_FALLBACK;

  return (
    // No own bottom margin: every page wraps FilterBar in an `mb-3 flex items-center` row alongside
    // GoButton etc. A bottom margin here would inflate FilterBar's flex margin-box, pushing the
    // marginless siblings (Go, ColumnSettings) ~6px lower under items-center. Spacing is the wrapper's.
    <div className="flex flex-wrap items-center gap-2">
      {showName && (
        <FilterSelect
          ariaLabel="Underlying"
          value={name}
          options={nameOptions}
          onChange={setName}
          placeholder="Underlying"
          title="The index or stock whose option/OI data this page shows"
        />
      )}
      {showExpiry && (
        <FilterSelect
          ariaLabel="Expiry"
          value={expiry}
          options={expiries.data ?? []}
          onChange={setExpiry}
          placeholder="Expiry"
          disabled={!expiries.data?.length}
          title="The options-contract expiry date; nearest weekly is selected by default"
        />
      )}
      {showInterval && (
        <FilterSelect
          ariaLabel="Interval"
          value={interval}
          options={[...allowedIntervals]}
          onChange={(v) => setInterval(v as OiInterval)}
          title="The candle/aggregation interval used to bucket the series"
        />
      )}
      <ModeToggle mode={mode} onChange={setMode} />
      {mode === 'history' && (
        <DateInput
          ariaLabel="History date"
          value={date}
          onChange={setDate}
          title="The past trading session to replay (defaults to the last market day)"
        />
      )}
    </div>
  );
}
