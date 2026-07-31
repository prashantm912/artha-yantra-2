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

  it('adds the DATE once a LIVE read is from a previous IST session, so a served-from-capture chain cannot read like a fresh one', () => {
    // The chain-table degrades to the last CAPTURED book after close rather than 503-ing, so an old
    // chain renders in the same table as a live one; HH:MM alone would make "yesterday's close" and
    // "three weeks ago" the same chip.
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

  it('renders a UTC-serialised asOf in IST, never the raw wire wall-clock', () => {
    // market-data runs on Clock.systemUTC(), so a 15:30 IST capture serialises as ...T10:00:00Z.
    // Slicing the ISO string would render "10:00" — a wrong time on the badge's whole purpose.
    render(
      <FreshnessBadge
        freshness={fresh({ asOf: '2026-07-11T10:00:00Z' })}
        now={Date.parse('2026-07-11T10:02:00Z')}
      />,
    );
    expect(screen.getByText('as of 15:30')).toBeInTheDocument();
    expect(screen.queryByText('as of 10:00')).not.toBeInTheDocument();
  });

  it('dates an OVERNIGHT capture even though it is under 24h old — the commonest way this badge is seen', () => {
    // 15:30 IST close, read at 09:15 IST next morning = ~17.75h. An elapsed-hours threshold would
    // hide the date here and render it identically to a chain captured minutes ago.
    render(
      <FreshnessBadge
        freshness={fresh({ asOf: '2026-07-10T15:30:00+05:30', complete: false })}
        now={Date.parse('2026-07-11T09:15:00+05:30')}
      />,
    );
    expect(screen.getByText('as of 2026-07-10 15:30')).toBeInTheDocument();
  });

  it('still omits the date for a same-IST-day stale read even across the UTC midnight boundary', () => {
    // The capture and the read sit on the SAME IST day but on OPPOSITE sides of 00:00 UTC:
    // 05:00 IST = 23:30Z the PREVIOUS UTC day, 06:00 IST = 00:30Z the next. A UTC-date comparison
    // would therefore call the capture "yesterday" and wrongly print a date. (An earlier version of
    // this test used 23:00-23:40 IST = 17:30-18:10Z, which never crosses UTC midnight at all and so
    // could not fail for the reason it claimed — caught by cross-vendor review.)
    render(
      <FreshnessBadge
        freshness={fresh({ asOf: '2026-07-11T05:00:00+05:30', complete: false })}
        now={Date.parse('2026-07-11T06:00:00+05:30')}
      />,
    );
    expect(screen.getByText('as of 05:00')).toBeInTheDocument();
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
