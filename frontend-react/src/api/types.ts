import type { OiInterpretation } from '../core/oiInterpretation.ts';

// OI wire types (ported from frontend-ui oi-analytics.store.ts). BigDecimal fields are JSON STRINGS
// at runtime (Jackson) even though the generated .d.ts types them as `number` — keep them `string`
// and never parseFloat. `long` OI/count fields are numbers. Anchor-scoped subset; more added per wave.

/** GET /api/v1/market/options/oi-stats — bare object; 422 DATA_GAP when no snapshot. */
export interface OiStats {
  pcr: string | null;
  maxPain: string | null;
  ceOi: number;
  peOi: number;
  asOf: string;
}

/** One active strike from GET /api/v1/market/options/active-strikes. */
export interface StrikeView {
  strike: string;
  ceOi: number;
  peOi: number;
}

/** One Active Strike Sentiment % point per bucket (active-strikes?buckets=N → the RIGHT chart). */
export interface SentimentPoint {
  bucket: string;
  sentimentPct: string | null;
}

/** One active-strike Call/Put OI point per bucket (active-strikes?buckets=N → the LEFT chart). */
export interface ActiveStrikeOiPoint {
  bucket: string;
  ceOi: number;
  peOi: number;
}

/**
 * One active-strike Call/Put IV + price point per bucket (active-strikes?buckets=N → the Active Strikes
 * IV chart). IV/price are BigDecimal STRINGS (never parseFloat); the chart coordinate is the only number
 * crossing. The single peak-OI strike's IVs (IV is unsummable — see the BE activeStrikeIvSeries).
 */
export interface ActiveStrikeIvPoint {
  bucket: string;
  ceIv: string | null;
  peIv: string | null;
  price: string | null;
}

/**
 * GET /api/v1/market/options/active-strikes — sentiment + top-N strikes; 422 DATA_GAP on empty. The two
 * `*Series` arrays ride the optional `buckets` param (omitted byte-identically when absent).
 */
export interface ActiveStrikes {
  sentimentPct: string | null;
  items: StrikeView[];
  sentimentSeries?: SentimentPoint[] | null;
  activeStrikeOiSeries?: ActiveStrikeOiPoint[] | null;
  activeStrikeIvSeries?: ActiveStrikeIvPoint[] | null;
  asOf: string;
}

/** One row of GET /api/v1/market/options/oi-analysis `{items}` (per bucket·strike·optionType). */
export interface OiStrikePoint {
  bucket: string;
  strike: string;
  optionType: 'CE' | 'PE';
  ltp: string | null;
  oi: number | null;
  oiChange: number | null;
  iv: string | null;
  spot: string | null;
}

/** One Connecting Dots interval row: 11 factor codes (0/1/2) + the 5-state composite trend (0..4). */
export interface ConnectingDotsRow {
  timeInterval: string;
  trend: number;
  dow: number;
  vix: number;
  volume: number;
  activeStrikeIv: number;
  activeStrikeOi: number;
  futOi: number;
  vwap: number;
  supertrend: number;
  rsi: number;
  futPrice: number;
  dailyTrend: number;
}

/** GET /api/v1/market/connecting-dots — the per-interval multi-factor sentiment matrix for an index. */
export interface ConnectingDots {
  underlying: string;
  interval: string;
  asOf: string;
  rows: ConnectingDotsRow[];
}

/** GET /api/v1/market/vix — the INDIA VIX quote (pinned index): LTP + day OHLC + change vs prev close. */
export interface VixQuote {
  ltp: string | null;
  dayHigh: string | null;
  dayLow: string | null;
  dayOpen: string | null;
  prevClose: string | null;
  change: string | null;
  changePct: string | null;
  asOf: string;
}

/** One interval's combined straddle candle (GET /straddle-chart): summed CE+PE OHLC + each leg's close. */
export interface StraddleCandle {
  time: string;
  open: string;
  high: string;
  low: string;
  close: string;
  ceClose: string;
  peClose: string;
  volume: number;
}

