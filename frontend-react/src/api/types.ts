import type { OiInterpretation } from '../core/oiInterpretation.ts';

// OI wire types (ported from frontend-ui oi-analytics.store.ts). BigDecimal fields are JSON STRINGS
// at runtime (Jackson) even though the generated .d.ts types them as `number` — keep them `string`
// and never parseFloat. `long` OI/count fields are numbers. Anchor-scoped subset; more added per wave.

/**
 * The standard data-freshness / source-quality envelope (app-platform audit §11.17) nested on the
 * high-value analytics reads, read by the shared {@code FreshnessBadge}. Absent (`undefined`) on
 * endpoints not yet covered by Phase-3 slice A. `historyStart` is deliberately null server-side for
 * now (a MIN(ts) probe is not run on the hot read path).
 */
export interface DataFreshness {
  /** ISO-8601 timestamp of the newest datapoint (null when the read is empty). */
  asOf: string | null;
  /** Concrete producer label: `capture` | `candle-derived` | `bhavcopy` | `nse-eod`. */
  source: string | null;
  /** Earliest available session (null in this slice — depth not yet surfaced). */
  historyStart: string | null;
  /** `max(0, now − asOf)` in seconds (null when asOf is null). */
  staleSeconds: number | null;
  /** The response carries a fully-captured latest datapoint. */
  complete: boolean;
  /** Data KIND (independent of freshness): `live` | `derived` | `eod` | `static`. */
  provenance: string | null;
}

