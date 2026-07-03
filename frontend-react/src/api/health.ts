// DataHealthCanary read surface (roadmap F4): the dashboard strip polls the market-data canary —
// tick/bar divergence per instrument + OI capture freshness. Session-gated server-side: a closed
// market always reads GREEN with marketOpen=false.

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface CanaryCheck {
  check: string;
  key: string;
  status: 'AMBER' | 'RED' | string;
  detail: string;
  since: string | null;
}

export interface DataHealth {
  status: 'GREEN' | 'AMBER' | 'RED' | string;
  marketOpen: boolean;
  asOf: string;
  tickedTokens: number;
  problems: CanaryCheck[];
}

const HEALTH_REFETCH_MS = 30_000;

export function useDataHealth() {
  return useQuery({
    queryKey: ['market', 'health', 'data'],
    queryFn: () => apiFetch<DataHealth>('/market/health/data'),
    refetchInterval: HEALTH_REFETCH_MS,
  });
}
