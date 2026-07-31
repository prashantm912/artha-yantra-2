import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FreshnessBadge } from './FreshnessBadge.tsx';
import type { DataFreshness } from '../../api/types.ts';

function fresh(p: Partial<DataFreshness>): DataFreshness {
  return {
    asOf: '2026-07-11T15:14:00+05:30',
    source: 'capture',
    historyStart: null,
    staleSeconds: 30,
    complete: true,
    provenance: 'live',
    ...p,
  };
}

// Fixed "now" = 2026-07-11T15:15:00+05:30 → 60s after the sample asOf.
const NOW = Date.parse('2026-07-11T15:15:00+05:30');

describe('FreshnessBadge', () => {
  it('renders nothing when the envelope is absent or provenance-less', () => {
    const { container } = render(<FreshnessBadge freshness={undefined} />);
    expect(container).toBeEmptyDOMElement();
    const { container: c2 } = render(
      <FreshnessBadge freshness={fresh({ provenance: null })} />,
    );
    expect(c2).toBeEmptyDOMElement();
  });

  it('shows a fresh LIVE read with the IST time and bull tone', () => {
    render(<FreshnessBadge freshness={fresh({})} now={NOW} />);
    expect(screen.getByText('Live')).toBeInTheDocument();
    expect(screen.getByText('as of 15:14')).toBeInTheDocument();
    expect(screen.getByText('Live').closest('[data-provenance]')).toHaveClass('text-bull');
  });

  it('flips a LIVE read to Stale (warn) once the client-side age passes the threshold', () => {
    // asOf 20 minutes before NOW → 1200s > default 180s.
    render(
      <FreshnessBadge
        freshness={fresh({ asOf: '2026-07-11T14:55:00+05:30' })}
        now={NOW}
      />,
    );
    expect(screen.getByText('Stale')).toBeInTheDocument();
    expect(screen.queryByText('Live')).not.toBeInTheDocument();
    expect(screen.getByText('Stale').closest('[data-provenance]')).toHaveClass('text-warn');
  });

  it('flips to Stale when the server reports the datapoint incomplete, regardless of age', () => {
    render(<FreshnessBadge freshness={fresh({ complete: false })} now={NOW} />);
    expect(screen.getByText('Stale')).toBeInTheDocument();
  });

  it('keeps a briefly-stale LIVE read on HH:MM alone (no date noise under a day)', () => {
    render(
      <FreshnessBadge freshness={fresh({ asOf: '2026-07-11T14:55:00+05:30' })} now={NOW} />,
    );
    expect(screen.getByText('as of 14:55')).toBeInTheDocument();
  });

  it('adds the DATE once a LIVE read is a day or more old, so a served-from-capture chain cannot read like a fresh one', () => {
    // The chain-table degrades to the last CAPTURED book after close rather than 503-ing, so a
    // days-old chain renders in the same table as a live one; HH:MM alone would make "yesterday's
    // close" and "three weeks ago" the same chip.
    render(
      <FreshnessBadge
        freshness={fresh({ asOf: '2026-06-20T15:30:00+05:30', complete: false })}
        now={NOW}
      />,
    );
    expect(screen.getByText('Stale')).toBeInTheDocument();
    expect(screen.getByText('as of 2026-06-20 15:30')).toBeInTheDocument();
    expect(screen.queryByText('as of 15:30')).not.toBeInTheDocument();
  });

  it('renders an EOD read as the date, muted, never coloured alone', () => {
    render(
      <FreshnessBadge
        freshness={fresh({ provenance: 'eod', source: 'bhavcopy' })}
        now={NOW}
      />,
    );
    // The leading WORD carries the state (a11y: not colour alone).
    expect(screen.getByText('EOD')).toBeInTheDocument();
    expect(screen.getByText('as of 2026-07-11')).toBeInTheDocument();
    expect(screen.getByText('EOD').closest('[data-provenance]')).toHaveClass('text-ay-muted');
  });

  it('labels a candle-derived history read as Derived', () => {
    render(
      <FreshnessBadge
        freshness={fresh({ provenance: 'derived', source: 'candle-derived' })}
        now={NOW}
      />,
    );
    expect(screen.getByText('Derived')).toBeInTheDocument();
  });

  it('carries the source + age on the hover title', () => {
    render(<FreshnessBadge freshness={fresh({})} now={NOW} />);
    const chip = screen.getByText('Live').closest('[data-provenance]');
    expect(chip).toHaveAttribute('title', expect.stringContaining('source: capture'));
    expect(chip?.getAttribute('title')).toContain('captured 1m ago');
  });
});
