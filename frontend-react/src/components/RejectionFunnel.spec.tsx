import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import type { EvalFunnel, RailCount } from '../api/signalRejections.ts';

// Mutable holders so one file covers a populated day, an unrecorded day and an unknown outcome tag.
const state = vi.hoisted(() => ({
  funnel: null as EvalFunnel | null,
  rails: [] as RailCount[],
}));

vi.mock('../api/signalRejections.ts', async (orig) => {
  const actual = await orig<typeof import('../api/signalRejections.ts')>();
  return {
    ...actual, // keep the shared types
    useEvalFunnel: () => ({ isLoading: false, isError: false, data: state.funnel ?? undefined }),
    useRejectionRailCounts: () => ({ data: { items: state.rails } }),
  };
});

// The EChart atom calls echarts.init on a real canvas, which jsdom does not provide. Substitute a
// component that INVOKES makeOption exactly as the atom does and publishes the resulting sankey links
// — so the chart's data (which is the part that can be wrong) is still asserted, without a canvas.
vi.mock('./atoms/EChart.tsx', () => ({
  EChart: ({
    makeOption,
    ariaLabel,
  }: {
    makeOption: (t: Record<string, string>) => { series: { links: unknown[] }[] };
    ariaLabel: string;
  }) => {
    const option = makeOption({
      text: '#fff',
      muted: '#888',
      border: '#333',
      grid: '#222',
      crosshair: '#444',
      bull: '#0f0',
      bear: '#f00',
      accent: '#00f',
      warn: '#ff0',
      surface1: '#111',
    });
    return (
      <div
        role="img"
        aria-label={ariaLabel}
        data-testid="funnel-sankey"
        data-links={JSON.stringify(option.series[0].links)}
      />
    );
  },
}));

import RejectionFunnel from './RejectionFunnel.tsx';

/**
 * A day whose 1,000 evaluations narrow to 10 fires: 700 die at the chart gate, 200 at the composite,
 * 90 at the confluence gate. Three outcomes are ABSENT rather than zero, which is how the table really
 * looks — V053 only writes combinations it observed.
 */
const BUSY_DAY: EvalFunnel = {
  sessionDate: '2026-08-01',
  boots: 1,
  items: [
    { strategySlug: 'scalp-a', outcome: 'chart-gate-failed', evalCount: 700 },
    { strategySlug: 'scalp-a', outcome: 'composite-below-threshold', evalCount: 200 },
    { strategySlug: 'scalp-a', outcome: 'confluence-blocked', evalCount: 90 },
    { strategySlug: 'scalp-a', outcome: 'fired', evalCount: 10 },
    // A second, quieter strategy — the funnel is PER strategy, so these must not leak into scalp-a.
    { strategySlug: 'scalp-b', outcome: 'chart-gate-failed', evalCount: 5 },
  ],
};

function renderFunnel() {
  return render(
    <RejectionFunnel
      date="2026-08-01"
      from="2026-08-01T00:00:00+05:30"
      to="2026-08-02T00:00:00+05:30"
    />,
  );
}

function sankeyLinks(): { source: string; target: string; value: number }[] {
  const raw = screen.getByTestId('funnel-sankey').getAttribute('data-links') ?? '[]';
  return JSON.parse(raw);
}

beforeEach(() => {
  state.funnel = null;
  state.rails = [];
});

describe('RejectionFunnel', () => {
  it('walks the ladder from the true denominator down to the fired survivors', () => {
    state.funnel = BUSY_DAY;
    renderFunnel();

    // The busiest strategy is the default view, and its denominator is ITS OWN evaluation total —
    // scalp-b's 5 evaluations must not be folded in.
    expect(screen.getByText(/1,000 evaluations/)).toBeInTheDocument();

    const chartRow = screen.getByRole('row', { name: /Chart gate said no/ });
    expect(within(chartRow).getByText('1,000')).toBeInTheDocument(); // entering
    expect(within(chartRow).getByText('700')).toBeInTheDocument(); // left here
    expect(within(chartRow).getByText('300')).toBeInTheDocument(); // surviving
    expect(within(chartRow).getByText('70.0%')).toBeInTheDocument();

    const compositeRow = screen.getByRole('row', { name: /Composite below threshold/ });
    expect(within(compositeRow).getByText('200')).toBeInTheDocument();
    expect(within(compositeRow).getByText('20.0%')).toBeInTheDocument();

    // The survivors of the last leak ARE the fires — the ladder closes.
    const firedRow = screen.getByRole('row', { name: /^Fired/ });
    expect(within(firedRow).getByText('10')).toBeInTheDocument();
    expect(within(firedRow).getByText('1.0%')).toBeInTheDocument();
  });

  it('renders a day with no recorded counts as absent, never as zeros', () => {
    state.funnel = { sessionDate: '2026-08-03', boots: 0, items: [] };
    renderFunnel();

    expect(screen.getByText(/no denominator to stand on/)).toBeInTheDocument();
    expect(screen.getByText(/not the same as .nothing was evaluated./)).toBeInTheDocument();
    // No chart and no ladder: there is nothing measured to draw.
    expect(screen.queryByTestId('funnel-sankey')).toBeNull();
    expect(screen.queryByRole('row', { name: /^Fired/ })).toBeNull();
  });

  it('drops zero-valued flows and hangs the blocking rails off the confluence-blocked node', () => {
    state.funnel = BUSY_DAY;
    state.rails = [
      { rail: 'vwap-align', count: 60 },
      { rail: 'volume-floor', count: 30 },
    ];
    renderFunnel();

    const links = sankeyLinks();
    // Three outcomes were never recorded, so their stages must not be drawn at all — a zero-width
    // box in the flow would read as a stage bars passed through.
    expect(links.every((l) => l.value > 0)).toBe(true);
    expect(links.some((l) => l.target === 'Indicators warming')).toBe(false);
    expect(links.some((l) => l.target === 'Discipline freeze')).toBe(false);

    // The rails hang off the leak node, and the leak keeps the DENOMINATOR's 90 on its inbound link
    // rather than being rewritten to the rails' 90 — the two are bounded differently and may differ.
    expect(links).toContainEqual({
      source: 'Gate present',
      target: 'Confluence gate blocked',
      value: 90,
    });
    expect(links).toContainEqual({
      source: 'Confluence gate blocked',
      target: 'vwap-align',
      value: 60,
    });
    expect(links).toContainEqual({
      source: 'Confluence gate blocked',
      target: 'volume-floor',
      value: 30,
    });
    expect(links).toContainEqual({ source: 'Gate present', target: 'Fired', value: 10 });
  });

  it('surfaces an outcome tag it does not know about instead of silently dropping it', () => {
    state.funnel = {
      sessionDate: '2026-08-01',
      boots: 2,
      items: [
        { strategySlug: 'scalp-a', outcome: 'chart-gate-failed', evalCount: 90 },
        { strategySlug: 'scalp-a', outcome: 'some-future-outcome', evalCount: 10 },
      ],
    };
    renderFunnel();

    expect(screen.getByText(/some-future-outcome/)).toBeInTheDocument();
    // It still counts toward the denominator — dropping it would quietly shrink every rate.
    expect(screen.getByText(/100 evaluations/)).toBeInTheDocument();
    // A restarted day says so rather than presenting the sum as one continuous run.
    expect(screen.getByText(/2 boots/)).toBeInTheDocument();
  });
});