/** GET /api/v1/market/options/straddle-chart — combined CE+PE premium candles + the header strip. */
export interface StraddleChart {
  underlying: string;
  expiry: string;
  callStrike: string;
  putStrike: string;
  interval: string;
  underlyingLtp: string | null;
  underlyingDayOpen: string | null;
  asOf: string;
  items: StraddleCandle[];
}

/** GET /api/v1/market/options/oi-analysis/strike-series — one strike's CE+PE points per session bucket. */
export interface StrikeSeries {
  underlying: string;
  expiry: string;
  strike: string;
  interval: string;
  asOf: string;
  items: OiStrikePoint[];
}

/** One row of GET /api/v1/market/options/spurt `{items}` — per strike·side interval buildup. */
export interface SpurtRow {
  strike: string;
  optionType: 'CE' | 'PE';
  ltp: string | null;
  prevLtp: string | null;
  oi: number | null;
  oiChange: number;
  spurtPct: string | null;
  ltpChange: string | null;
  ltpChangePct: string | null;
  volume: number | null;
  interpretation: OiInterpretation;
}

/** GET /api/v1/market/options/spurt summary: spot-dir × total-OI-dir → the 4-state badge. */
export interface SpurtSummary {
  interpretation: OiInterpretation;
  spotDelta: string;
  oiChange: number;
}

/** GET /api/v1/market/options/spurt — per-strike buildup + the underlying rollup; 422 on no snapshot. */
export interface SpurtChain {
  items: SpurtRow[];
  summary: SpurtSummary | null;
  asOf: string | null;
}

/** A CE/PE leg's cell values in the folded strike grid. */
export interface LegCell {
  oi: number | null;
  oiChange: number | null;
  iv: string | null;
  ltp: string | null;
}

/** One folded chain row: CE + PE for a strike (oipulse mirrored grid). */
export interface OiChainRow {
  strike: string;
  ce: LegCell | null;
  pe: LegCell | null;
  spot: string | null;
}

// ── /chain-table — the faithful Options Chain feed (§20.7): live black76 greeks + interval deltas.

/** Interval deltas overlaid on a live leg (null when no prior snapshot bucket for this strike·side). */
export interface LegDeltas {
  oiChange: number | null;
  oiChangePct: string | null;
  ltpChange: string | null;
  ltpChangePct: string | null;
  interpretation: OiInterpretation;
}

/** A full live chain leg — IV + all 5 greeks computed server-side in black76 (decimal strings). */
export interface ChainLeg {
  tradingsymbol: string;
  ltp: string | null;
  bid: string | null;
  ask: string | null;
  volume: number | null;
  oi: number | null;
  iv: string | null;
  delta: string | null;
  gamma: string | null;
  theta: string | null;
  vega: string | null;
  rho: string | null;
  ivReason: string;
  priceSource: string | null;
}

/** A live leg plus its interval deltas (deltas null until a snapshot pair has accrued). */
export interface ChainTableLeg {
  leg: ChainLeg;
  deltas: LegDeltas | null;
}

/** One faithful-chain row: CE | strike | PE, each leg enriched with deltas. */
export interface ChainTableRow {
  strike: string;
  ce: ChainTableLeg | null;
  pe: ChainTableLeg | null;
}

/** GET /api/v1/market/options/chain-table — live chain header + enriched rows + the delta interval. */
export interface ChainTable {
  underlying: string;
  expiry: string;
  spot: string | null;
  forward: string | null;
  forwardSource: string | null;
  riskFreeRate: string | null;
  pcr: string | null;
  stale: boolean;
  asOf: string;
  interval: string;
  rows: ChainTableRow[];
}

// ── Wave-2 depth pages (master plan §20.3). BigDecimal → string, long → number (see header note).

/** GET /api/v1/market/options/trending — one bucket: total/CE/PE OI + underlying spot + UP/DOWN/FLAT. */
export interface TrendPoint {
  bucket: string;
  totalOi: number;
  ceOi: number;
  peOi: number;
  spot: string | null;
  trend: 'UP' | 'DOWN' | 'FLAT';
  /** Summed CE/PE premium (option ltp) across the chain — the Trending OI - PA price-action columns. */
  ceLtp: string | null;
  peLtp: string | null;
}

