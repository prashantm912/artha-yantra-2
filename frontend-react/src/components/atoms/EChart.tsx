import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import type { EChartsOption } from 'echarts';
import { cn } from '../../lib/cn.ts';

// Lazy ECharts wrapper (§20.1). Lives only in routes that import it, so the ~1 MB ECharts bundle is a
// separate chunk behind a React.lazy route — never in the main payload. Themes from the live --ay-*
// tokens (read off the element) and RE-themes when data-theme flips on <html>; resizes via
// ResizeObserver (the zoneless-Angular autoSize-miss has no React analogue, but RO is still correct).
// a11y: the canvas is opaque, so the host div is role="img" with an explicit label + ECharts' own
// generated aria description.

/** The subset of --ay-* tokens an ECharts option needs (resolved hex, theme-current). */
export interface ChartTheme {
  text: string;
  muted: string;
  border: string;
  grid: string;
  crosshair: string;
  bull: string;
  bear: string;
  accent: string;
  warn: string;
  surface1: string;
}

function readTheme(el: HTMLElement): ChartTheme {
  const s = getComputedStyle(el);
  const v = (name: string) => s.getPropertyValue(name).trim();
  return {
    text: v('--ay-text'),
    muted: v('--ay-text-muted'),
    border: v('--ay-border'),
    grid: v('--ay-chart-grid'),
    crosshair: v('--ay-chart-crosshair'),
    bull: v('--ay-bull'),
    bear: v('--ay-bear'),
    accent: v('--ay-accent'),
    warn: v('--ay-warn'),
    surface1: v('--ay-surface-1'),
  };
}

interface EChartProps {
  /** Builds the option from the live theme — re-invoked on data change and on data-theme flips. */
  makeOption: (theme: ChartTheme) => EChartsOption;
  /** Fixed height; pass `undefined` and a responsive `h-*` className to drive height from CSS. */
  height?: number | string;
  ariaLabel: string;
  className?: string;
}

export function EChart({ makeOption, height, ariaLabel, className }: EChartProps) {
  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  // init once; resize with the container.
  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    const chart = echarts.init(el, undefined, { renderer: 'canvas' });
    chartRef.current = chart;
    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(el);
    return () => {
      ro.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  // (re)apply the option on data change and whenever the theme flips on <html>.
  useEffect(() => {
    const el = elRef.current;
    const chart = chartRef.current;
    if (!el || !chart) return;
    const apply = () => chart.setOption(makeOption(readTheme(el)), true);
    apply();
    const mo = new MutationObserver(apply);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => mo.disconnect();
  }, [makeOption]);

  return (
    <div
      ref={elRef}
      role="img"
      aria-label={ariaLabel}
      className={cn('w-full', className)}
      style={height != null ? { height } : undefined}
    />
  );
}