/** GET /api/v1/market/options/oi-stats — bare object; 422 DATA_GAP when no snapshot. */
export interface OiStats {
  pcr: string | null;
  maxPain: string | null;
  ceOi: number;
  peOi: number;
  asOf: string;
  freshness?: DataFreshness;
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
  /** Per-side SPOT-solved IVs (display path) — shows the call-vs-put IV split the PCP-forward solve hides. */
  activeStrikeSideIvSeries?: ActiveStrikeIvPoint[] | null;
  asOf: string;
  freshness?: DataFreshness;
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

/** One interval's calendar-spread candle (GET /calendar-spread): (near − far) OHLC + each leg's close. */
export interface SpreadCandle {
  time: string;
  open: string;
  high: string;
  low: string;
  close: string;
  nearClose: string;
  farClose: string;
  volume: number;
}

/** GET /api/v1/market/options/calendar-spread — the (near − far) premium-differential candles + header. */
export interface CalendarSpreadChart {
  underlying: string;
  strike: string;
  optionType: string;
  nearExpiry: string;
  farExpiry: string;
  interval: string;
  underlyingLtp: string | null;
  underlyingDayOpen: string | null;
  asOf: string;
  items: SpreadCandle[];
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

/** One Big-OI session-log event (GET /api/v1/market/options/big-oi-log): a bucket's big ΔOI move. */
export interface BigOiLogEvent {
  time: string;
  spot: string | null;
  strike: string;
  optionType: 'CE' | 'PE';
  ltp: string | null;
  ltpChange: string | null;
  oi: number | null;
  oiChange: number;
  interpretation: OiInterpretation;
}

/** GET /api/v1/market/options/big-oi-log — chronological session event log, newest bucket first. */
export interface BigOiLog {
  items: BigOiLogEvent[];
  asOf: string | null;
}

/** One strike's interval OI move (oipulse Interval-wise OI bars) — `strike` = e.g. "57400 PE". */
export interface StrikeMove {
  strike: string;
  oiChange: number;
  interpretation: OiInterpretation;
}

/** GET /api/v1/market/options/interval-wise-oi — top OI gainer/loser strikes per 15m/60m/daily lookback. */
export interface IntervalWiseOi {
  gainers15: StrikeMove[];
  losers15: StrikeMove[];
  gainers60: StrikeMove[];
  losers60: StrikeMove[];
  gainersDaily: StrikeMove[];
  losersDaily: StrikeMove[];
  asOf: string | null;
}

/** One heatmap cell: column index `x` (bucket), row index `y` (strike), `value` = interval ΔOI. */
export interface OiHeatmapCell {
  x: number;
  y: number;
  value: number | null;
}

/** GET /api/v1/market/options/oi-heatmap — strike × time grid of interval ΔOI, split CE/PE. */
export interface OiHeatmap {
  buckets: string[];
  strikes: string[];
  ce: OiHeatmapCell[];
  pe: OiHeatmapCell[];
  maxAbs: number;
  asOf: string | null;
  freshness?: DataFreshness;
}

/**
 * One EOD daily row of GET /api/v1/market/options/oi-expiry (a strike's CE or PE table). Premium OHLC
 * legs are decimal STRINGS (never parseFloat); `oi`/`volume` are longs; the day-over-day % changes are
 * decimal strings or null (the first captured session has no prior). `interpretation` is the 4-state
 * enum (or null). `allDayHigh`/`allDayLow` flag the date whose High/Low was the window extreme.
 */
export interface OiExpiryEodDay {
  date: string;
  open: string | null;
  high: string | null;
  low: string | null;
  close: string | null;
  oi: number | null;
  volume: number | null;
  changeInClosePct: string | null;
  changeInOiPct: string | null;
  interpretation: OiInterpretation | null;
  allDayHigh: boolean;
  allDayLow: boolean;
}

/** One strike's CE + PE daily tables (oipulse OI Expiry Strategy / "Options EOD OI Analysis"). */
export interface OiExpiryStrike {
  strike: string;
  ce: OiExpiryEodDay[];
  pe: OiExpiryEodDay[];
}

/**
 * One (strike, leg) Open=High/Low scan row of GET /api/v1/market/options/open-high-strategy (oipulse
 * "Open & High Strategy", Siva #2). Premium OHLC legs are decimal STRINGS; `latestOi` is a long;
 * `probability`/`fallPctFromHigh` are decimal strings or null (no prior session / absent high). The
 * latest captured session yields `ohMark`/`olMark`/`triggered`; the prior sessions yield the
 * `probability` (hit-rate over `sessions - 1` prior days).
 */
export interface OpenHighStrategyLeg {
  optionType: string;
  /** The GRADED (pattern) session — the day BEFORE the viewed one on the live read. */
  latestDate: string | null;
  latestOpen: string | null;
  latestHigh: string | null;
  latestLow: string | null;
  latestClose: string | null;
  latestOi: number | null;
  ohMark: boolean;
  olMark: boolean;
  /** Live read: the viewed day BROKE the graded day's high (CE) / low (PE). */
  triggered: boolean;
  fallPctFromHigh: string | null;
  sessions: number;
  hits: number;
  probability: string | null;
  /** Live enrichment (audit §10.2-8): the viewed day's premium extremes + LTP + break time. */
  newDayHigh: string | null;
  newDayLow: string | null;
  liveLtp: string | null;
  triggeredTime: string | null;
}

/** One strike's CE + PE Open=High/Low scan (each leg null when that side has no captured session). */
export interface OpenHighStrategyStrike {
  strike: string;
  ce: OpenHighStrategyLeg | null;
  pe: OpenHighStrategyLeg | null;
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
  freshness?: DataFreshness;
}

/**
 * One raw row of GET /api/v1/market/options/chain/history — a stored snapshot leg (chain time-travel).
 * Money/greeks ride the wire as decimal STRINGS (BigDecimal-as-string); oi/volume are longs. Unlike the
 * live /chain-table feed there are NO computed interval deltas — the raw capture carries only oi_change.
 */
export interface ChainHistoryRow {
  ts: string;
  strike: string;
  optionType: 'CE' | 'PE';
  tradingsymbol: string;
  ltp: string | null;
  bid: string | null;
  ask: string | null;
  volume: number | null;
  oi: number | null;
  oiChange: number | null;
  spotPrice: string | null;
  iv: string | null;
  delta: string | null;
  gamma: string | null;
  theta: string | null;
  vega: string | null;
  rho: string | null;
  ivReason: string | null;
  priceSource: string | null;
}

/** GET /api/v1/market/options/chain/history — the stored snapshot nearest `at` (404 pre-capture). */
export interface ChainHistory {
  underlying: string;
  expiry: string;
  ts: string;
  rows: ChainHistoryRow[];
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
  freshness?: DataFreshness;
}

/**
 * GET /api/v1/market/options/pcr-series — intraday PCR-vs-price (the OI-Statistics chart), source-
 * aware (native fold or Upstox full-chain). `pcr`/`spot` are decimal strings ("HH:mm" IST `time`).
 */
export interface PcrSeriesPoint {
  time: string;
  pcr: string | null;
  spot: string | null;
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

/** One bucket of GET /api/v1/market/options/premium-series — the ATM straddle over the session. */
export interface PremiumSeriesPoint {
  bucket: string;
  atmStrike: string | null;
  atmStraddle: string | null;
  spot: string | null;
}

/** GET /api/v1/market/options/premium-series — the ATM-straddle decay curve; 422 on no snapshot. */
export interface PremiumSeries {
  items: PremiumSeriesPoint[];
  asOf: string | null;
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
  freshness?: DataFreshness;
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
  freshness?: DataFreshness;
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

/** One row of GET /api/v1/market/holidays `{items}` — an NSE trading holiday with its weekday + name.
 * The Passed/Coming validity is derived client-side vs today (not carried on the wire). */
export interface HolidayRow {
  date: string;
  day: string;
  description: string;
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

/**
 * One row of GET /api/v1/market/fii-dii/derivative-stats `{items}` — a FII F&O segment's net activity
 * for a trade date (₹ Crore). `segment` ∈ INDEX_FUTURES | INDEX_OPTIONS | STOCK_FUTURES | STOCK_OPTIONS.
 */
export interface FiiDerivativeRow {
  tradeDate: string;
  segment: string;
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

/** Market breadth summary for one trade date (advance/decline counts + average delivery%). */
export interface BreadthSummary {
  tradeDate: string;
  advances: number;
  declines: number;
  unchanged: number;
  total: number;
  avgDeliveryPct: string | null;
}

/** One delivery-leaders row — BigDecimal fields are decimal STRINGS (never parseFloat for display). */
export interface BreadthDeliveryRow {
  symbol: string;
  deliveryPct: string | null;
  close: string | null;
  pctChange: string | null;
}

/** GET /api/v1/market/breadth?date= — advance/decline + delivery-% leaders from the EQ bhavcopy. */
export interface Breadth {
  summary: BreadthSummary;
  topDelivery: BreadthDeliveryRow[];
  asOf: string;
  freshness?: DataFreshness;
}

/** One materialized breadth day (audit §3.3 Phase-3): A/D counts + above-MA counts. */
export interface BreadthDay {
  tradeDate: string;
  advances: number;
  declines: number;
  unchanged: number;
  total: number;
  avgDeliveryPct: string | null;
  aboveSma50: number | null;
  sma50Universe: number | null;
  aboveSma200: number | null;
  sma200Universe: number | null;
}

/** GET /api/v1/market/breadth/history — the materialized daily-breadth series (zero-fold read). */
export interface BreadthHistory {
  items: BreadthDay[];
  from: string | null;
  to: string | null;
}

/** One Futures OI Buzz constituent tile — BigDecimal fields are decimal STRINGS; oi/oiChange numbers.
 * oiChange (vs prev-session close OI) + its 4-state interpretation are null until the prev-OI cache warms. */
export interface OiBuzzTile {
  symbol: string;
  changePct: string | null;
  ltp: string | null;
  open: string | null;
  high: string | null;
  low: string | null;
  oi: number | null;
  oiChange: number | null;
  interpretation: OiInterpretation | null;
}

/** GET /api/v1/market/futures/oi-buzz-heatmap?name= — constituent %change tiles + advance/decline. */
export interface OiBuzzHeatmap {
  index: string;
  advance: number;
  decline: number;
  tiles: OiBuzzTile[];
  asOf: string | null;
}

/** One day of a stock's delivery series — BigDecimal fields are decimal STRINGS; qty fields numbers. */
export interface DeliveryDay {
  date: string;
  open: string | null;
  high: string | null;
  low: string | null;
  close: string | null;
  ltpChangePct: string | null;
  deliveryPct: string | null;
  dayRange: string | null;
  dayRangePct: string | null;
  deliveryQty: number | null;
  totalTradedQty: number | null;
}

/** GET /api/v1/market/equity/delivery?symbol=&days= — a stock's daily delivery series, newest first. */
export interface EquityDelivery {
  symbol: string;
  days: number;
  items: DeliveryDay[];
}

/** One Equity Returns screener row — LTP + window returns are decimal STRINGS (null = no base close). */
export interface EquityReturnRow {
  symbol: string;
  industry: string | null;
  ltp: string | null;
  r1d: string | null;
  r1w: string | null;
  r1m: string | null;
  r6m: string | null;
  r1y: string | null;
}

/** GET /api/v1/market/equity/returns — the multi-timeframe returns screener over the EQ universe. */
export interface EquityReturns {
  asOf: string | null;
  items: EquityReturnRow[];
  freshness?: DataFreshness;
}

/** One stock's latest-session change + sector — BigDecimal fields are decimal STRINGS. */
export interface SectorStockChange {
  symbol: string;
  sector: string;
  changePct: string | null;
  close: string | null;
  prevClose: string | null;
}

/** GET /api/v1/market/equity/sector-heatmap?name= — an index's constituents (group by sector client-side). */
export interface SectorHeatmap {
  index: string;
  tiles: SectorStockChange[];
  asOf: string | null;
  freshness?: DataFreshness;
}

/** Per-sector roll-up: avg change + advancer/decliner split. */
export interface SectorAgg {
  sector: string;
  avgChangePct: string | null;
  positive: number;
  negative: number;
  total: number;
}

/** One live sector-index card (niftyindices feed) — values are the feed's decimal strings. */
export interface SectorIndexCard {
  name: string;
  last: string | null;
  changePct: string | null;
  open: string | null;
  high: string | null;
  low: string | null;
  prevClose: string | null;
  asOf: string | null;
}

/** GET /api/v1/market/equity/sector-stats — live sector-index cards + per-sector cards + factor table. */
export interface SectorStats {
  asOf: string | null;
  sectorIndices: SectorIndexCard[];
  sectors: SectorAgg[];
  stocks: SectorStockChange[];
  freshness?: DataFreshness;
}

/** One constituent's index contribution (weight × %change) — decimal STRINGS. {@code points} =
 * contribution × index level / 100 (oipulse's index-point form; null when the index close is absent). */
export interface ContribRow {
  rank: number;
  symbol: string;
  contribution: string | null;
  changePct: string | null;
  close: string | null;
  points: string | null;
}

/** One news article (Upstox /v2/news); publishedTime is epoch-millis. */
export interface NewsItem {
  heading: string | null;
  summary: string | null;
  thumbnail: string | null;
  articleLink: string | null;
  publishedTime: number | null;
}

/** GET /api/v1/market/equity/news?symbol= — a stock's recent news; available=false when source off. */
export interface EquityNews {
  symbol: string;
  available: boolean;
  items: NewsItem[];
}

/** One Open=High / Open=Low setup — decimal STRINGS; farPct = (ltp − level) / level × 100. */
export interface OpenHighSetup {
  symbol: string;
  dayOpen: string | null;
  dayHigh: string | null;
  dayLow: string | null;
  ltp: string | null;
  farPct: string | null;
}

/** GET /api/v1/market/equity/open-high-low?name= — constituents that opened at their day high / low. */
export interface OpenHighLow {
  index: string;
  openHigh: OpenHighSetup[];
  openLow: OpenHighSetup[];
  asOf: string | null;
}

/** GET /api/v1/market/equity/index-contribution?name= — advances/declines by weighted contribution. */
export interface IndexContribution {
  index: string;
  indexChangePct: string | null;
  advanceTotal: string | null;
  declineTotal: string | null;
  indexLevel: string | null;
  advancePoints: string | null;
  declinePoints: string | null;
  advances: ContribRow[];
  declines: ContribRow[];
  asOf: string | null;
  /** True when the intraday quote-based fold served (mode=live); false = the EOD bhavcopy read. */
  live: boolean;
}

/**
 * One exchange's live session phase of GET /api/v1/market/market-status `{items}` (Upstox market-status). The
 * `status` is the raw Upstox phase enum (PRE_OPEN_START / PRE_OPEN_END / NORMAL_OPEN / NORMAL_CLOSE /
 * CLOSING_START / CLOSING_END), or "UNKNOWN" when the exchange call failed; `preOpen` is server-derived
 * (the phase starts with "PRE_OPEN"). `asOf` is the Upstox last_updated as an ISO +05:30 instant (null
 * when unknown).
 */
export interface MarketStatus {
  exchange: string;
  status: string;
  preOpen: boolean;
  asOf: string | null;
}

/**
 * One index's pre-open snapshot of GET /api/v1/market/pre-open `indices` (Upstox index quote). Price /
 * change fields are decimal STRINGS (never parseFloat) and null when the live quote did not resolve (the
 * row still lists by name). During the 09:00–09:15 IST pre-open window these carry the indicative
 * pre-open price; otherwise the last / closing price. changePct is server-derived net_change / prevClose
 * * 100; asOf is the ISO +05:30 snapshot instant.
 */
export interface PreOpenIndex {
  name: string;
  key: string;
  ltp: string | null;
  prevClose: string | null;
  netChange: string | null;
  changePct: string | null;
  asOf: string;
}

/**
 * GET /api/v1/market/pre-open — the NSE session phase + the four index quotes (Nifty 50 / Bank / Fin /
 * Sensex). `phase` is the raw NSE phase enum (or "UNKNOWN"); `preOpen` mirrors the NSE pre-open flag.
 */
export interface PreOpenSnapshot {
  phase: string;
  preOpen: boolean;
  indices: PreOpenIndex[];
}

/**
 * One corporate announcement / exchange filing (GET /api/v1/market/equity/announcements, NSE source).
 * `date`/`time` are the IST announcement date (ISO) + clock (HH:mm:ss); `subject` is the category,
 * `detail` the full text, `fileLink` the attachment PDF URL (any of company/subject/detail/fileLink may
 * be null). Empty list when the NSE source is not live (mock) or the call fails.
 */
export interface Announcement {
  date: string;
  time: string;
  symbol: string;
  company: string | null;
  subject: string | null;
  detail: string | null;
  fileLink: string | null;
}

/**
 * One row of the Futures Pre-Open scan (GET /api/v1/market/futures/pre-open). Price / change fields are
 * decimal STRINGS (never parseFloat), null when the quote did not resolve (the row still lists by name).
 * `prevDayBreak` is the High/Low Break badge: "H" = pre-open price above the previous day's high, "L" =
 * below the previous day's low, null = inside the range. The client splits Advances / Declines by the
 * sign of `change`.
 */
export interface FuturesPreOpenRow {
  symbol: string;
  preOpenPrice: string | null;
  prevClose: string | null;
  change: string | null;
  changePct: string | null;
  prevDayBreak: 'H' | 'L' | null;
}

/**
 * GET /api/v1/market/futures/pre-open — the 09:00–09:08 pre-market scan: the NSE session phase + the
 * radar stock-future rows + the index rows, with the market (stock) advance/decline/unchanged counts.
 * Empty rows + phase "UNKNOWN" when Upstox analytics is not wired (the page renders its closed state).
 */
export interface FuturesPreOpen {
  phase: string;
  preOpen: boolean;
  asOf: string;
  advances: number;
  declines: number;
  unchanged: number;
  stocks: FuturesPreOpenRow[];
  indices: FuturesPreOpenRow[];
}

/**
 * One global index's live snapshot of GET /api/v1/market/world-indices (Upstox GLOBAL_INDEX /
 * GLOBAL_INDICATOR). Price/change fields are decimal STRINGS (the platform BigDecimal-as-string
 * convention) and are null when the live quote did not resolve (the row still lists from the master).
 * changePct is server-derived net_change / prevClose * 100; asOf is the ISO +05:30 snapshot instant.
 */
export interface WorldIndex {
  name: string;
  country: string | null;
  key: string;
  tradingSymbol: string | null;
  ltp: string | null;
  prevClose: string | null;
  netChange: string | null;
  changePct: string | null;
  open: string | null;
  high: string | null;
  low: string | null;
  asOf: string;
  latency: string | null;
}