/** GET /api/v1/market/options/trending — per-bucket OI trend series; 422 DATA_GAP on no snapshot. */
export interface TrendSeries {
  items: TrendPoint[];
  asOf: string | null;
}

/** One strike's straddle premium (GET /api/v1/market/options/premium). */
export interface PremiumRow {
  strike: string;
  straddle: string;
  ce: string;
  pe: string;
}

/** GET /api/v1/market/options/premium — per-strike straddle + the ATM straddle; 422 on no snapshot. */
export interface PremiumChain {
  items: PremiumRow[];
  atmStrike: string | null;
  atmStraddle: string | null;
  spot: string | null;
  asOf: string;
}

/** One contract of GET /api/v1/market/futures/spurt — interval buildup + day price%. */
export interface FutSpurt {
  tradingsymbol: string;
  ltp: string | null;
  prevClose: string | null;
  pricePct: string | null;
  oi: number;
  oiChange: number;
  spurtPct: string | null;
  interpretation: OiInterpretation;
}

/** GET /api/v1/market/futures/spurt — per-contract 4-state buildup; 422 on no snapshot. */
export interface FutSpurtChain {
  items: FutSpurt[];
  asOf: string | null;
}

/** One row of GET /api/v1/market/futures/movers gainers/losers (day OHLC drives the O=H/L flag). */
export interface MoverRow {
  tradingsymbol: string;
  ltp: string | null;
  pricePct: string | null;
  oiPct: string | null;
  dayOpen: string | null;
  dayHigh: string | null;
  dayLow: string | null;
  interpretation: OiInterpretation;
}

/** GET /api/v1/market/futures/movers — gainers/losers by day price%; 422 on no snapshot. */
export interface Movers {
  gainers: MoverRow[];
  losers: MoverRow[];
  asOf: string | null;
}

/** One row of GET /api/v1/market/futures/eod `{items}` — per-contract per-IST-day OHLC + OI rollup. */
export interface FutEodRow {
  tradingsymbol: string;
  tradeDate: string;
  open: string | null;
  high: string | null;
  low: string | null;
  close: string | null;
  oiClose: number;
  oiChange: number;
  volume: number;
}

/**
 * One raw per-bucket point of GET /api/v1/market/futures/oi-analysis-series `{items}` (mirrors the BE
 * FutPoint). BigDecimal legs are STRINGS (never parseFloat); `oi`/`oiChange`/`volume` are longs. The
 * Futures OI Analysis page folds these into the per-interval table (cum ΔOI / Δltp / level break / 4-state).
 */
export interface FutSeriesPoint {
  bucket: string;
  tradingsymbol: string;
  ltp: string | null;
  oi: number | null;
  oiChange: number | null;
  dayOpen: string | null;
  dayHigh: string | null;
  dayLow: string | null;
  prevClose: string | null;
  volume: number | null;
  expiry: string | null;
}

/**
 * One interval's candle of GET /api/v1/market/futures/oi-chart `{items}` — real per-bucket OHLC (from
 * the contract's 1m bars) + the contract's OI at that bucket (null when no OI sample). OHLC are decimal
 * STRINGS (never parseFloat — only the chart coordinate fold crosses to Number); oi/oiChange are longs.
 */
export interface FutOiCandle {
  time: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: number;
  oi: number | null;
}

/** GET /api/v1/market/futures/oi-chart — one contract's candle+OI series; 422 when no FUT contract. */
export interface FutOiChart {
  underlying: string;
  tradingsymbol: string;
  expiry: string;
  interval: string;
  asOf: string;
  items: FutOiCandle[];
}

/**
 * One OHLCV bar of GET /api/v1/market/candles `{items}` (CandlesController.Candle) — the cache-first
 * per-symbol candle read. OHLC are decimal STRINGS (only the chart-coordinate fold crosses to Number);
 * `bucket` is the bar START as an IST-offset ISO datetime ("2026-06-20T09:15:00+05:30"); `volume` is a
 * primitive long (never null), `oi` is null for non-derivative symbols (e.g. an index).
 */
