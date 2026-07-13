import { downloadFile } from './client.ts';

export type ExportFormat = 'csv' | 'json';

export function exportTrades(runId: string, format: ExportFormat): Promise<void> {
  return downloadFile(`/backtests/${runId}/export/trades?format=${format}`);
}

export function exportFolds(runId: string, format: ExportFormat): Promise<void> {
  return downloadFile(`/backtests/${runId}/export/folds?format=${format}`);
}

export function exportEquity(runId: string, format: ExportFormat): Promise<void> {
  return downloadFile(`/backtests/${runId}/export/equity?format=${format}`);
}
