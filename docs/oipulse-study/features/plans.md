# Oi Pulse Plans — `/app/plans`

**Purpose:** subscription pricing page. Documents the product's **feature taxonomy by tier**
(useful as a feature checklist for replication). Sub-tabs: `Plans | Pricing`.

## Layout — 3 pricing cards (row)
Each card: faded-red semicircle header containing the red waveform logo, big red **price /Month**,
`(Excluding GST)`, a dark **"For N Month(s)"** badge, the tier name (red), a centered feature list,
and a red **Subscribe** button. A diagonal **corner ribbon** marks 1Cliq inclusion.

| Card | Price | Term | Ribbon |
|---|---|---|---|
| **Go Pro** | ₹1599 /Month | For 1 Month | red "1 CLIQ PLAN NOT INCLUDED" |
| **Go Annual** | ₹1167 /Month | For 12 Months | red "1 CLIQ PLAN NOT INCLUDED" |
| **Go Annual Combo** | ₹2167 /Month | For 12 Months | **green** "1 CLIQ PLAN INCLUDED" |

### Go Pro features
TradingView Advance Charts · Multiframe Charts · Connecting Dots · Trending OI · Big OI Movement ·
Active Strikes OI & IV · Open & High Strategy · FII & DII Activity · Market Movers ·
EOD OI Analyzer & Buzz · OI Analysis & Chart · Options Chain & Chart · OI Spurt & Statistics ·
Options Premium · Straddle & Strangle Chart · Calendar Spread & OI Expiry Strategy · Email Support Only

### Go Annual features
Everything in Go Pro · OSPL Signal · Qwik Scalp Signal · Strategy Builder · Intraday Strategy Simulator ·
Save Strategy Builder Strategy · Complete Oi Pulse Features for 1 Year & Save 4,989/- Every Year · Email Support Only

### Go Annual Combo features
Everything in Go Annual · 1Cliq Annual Plan will be Complimentary · Email Support Only

## Visual cues
- Price + tier name + Subscribe in brand red `#c42b1e`.
- Header semicircle = soft red gradient (`#e9b...` pink wash) behind the logo.
- Ribbons: red = excluded, green (`--success`) = included.
- Feature rows: light text, centered, thin separators.

## Data source
`POST /api/user-tool-plan/getavailableplans` → plan list (price, term, feature lines, 1Cliq flag).

## Replication notes (→ ArthaYantra)
- Single-owner app — likely **not needed** (no monetization). Keep this file as the canonical
  feature list / parity checklist across the OI suite.

## Screenshot
ss_0850mgnct.