export interface MarketCandle {
  exchange: string;
  tradingsymbol: string;
  interval: string;
  bucket: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: number;
  oi: number | null;
  source: string;
}

/**
 * One interval's leg candle of GET /api/v1/market/options/options-chart — real per-bucket OPTION-PREMIUM
 * OHLC (from the leg's 1m bars) + the leg's OI + IV at that bucket (null when no snapshot sample). OHLC/iv
 * are decimal STRINGS (only the chart-coordinate fold crosses to Number); oi/volume are longs.
 */
export interface OptCandle {
  time: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: number;
  oi: number | null;
  iv: string | null;
}

/** GET /api/v1/market/options/options-chart — one strike's CE + PE premium candle+OI/IV series (one fetch). */
export interface OptOiChart {
  underlying: string;
  expiry: string;
  strike: string;
  ceTradingsymbol: string;
  peTradingsymbol: string;
  interval: string;
  underlyingLtp: string | null;
  underlyingDayOpen: string | null;
  asOf: string;
  ce: OptCandle[];
  pe: OptCandle[];
}

/** One row of GET /api/v1/market/fii-dii/cash `{items}` — FII or DII cash buy/sell/net (₹ cr strings). */
export interface FiiDiiRow {
  tradeDate: string;
  category: string;
  buyValue: string | null;
  sellValue: string | null;
  netValue: string | null;
}

/** One row of GET /api/v1/market/fii-dii/participant-oi `{items}` — a participant's long/short contracts. */
export interface ParticipantOiRow {
  tradeDate: string;
  clientType: string;
  futureIndexLong: number;
  futureIndexShort: number;
  futureStockLong: number;
  futureStockShort: number;
  optionIndexCallLong: number;
  optionIndexPutLong: number;
  optionIndexCallShort: number;
  optionIndexPutShort: number;
  optionStockCallLong: number;
  optionStockPutLong: number;
  optionStockCallShort: number;
  optionStockPutShort: number;
  totalLongContracts: number;
  totalShortContracts: number;
}

/** One row of GET /api/v1/market/fii-dii/long-short `{items}` — FII index-futures long/short + ratio. */
export interface LongShortRow {
  tradeDate: string;
  fiiLong: number;
  fiiShort: number;
  ratio: string | null;
}

/**
 * One bank's cell in the Banks Analysis matrix — cumulative-from-day-open LTP% / OI% (decimal STRINGS,
 * never parseFloat) + the per-interval 4-state OI interpretation. Null cell = the bank had no point at
 * that interval.
 */
export interface BankAnalysisCell {
  bank: string;
  ltpPct: string | null;
  oiPct: string | null;
  interpretation: OiInterpretation | null;
}

/** One Banks Analysis matrix row: a snapshot bucket + a cell per bank in the configured column order. */
export interface BankAnalysisRow {
  bucket: string;
  cells: (BankAnalysisCell | null)[];
}

/** GET /api/v1/market/futures/banks-analysis — the time × bank OI matrix; 422 until ≥1 bucket accrues. */
export interface BanksAnalysis {
  banks: string[];
  interval: string;
  asOf: string;
  rows: BankAnalysisRow[];
}

/** One selected leg's OI line (Multiple OI Chart): one OI value per bucket (null where no snapshot). */
export interface MultiOiLeg {
  leg: string;
  points: { bucket: string; oi: number | null }[];
}

/** One bucket of the underlying price (spot) reference line — BigDecimal STRING (never parseFloat). */
export interface MultiOiSpotPoint {
  bucket: string;
  spot: string | null;
}

/** GET /api/v1/market/options/multiple-oi — N selected legs' OI lines + the underlying price line. */
export interface MultiOi {
  items: MultiOiLeg[];
  spot: MultiOiSpotPoint[];
  underlying: string;
  expiry: string;
  interval: string;
  asOf: string;
}
