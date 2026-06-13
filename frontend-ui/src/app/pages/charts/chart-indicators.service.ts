import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** Registry entry from `GET /api/v1/indicators` (Phase 40B). */
export interface IndicatorMeta {
  id: string;
  label: string;
  params: { name: string; defaultValue: unknown }[];
  outputs: string[];
  render: 'line' | 'histogram';
  pane: 'price' | 'sub';
  requiresContext: boolean;
}

/** One overlay series point already split into epoch-ms + decimal-string (chart render boundary). */
export interface OverlayPoint {
  timeMs: number;
  value: string;
}

/**
 * Chart indicator client (Phase 40C): the registry + engine-computed series from the Phase-40B
 * backtest-service endpoint. The frontend NEVER computes indicator math (S7) — every overlay value
 * comes from here.
 */
@Injectable({ providedIn: 'root' })
export class ChartIndicatorsService {
  private readonly http = inject(HttpClient);

  registry(): Promise<IndicatorMeta[]> {
    return firstValueFrom(this.http.get<{ items: IndicatorMeta[] }>('/api/v1/indicators')).then(
      (r) => r.items ?? [],
    );
  }

  series(
    id: string,
    symbol: string,
    interval: string,
    fromMs: number,
    toMs: number,
    params: Record<string, unknown>,
  ): Promise<{ name: string; points: OverlayPoint[] }[]> {
    const httpParams = new HttpParams()
      .set('symbol', symbol)
      .set('interval', interval)
      .set('from', new Date(fromMs).toISOString())
      .set('to', new Date(toMs).toISOString())
      .set('params', JSON.stringify(params));
    return firstValueFrom(
      this.http.get<{ series: { name: string; points: { time: string; value: string }[] }[] }>(
        `/api/v1/indicators/${id}/series`,
        { params: httpParams },
      ),
    ).then((r) =>
      (r.series ?? []).map((s) => ({
        name: s.name,
        points: s.points.map((p) => ({ timeMs: Date.parse(p.time), value: p.value })),
      })),
    );
  }
}
