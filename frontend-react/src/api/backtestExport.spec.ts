import { beforeEach, describe, expect, it, vi } from 'vitest';

const downloadFile = vi.hoisted(() => vi.fn());
vi.mock('./client.ts', () => ({ downloadFile }));

import { exportEquity, exportFolds, exportTrades } from './backtestExport.ts';

describe('backtestExport', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('builds the trades, folds and equity download paths', async () => {
    await exportTrades('run-1', 'csv');
    await exportFolds('run-1', 'json');
    await exportEquity('run-1', 'csv');

    expect(downloadFile).toHaveBeenNthCalledWith(
      1,
      '/backtests/run-1/export/trades?format=csv',
    );
    expect(downloadFile).toHaveBeenNthCalledWith(
      2,
      '/backtests/run-1/export/folds?format=json',
    );
    expect(downloadFile).toHaveBeenNthCalledWith(
      3,
      '/backtests/run-1/export/equity?format=csv',
    );
  });
});
