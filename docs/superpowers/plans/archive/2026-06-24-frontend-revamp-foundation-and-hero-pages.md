# Frontend Revamp — Detailed Design (Phase 1 + Hero Pages)

| | |
|---|---|
| **Title** | Frontend Revamp — Detailed Design (Phase 1 Foundation + Hero Pages) |
| **Date** | 2026-06-24 |
| **Status** | **DONE / ARCHIVED 2026-06-25.** All 5 phases merged (doc #158 · foundation #159 · shadcn bridge #160 · Button/QueryState/Sonner #161 · DataTable #162 · hero pages #163), then the design system **rolled out to 64/65 pages** (#166–#173) + the accent-token fix (#164) + chain polish (#165). Two new Upstox oipulse pages followed — **World Indices** (#174, live-fix #176) + **Pre-Open Market** (#175) — and the nav restructure **"All Menu" → per-section menu bar** (#177). Rebuilt + deployed to `:8080`. Decisions locked 2026-06-24 (Newsreader display face, signature lockup + radial mesh, motion on, mono numerics, compact-chain, breadcrumb dropped). Memory: [[frontend-revamp-state]]. |
| **Scope** | `frontend-react` design tokens + shadcn bridge + Button/Select/DataTable/QueryState component specs + three hero-page redesigns (Dashboard, Options Chain, FII/DII Capital Market). DESIGN ONLY — no source committed. |

---

## 0. Scope and locked toolkit

This document is the consolidated, internally-consistent design authority for **Phase 1 (Foundation)** of the ArthaYantra React frontend revamp plus **three representative hero-page redesigns** that exercise the full pattern (metric strip + chart card + dense table + loading/empty/error states). The frontend is React 19 + Vite 6 + Tailwind v4 **CSS-first** (no `tailwind.config.js`; `src/index.css` uses `@import 'tailwindcss'`, `@theme inline`, `@custom-variant dark`), Zustand, TanStack Query v5, react-router 7, echarts 5, lightweight-charts 5. There is no component library yet (hand-rolled atoms in `src/components/atoms/*`). Five swappable themes flow via `data-theme` on `<html>`; **all hex lives in the per-`[data-theme]` `--ay-*` blocks** and only there. Mobile target is the Samsung S24 Ultra (~480px). a11y is gated in CI by axe + Playwright role/name selectors. The box is loopback/offline — no third-party CDN.

The toolkit below was decided in the prior analysis pass and is **locked** — this design builds against exactly it (do not re-litigate):

- **Fonts** — self-host via `@fontsource-variable/inter` (`--font-sans`) + a tabular mono `@fontsource-variable/jetbrains-mono` (`--font-mono`). Never Google CDN.
- **Scale tokens** added to `src/index.css` via `@theme inline`: type ramp, spacing rhythm, `--radius-*`, `--ay-shadow-1..3` (tuned per dark theme), motion (`--ease-*`, `--duration-*`), focus-ring token. `--ay-*` stays the single source of hex.
- **Icons** — `lucide-react` (currentColor-themed). Replace all Unicode glyphs. Icon-only buttons keep an accessible name.
- **shadcn/ui** (Tailwind-v4 canary CLI, copy-in source, MIT) for **controls/overlays/skeleton only**: Button (base), Select, Dialog, Sheet, DropdownMenu, Tooltip, Tabs, Command, Skeleton, Sonner. Wired behind a one-time `--ay-*` alias bridge so all 5 themes flow free. Do **not** use shadcn Table; do **not** route charts through shadcn.
- **Dense tables** — TanStack Table v8 (headless) **under** the existing `src/components/DataTable.tsx` — keep its public API + test hooks + mobile-card composite; add multi-sort, sticky-left pinned strike column, density toggle, column visibility, faceted filter, zebra, row-hover, lucide chevrons.
- **Motion** — `motion/react` (`LazyMotion` + `m`), every animation `<200ms` and `prefers-reduced-motion`-gated.
- **Charts** — keep echarts + lightweight-charts (do not swap); polish only. `visx` allowed **only** for bespoke oipulse visuals echarts cannot do cleanly.

---

## 1. Foundation tokens — `src/index.css` scale backbone

All additions **extend** the existing `src/index.css` — they do not duplicate the `--ay-*` palette (per-`[data-theme]` blocks) or fight the existing `@theme inline` block. The single source of hex stays the per-theme blocks; everything new is either a theme-invariant scale token or a per-theme **shadow/focus recipe** (the one tier that must vary by theme).

Two structural facts the additions respect:

- The existing `@theme inline` block already declares `--font-sans` and the `--color-*` aliases. New scale tokens **merge into that same block** — you cannot have two competing `@theme inline` blocks. §1.1 shows the merged block in full as a paste-ready replacement of the existing colour-alias block.
- `--font-sans` currently names `Inter` but never self-hosts it, so it silently falls back to Segoe UI. Step 0 self-hosts Inter and adds `--font-mono`.

### Step 0 — Self-host fonts (prerequisite, not CSS)

```
npm i @fontsource-variable/inter @fontsource-variable/jetbrains-mono
```

In `src/main.tsx`, **above** `import './index.css'` (so the `@font-face` rules land before the cascade uses them):

```ts
import '@fontsource-variable/inter';          // ships InterVariable, axes wght 100–900
import '@fontsource-variable/jetbrains-mono'; // ships JetBrains Mono Variable
import './index.css';
```

These packages bundle WOFF2 + `@font-face` locally (no Google CDN — satisfies the offline guardrail). They register the families `'Inter Variable'` and `'JetBrains Mono Variable'`, used below. Inter + JetBrains Mono are OFL.

### 1.1 — Replace the existing `@theme inline` colour-alias block with this superset

Keeps every existing colour alias verbatim and appends fonts, the type ramp, radius, motion, spacing, and tracking. `@theme inline` is correct here: these are static scale values that become Tailwind utilities (`text-h1`, `rounded-md`, `duration-fast`, `font-mono`), and `inline` keeps the `--color-*`/`--font-*` references as `var()` so runtime `data-theme` swaps still re-theme.

```css
@theme inline {
  /* ── colour aliases (UNCHANGED — existing values) ── */
  --color-surface-0: var(--ay-surface-0);
  --color-surface-1: var(--ay-surface-1);
  --color-surface-2: var(--ay-surface-2);
  --color-ay-border: var(--ay-border);
  --color-ay-text: var(--ay-text);
  --color-ay-muted: var(--ay-text-muted);
  --color-bull: var(--ay-bull);
  --color-bear: var(--ay-bear);
  --color-warn: var(--ay-warn);
  --color-accent: var(--ay-accent);

  /* ── fonts ── */
  --font-sans: 'Inter Variable', 'Segoe UI', system-ui, sans-serif;
  --font-mono: 'JetBrains Mono Variable', ui-monospace, 'Cascadia Mono', monospace;

  /* ── TYPOGRAPHY RAMP (1.20 ratio above body; shallow on purpose for a dense product) ── */
  --text-display: 1.75rem;     --text-display--line-height: 2.125rem;  /* 28/34 — page hero, rare */
  --text-h1: 1.375rem;         --text-h1--line-height: 1.75rem;        /* 22/28 — page title    */
  --text-h2: 1.125rem;         --text-h2--line-height: 1.5rem;         /* 18/24 — section       */
  --text-h3: 1rem;             --text-h3--line-height: 1.375rem;       /* 16/22 — card title    */
  --text-body: 0.875rem;       --text-body--line-height: 1.25rem;      /* 14/20 — default prose */
  --text-body-sm: 0.8125rem;   --text-body-sm--line-height: 1.125rem;  /* 13/18 — controls,help */
  --text-caption: 0.75rem;     --text-caption--line-height: 1rem;      /* 12/16 — labels, meta  */
  --text-dense: 0.6875rem;     --text-dense--line-height: 0.9375rem;   /* 11/15 — table cells   */

  --font-weight-regular: 400;
  --font-weight-medium: 500;
  --font-weight-semibold: 600;

  --tracking-tight: -0.011em;  /* display / h1 */
  --tracking-normal: 0;
  --tracking-wide: 0.02em;     /* caption labels, uppercased meta */

  /* ── RADIUS ── */
  --radius-sm: 0.25rem;   /* 4px — badges, pills, tags, table-cell tints   */
  --radius-md: 0.375rem;  /* 6px — controls (select/button/input), cards   */
  --radius-lg: 0.5rem;    /* 8px — dialogs, sheets, popovers, large panels */

  /* ── MOTION ── */
  --duration-fast: 120ms;
  --duration-base: 180ms;                                   /* both ≤200ms per guardrail */
  --ease-standard: cubic-bezier(0.2, 0, 0, 1);              /* enter/exit, hovers         */
  --ease-emphasized: cubic-bezier(0.3, 0, 0, 1);            /* overlays, larger movement  */

  /* ── SPACING (semantic only — Tailwind's 4px base covers the rest) ── */
  --space-page-x: 1rem;       /* 16px — phone/baseline page gutter  */
  --space-page-x-lg: 1.5rem;  /* 24px — ≥768px page gutter          */
  --space-section: 1.5rem;    /* 24px — gap between page sections   */
  --space-card: 0.75rem;      /* 12px — standard card padding       */
  --space-control: 0.5rem;    /* 8px  — gap inside a control row     */
}
```

> **CONFLICT RESOLVED — `--radius-md` value.** The Foundation token spec sets `--radius-md: 0.375rem` directly. The shadcn bridge (§2) derives shadcn's radius scale from a single `--radius` literal as `--radius-md: calc(var(--radius) - 2px)`, which with `--radius: 0.375rem` yields `0.375rem - 2px ≈ 0.25rem` — a **different** value, and a name collision. **Decision:** the Foundation `@theme inline` above owns `--radius-sm/md/lg` as the app radius scale (4/6/8px). The shadcn bridge does **not** re-declare `--radius-sm/md/lg`; it sets only the literal `--radius: 0.375rem` so shadcn components match `rounded-md` controls, and lets shadcn's components reference `--radius` directly (shadcn's `rounded-md`/`rounded-lg` utilities resolve against the Foundation scale, which is visually equivalent). See §2 note "Radius reconciliation".

### 1.2 — Elevation + focus tokens (per-theme; default = dark recipe)

Shadows are **not** in `@theme inline` — they must differ per `[data-theme]`. They live as plain `--ay-*` custom properties so each theme block overrides them, exactly like the palette. `--ay-focus` derives from each theme's `--ay-accent` and is defined once per theme.

On dark themes "elevation" is **not** a drop shadow (invisible on near-black) — it is (1) a 1px top inset highlight, (2) a hairline inset ring for definition, (3) a soft ambient shadow underneath. Light theme gets a conventional soft drop shadow. High-contrast leans on a visible inset ring and a white-hot focus.

```css
/* default = dark recipe */
:root,
[data-theme='dark'] {
  --ay-shadow-1:
    inset 0 1px 0 0 rgb(255 255 255 / 0.04),
    0 1px 2px 0 rgb(0 0 0 / 0.4);
  --ay-shadow-2:
    inset 0 1px 0 0 rgb(255 255 255 / 0.05),
    inset 0 0 0 1px rgb(255 255 255 / 0.03),
    0 4px 12px -2px rgb(0 0 0 / 0.5);
  --ay-shadow-3:
    inset 0 1px 0 0 rgb(255 255 255 / 0.06),
    inset 0 0 0 1px rgb(255 255 255 / 0.04),
    0 12px 32px -6px rgb(0 0 0 / 0.6);
  --ay-focus: var(--ay-accent);
}

[data-theme='midnight-blue'] {
  --ay-shadow-1:
    inset 0 1px 0 0 rgb(96 165 250 / 0.06),
    0 1px 2px 0 rgb(0 0 0 / 0.45);
  --ay-shadow-2:
    inset 0 1px 0 0 rgb(96 165 250 / 0.07),
    inset 0 0 0 1px rgb(255 255 255 / 0.03),
    0 4px 14px -2px rgb(0 0 0 / 0.55);
  --ay-shadow-3:
    inset 0 1px 0 0 rgb(96 165 250 / 0.08),
    inset 0 0 0 1px rgb(255 255 255 / 0.04),
    0 14px 36px -6px rgb(0 0 0 / 0.65);
  --ay-focus: var(--ay-accent);
}

[data-theme='oipulse-red'] {
  --ay-shadow-1:
    inset 0 1px 0 0 rgb(226 87 76 / 0.06),
    0 1px 2px 0 rgb(0 0 0 / 0.45);
  --ay-shadow-2:
    inset 0 1px 0 0 rgb(226 87 76 / 0.07),
    inset 0 0 0 1px rgb(255 255 255 / 0.03),
    0 4px 12px -2px rgb(0 0 0 / 0.55);
  --ay-shadow-3:
    inset 0 1px 0 0 rgb(226 87 76 / 0.08),
    inset 0 0 0 1px rgb(255 255 255 / 0.04),
    0 12px 32px -6px rgb(0 0 0 / 0.65);
  --ay-focus: var(--ay-accent);
}

/* pure-black: shadows nearly invisible → lean on a visible inset ring + white-hot focus (≥7:1) */
[data-theme='high-contrast'] {
  --ay-shadow-1:
    inset 0 1px 0 0 rgb(255 255 255 / 0.10),
    inset 0 0 0 1px rgb(255 255 255 / 0.10);
  --ay-shadow-2:
    inset 0 1px 0 0 rgb(255 255 255 / 0.14),
    inset 0 0 0 1px rgb(255 255 255 / 0.16),
    0 6px 16px -2px rgb(0 0 0 / 0.8);
  --ay-shadow-3:
    inset 0 1px 0 0 rgb(255 255 255 / 0.18),
    inset 0 0 0 1px rgb(255 255 255 / 0.22),
    0 16px 40px -6px rgb(0 0 0 / 0.9);
  --ay-focus: #ffffff;
}

/* light: real soft low-spread drop shadows; no inset highlight */
[data-theme='light'] {
  --ay-shadow-1: 0 1px 2px 0 rgb(16 32 58 / 0.06), 0 1px 1px -1px rgb(16 32 58 / 0.10);
  --ay-shadow-2: 0 4px 12px -2px rgb(16 32 58 / 0.10), 0 2px 4px -2px rgb(16 32 58 / 0.08);
  --ay-shadow-3: 0 12px 28px -6px rgb(16 32 58 / 0.14), 0 6px 12px -4px rgb(16 32 58 / 0.10);
  --ay-focus: var(--ay-accent);
}
```

> High-contrast intentionally drops the ambient shadow on `--ay-shadow-1` (a 1px black-on-black shadow is dead pixels) and substitutes a real inset ring.

### 1.3 — Bridge `--ay-shadow-*` into Tailwind utilities

A **separate** `@theme` (non-inline) so consumers write `shadow-e1`/`shadow-e2`/`shadow-e3` instead of arbitrary `shadow-[...]`. The per-theme `--ay-shadow-*` already `var()`-resolve at runtime, so a plain alias is enough:

```css
@theme {
  --shadow-e1: var(--ay-shadow-1);
  --shadow-e2: var(--ay-shadow-2);
  --shadow-e3: var(--ay-shadow-3);
}
```

> **CONVENTION — shadow utility.** The component specs were drafted with two spellings: `shadow-[var(--ay-shadow-1)]` (arbitrary value) and `shadow-e1` (the utility above). **Decision: use `shadow-e1/e2/e3` everywhere** (cleaner, single source). All hero-page snippets below that show `shadow-[var(--ay-shadow-1)]` should be read as `shadow-e1`. Where a snippet predates this utility, it remains valid (arbitrary value resolves identically) but new code uses the utility.

### 1.4 — Base layer: ramp, mono numerics, focus, reduced-motion

Behaviour rules (not tokens), placed after the existing `html, body` block as plain CSS / `@utility` helpers. Tailwind v4 `@utility` lets one class set size + line-height + weight + tracking together.

```css
/* Default body text steps down from 16px to the product's 14px. */
html, body {
  font-size: var(--text-body);
  line-height: var(--text-body--line-height);
}

/* Semantic type utilities — one class = size + line-height + weight + tracking. */
@utility text-display {
  font-size: var(--text-display); line-height: var(--text-display--line-height);
  font-weight: var(--font-weight-semibold); letter-spacing: var(--tracking-tight);
}
@utility text-h1 {
  font-size: var(--text-h1); line-height: var(--text-h1--line-height);
  font-weight: var(--font-weight-semibold); letter-spacing: var(--tracking-tight);
}
@utility text-h2 {
  font-size: var(--text-h2); line-height: var(--text-h2--line-height);
  font-weight: var(--font-weight-semibold);
}
@utility text-h3 {
  font-size: var(--text-h3); line-height: var(--text-h3--line-height);
  font-weight: var(--font-weight-medium);
}
@utility text-body    { font-size: var(--text-body);    line-height: var(--text-body--line-height); }
@utility text-body-sm { font-size: var(--text-body-sm); line-height: var(--text-body-sm--line-height); }
@utility text-caption { font-size: var(--text-caption); line-height: var(--text-caption--line-height); }
@utility text-dense   { font-size: var(--text-dense);   line-height: var(--text-dense--line-height); }

/* Numeric / tabular cells: monospace + lining tabular figures so digits column-align. */
@utility nums {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum' 1;
}

/* Standard page container — replaces the bare p-4 on AppShell <main>. */
@utility page {
  width: 100%;
  max-width: 96rem;          /* 1536px — fits the 18-col chain, centred on huge displays */
  margin-inline: auto;
  padding-inline: var(--space-page-x);
}
@media (min-width: 768px) {
  @utility page { padding-inline: var(--space-page-x-lg); }
}

/* Standard card — replaces repeated `rounded border border-ay-border bg-surface-1 p-2`. */
@utility card {
  background: var(--ay-surface-1);
  border: 1px solid var(--ay-border);
  border-radius: var(--radius-md);
  padding: var(--space-card);
  box-shadow: var(--ay-shadow-1);
}

/* GLOBAL FOCUS — one recipe, all 5 themes; token-driven so high-contrast goes white.
   Replaces every `focus:outline-none` (a11y-fail) and ad-hoc `focus:ring-2 focus:ring-accent`.
   :focus-visible so mouse clicks don't ring; offset keeps the ring off dense borders. */
:where(a, button, [role='button'], input, select, textarea, summary, [tabindex]):focus-visible {
  outline: 2px solid var(--ay-focus);
  outline-offset: 2px;
  border-radius: var(--radius-sm);
}

/* Honour OS reduced-motion for everything token-driven (extends the existing .ay-pulse guard). */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
    scroll-behavior: auto !important;
  }
}
```

### 1.5 — Specifics pinned down

- **Page container.** `max-width: 96rem` (1536px), centred, responsive gutter `16px → 24px` at `≥768px`. `AppShell` `<main className="p-4">` → `<main className="page py-4">`.
- **Standard card.** `bg-surface-1` + 1px `--ay-border` + `--radius-md` + `--space-card` (12px) + `--ay-shadow-1`. Replaces the hand-rolled `rounded border border-ay-border bg-surface-1 p-2` strings.
- **Which `text-xs` stays vs changes:**
  - **Stays dense → `text-dense` (11px):** in-grid table cells (`DataTable` `<table>`, mobile card body, per-strike chain cells) — deliberately sub-12px.
  - **Becomes `text-caption` (12px):** labels/meta/help text (Metric label, page explainers, `IstClock`/`WsPill`/MOCK tag, pagination footer).
  - **Becomes `text-body-sm` (13px):** control text (`Select`, `GoButton`); prose intros go to `text-body`.
  - **Becomes `text-h1`:** real visible page titles (currently `ay-sr-only` or absent).
- **Numeric cells.** Everywhere the code writes bare `tabular-nums` should write **`nums`** to also pull in `--font-mono` so digit columns truly align (Inter's proportional `1` vs `8` advances otherwise stay uneven).
- **Glyphs to retire.** The Unicode sort caret `text-[0.6rem]` → `text-dense` + lucide `ChevronUp`/`ChevronDown`/`ChevronsUpDown`. Other `▲▼⇅↑↓●` glyphs are lucide-replacement targets in their owning components.

### 1.6 — Token-tier → usage → anti-pattern it replaces

| Tier | Tokens | Where used | Anti-pattern it replaces |
|---|---|---|---|
| **Font family** | `--font-sans` (Inter Variable, self-hosted), `--font-mono` (JetBrains Mono Variable) | body default; numeric cells via `nums` | Inter named but never self-hosted (falls back to Segoe UI); no mono token existed |
| **Type ramp** | `--text-display/h1/h2/h3/body/body-sm/caption/dense` + `@utility text-*` | titles, headers, prose, labels, dense cells | raw `text-xs`/`text-sm` scattered across ~205 occurrences with no semantic meaning |
| **Tabular numerics** | `nums` (`--font-mono` + `tabular-nums`) | every price / OI / Δ / PCR / clock cell | bare `tabular-nums` on a proportional font |
| **Spacing rhythm** | `--space-*`; `@utility page`, `@utility card` | gutter, section gaps, card padding, control rows | `<main className="p-4">`; per-page `mb-3`/`gap-2`/`p-2` guesses; no max-width container |
| **Radius** | `--radius-sm/md/lg` (4/6/8px) | sm=badges/pills/tints; md=controls+cards; lg=dialogs/sheets/popovers | bare `rounded`/`rounded-md` with no scale |
| **Elevation** | `--ay-shadow-1/2/3` per-theme + `shadow-e1/e2/e3` | card=e1; dropdown/popover/tooltip=e2; dialog/sheet=e3 | no shadow system — flat `border` everywhere |
| **Motion** | `--duration-fast/base` (120/180ms), `--ease-standard/emphasized` | hover/enter/exit, `motion/react` `LazyMotion` | one hardcoded `0.5s ease-out`; no shared timing/easing |
| **Focus** | `--ay-focus` (white in high-contrast) + global `:focus-visible` | every interactive element, all 5 themes | `focus:outline-none` with no replacement ring (axe fail) |
| **Reduced motion** | global `@media (prefers-reduced-motion)` blanket | every animation/transition app-wide | only `.ay-pulse` was guarded |

### 1.7 — Aesthetic direction (taste pass)

> Added from the frontend-design + impeccable **taste pass** (2026-06-24). Both skills are
> brand/marketing-biased — they explicitly ban Inter and demand bold/asymmetric/grainy
> aesthetics. That ethos is **rejected for the data planes** (scannability is the product;
> asymmetry/overlap/grain degrade dense grids and trip axe contrast) and their transferable
> discipline is kept. The product is a precision instrument: the look we buy is **expensive
> and exact**, not flashy. These four decisions extend §1 without touching a guardrail (all
> colour stays `--ay-*`; data planes stay flat/aligned/hairline; motion stays <200ms +
> reduced-motion-gated).

**1.7.1 — Display face (the one type-personality move).** Add a third font token, applied ONLY to word-titles ≥22px — never a grid, control, or sub-22px text:

```css
/* in the §1.1 @theme inline block */
--font-display: 'Newsreader', Georgia, 'Times New Roman', serif;
/* self-host: npm i @fontsource/newsreader; import in main.tsx above index.css */

/* in §1.4, scope it to the two largest steps ONLY */
@utility text-display { font-family: var(--font-display); /* …existing… */ }
@utility text-h1      { font-family: var(--font-display); /* …existing… */ }
```

Inter (`--font-sans`) stays the body/UI face; JetBrains Mono (`--font-mono`) stays every numeric cell via `nums`. The display face appears at most on the page `<h1>` and the rare `text-display` hero label — invisible to all ~40 grids. An editorial serif over a mono grid is the signature register (FT/Bloomberg), at the cost of one WOFF2. **OWNER DECISION (§7 Q10):** serif (Newsreader/Fraunces — editorial, higher taste) vs. grotesque (Space Grotesk — technical, safer). All OFL, all `@fontsource`, all offline-self-hostable. Big *numbers* stay mono (alignment + `tnum`); display wraps word-titles only.

**1.7.2 — The one signature (chrome only, never data).** Every page `<header>` shares ONE repeated lockup: display-face `<h1>` + a 2px `--ay-accent` left-rule (or hairline under the title) + the live-state dot. The single memorable gesture; zero contrast cost on data because it lives in the header band. Asymmetry / overlap / grid-breaking / grain are REJECTED on all data surfaces. Atmosphere/depth is permitted ONLY on zero-data canvases (login, standalone `EmptyCard`, Dashboard cold-load) as a token-built radial mesh — never texture, never behind a grid:

```css
background: radial-gradient(120% 80% at 50% 0%,
  color-mix(in srgb, var(--ay-accent) 6%, transparent), transparent 60%);
```

**1.7.3 — Accent & depth rhythm.** `--ay-accent` is the single loudest non-semantic colour and is RATIONED: **at most one accent highlight per viewport** (primary action OR the header rule) + focus rings. Hover washes stay `--ay-surface-2` (§2.1). Bull/bear/warn own all semantic colour. Depth = **ONE frame per nesting level**: a carded region (chart/table card) gets `shadow-e1` + its border; content INSIDE it (`QueryState` empty/error) is borderless/shadowless, differentiated by `bg-surface-2` + spacing — never a second frame (no card-in-card). Shadow tiers signal float (popover/dialog e2/e3 over a card's e1), never stacking. Add an `inset` prop to `EmptyCard`/error card to strip the frame when rendered inside an already-carded region.

**1.7.4 — Tabular hardening.** `nums` (§1.4) gains slashed zero so `0` ≠ `O` in OI cells:

```css
@utility nums { /* …existing… */ font-feature-settings: 'tnum' 1, 'zero' 1; }
```

> **High-contrast `#000000` is NOT an "always tint" violation** — it is an a11y theme whose job is ≥7:1; tinting defeats it (keep §1.2's inset-ring substitution). The default dark `#0c1017` and the other dark themes are already hue-tinted (cool/red/blue), and the per-theme shadow recipes already tint their inset highlights — that discipline is correct, leave it.

---

## 2. shadcn/ui → `--ay-*` alias bridge

shadcn components reference `--background/--primary/--ring/etc.` **We do not let shadcn own any hex:** every shadcn token aliases an `--ay-*` var. Because `--ay-*` already redefines per `[data-theme]`, these aliases re-resolve on theme switch automatically — declare once at `:root`, **no per-theme alias duplication** (one documented optional exception for light-theme contrast hardening).

The current shadcn canary token set (verified, 2026-06): colour pairs `background/foreground`, `card`, `popover`, `primary`, `secondary`, `muted`, `accent` (each with `-foreground`); single tokens **`destructive`** (no `-foreground` — removed; on-destructive text is baked white by the component), `border`, `input`, `ring`; `chart-1..5`; eight `sidebar-*`; `--radius`. Only Button/Select/Dialog/Sheet/DropdownMenu/Tooltip/Tabs/Command/Skeleton/Sonner are in use, so `chart-*`/`sidebar-*` are aliased for zero-warning completeness only.

> **CONFLICT RESOLVED — destructive on-text.** The Button-spec draft listed `--color-destructive-foreground: var(--ay-surface-0)`. The current shadcn canary **removed `--destructive-foreground`** and bakes on-destructive text white inside the component. **Decision: do NOT declare `--destructive-foreground`** (it would be a dead token). White-on-`--ay-bear` is ≥4.5:1 in every theme (light bear `#991b1b` darkened precisely so white passes). If a copied component ever references `text-destructive-foreground`, replace it with the baked default — do not reintroduce the token.

### 2.1 — The tricky decisions (explicit)

- **`primary` = `--ay-accent`; `primary-foreground` = `--ay-surface-0`.** Matches `GoButton` (`bg-accent text-surface-0`). AA-verified all 5 themes including light (`#0369a1` on `#f6f8fc` ≈ 4.9:1).
- **`accent` = `--ay-surface-2`; `accent-foreground` = `--ay-text`.** shadcn `accent` is the **hover/active wash** on menu/tab/command rows — **NOT** the brand colour. Pointing it at `--ay-accent` would flood hovers with saturated cyan. This is the single most common bridge mistake.
- **`destructive` = `--ay-bear`.** On-text baked white (no `-foreground`).
- **`muted-foreground` = `--ay-text-muted`** (Select placeholders, Command hints, Tooltip secondary).
- **`ring` = `--ay-accent`.** Matches the repo focus convention; stays consistent with the global `--ay-focus` recipe.
- **`border` and `input` both → `--ay-border`** (the repo uses one border token everywhere).
- **`card`/`popover` = `--ay-surface-1`; their `-foreground` = `--ay-text`.** All overlays land on surface-1.
- **`secondary` = `--ay-surface-2`; `secondary-foreground` = `--ay-text`** (low-emphasis fills, e.g. Dialog "Cancel"). Same pair as `accent` by design but kept separate so they can diverge later.
- **`--radius` = `0.375rem`** to match `rounded-md` controls (not shadcn's chunkier `0.625rem` default).

### 2.2 — Mapping table (paste into the PR description)

| shadcn token | → `--ay-*` source | rationale |
|---|---|---|
| `--background` | `--ay-surface-0` | app bg |
| `--foreground` | `--ay-text` | |
| `--card` / `--card-foreground` | `--ay-surface-1` / `--ay-text` | elevated surface |
| `--popover` / `--popover-foreground` | `--ay-surface-1` / `--ay-text` | overlay surface |
| `--primary` | `--ay-accent` | brand action |
| `--primary-foreground` | `--ay-surface-0` | on-accent; AA all 5 themes |
| `--secondary` / `--secondary-foreground` | `--ay-surface-2` / `--ay-text` | low-emphasis fill |
| `--muted` | `--ay-surface-2` | Skeleton base, disabled fills |
| `--muted-foreground` | `--ay-text-muted` | placeholders/hints |
| `--accent` / `--accent-foreground` | `--ay-surface-2` / `--ay-text` | **hover wash — NOT brand** |
| `--destructive` | `--ay-bear` | on-text baked white (no `-foreground`) |
| `--border` / `--input` | `--ay-border` | single border token |
| `--ring` | `--ay-accent` | focus ring |
| `--radius` | `0.375rem` (literal) | match `rounded-md` |
| `--chart-1..5` | accent / bull / bear / warn / text-muted | unused; aliased for completeness |
| `--sidebar*` (8) | surface-1 / text / accent / surface-0 / surface-2 / text / border / accent | unused; aliased to avoid stray defaults |

### 2.3 — Paste-ready CSS bridge block

Add **immediately AFTER the `@theme inline { … }` and BEFORE the `:root,[data-theme='dark']` palette** — between the Tailwind utility mapping and the per-theme palettes. Part (A) registers shadcn `--color-*` utilities; part (B) is the `:root` alias layer. Do **not** let the canary CLI append its own `:root{--background:oklch(...)}` / `.dark{…}` / `@theme inline` — delete those after init (see §2.4).

```css
/* ── shadcn/ui → --ay-* ALIAS BRIDGE ───────────────────────────────────────
   Every shadcn token aliases an --ay-* var. --ay-* redefines per [data-theme],
   so aliases re-resolve on theme switch automatically — declare ONCE at :root.
   No --destructive-foreground (removed; on-text baked white). --chart-*/--sidebar-*
   aliased for completeness only. */

/* (A) Register shadcn's utility names so bg-primary, text-muted-foreground, etc. compile. */
@theme inline {
  --color-background: var(--background);
  --color-foreground: var(--foreground);
  --color-card: var(--card);
  --color-card-foreground: var(--card-foreground);
  --color-popover: var(--popover);
  --color-popover-foreground: var(--popover-foreground);
  --color-primary: var(--primary);
  --color-primary-foreground: var(--primary-foreground);
  --color-secondary: var(--secondary);
  --color-secondary-foreground: var(--secondary-foreground);
  --color-muted: var(--muted);
  --color-muted-foreground: var(--muted-foreground);
  --color-accent: var(--accent);
  --color-accent-foreground: var(--accent-foreground);
  --color-destructive: var(--destructive);
  --color-border: var(--border);
  --color-input: var(--input);
  --color-ring: var(--ring);
  --color-chart-1: var(--chart-1);
  --color-chart-2: var(--chart-2);
  --color-chart-3: var(--chart-3);
  --color-chart-4: var(--chart-4);
  --color-chart-5: var(--chart-5);
  --color-sidebar: var(--sidebar);
  --color-sidebar-foreground: var(--sidebar-foreground);
  --color-sidebar-primary: var(--sidebar-primary);
  --color-sidebar-primary-foreground: var(--sidebar-primary-foreground);
  --color-sidebar-accent: var(--sidebar-accent);
  --color-sidebar-accent-foreground: var(--sidebar-accent-foreground);
  --color-sidebar-border: var(--sidebar-border);
  --color-sidebar-ring: var(--sidebar-ring);
}

/* (B) Alias layer — ONE declaration at :root; inherits the --ay-* cascade.
   This :root carries ONLY aliases (no hex); the hex --ay-* palettes live below and override per theme. */
:root {
  --radius: 0.375rem; /* match rounded-md controls */

  --background: var(--ay-surface-0);
  --foreground: var(--ay-text);
  --card: var(--ay-surface-1);
  --card-foreground: var(--ay-text);
  --popover: var(--ay-surface-1);
  --popover-foreground: var(--ay-text);

  --primary: var(--ay-accent);
  --primary-foreground: var(--ay-surface-0); /* on-accent; AA-verified incl. light */
  --secondary: var(--ay-surface-2);
  --secondary-foreground: var(--ay-text);
  --muted: var(--ay-surface-2);
  --muted-foreground: var(--ay-text-muted);

  /* accent = shadcn HOVER WASH, deliberately NOT the brand colour. */
  --accent: var(--ay-surface-2);
  --accent-foreground: var(--ay-text);

  --destructive: var(--ay-bear); /* shadcn bakes on-text white */

  --border: var(--ay-border);
  --input: var(--ay-border);
  --ring: var(--ay-accent);

  --chart-1: var(--ay-accent);
  --chart-2: var(--ay-bull);
  --chart-3: var(--ay-bear);
  --chart-4: var(--ay-warn);
  --chart-5: var(--ay-text-muted);
  --sidebar: var(--ay-surface-1);
  --sidebar-foreground: var(--ay-text);
  --sidebar-primary: var(--ay-accent);
  --sidebar-primary-foreground: var(--ay-surface-0);
  --sidebar-accent: var(--ay-surface-2);
  --sidebar-accent-foreground: var(--ay-text);
  --sidebar-border: var(--ay-border);
  --sidebar-ring: var(--ay-accent);
}
```

> **Radius reconciliation.** Part (A) does **not** declare `--radius-sm/md/lg/xl` (the canary would normally derive them from `--radius`). The Foundation `@theme inline` (§1.1) already owns `--radius-sm/md/lg`. The literal `--radius: 0.375rem` in part (B) exists only so any shadcn component that reads `var(--radius)` directly matches `rounded-md`. If a copied shadcn component references `rounded-xl` (which would want `--radius-xl`), add a single `--radius-xl: calc(var(--radius) + 4px)` to the §1.1 block at that time — do not add the full shadcn radius scale speculatively.

**Optional light-theme on-accent hardening** (append after the light palette block; leave commented unless axe flags it — `#0369a1` on `#f6f8fc` ≈ 4.9:1 already passes):

```css
/* [data-theme='light'] { --primary-foreground: #ffffff; } */
```

The existing `@custom-variant dark` (`&:where(:not([data-theme='light']) *)`) is authoritative — do **not** let the CLI inject `@custom-variant dark (&:is(.dark *))` (there is no `.dark` class; delete it if init writes one).

### 2.4 — `npx shadcn@canary` init/add

**`components.json`** (Vite + Tailwind v4 CSS-first):

```json
{
  "$schema": "https://ui.shadcn.com/schema.json",
  "style": "new-york",
  "rsc": false,
  "tsx": true,
  "tailwind": {
    "config": "",
    "css": "src/index.css",
    "baseColor": "neutral",
    "cssVariables": true,
    "prefix": ""
  },
  "aliases": {
    "components": "@/components",
    "utils": "@/lib/cn",
    "ui": "@/components/ui",
    "lib": "@/lib",
    "hooks": "@/hooks"
  },
  "iconLibrary": "lucide"
}
```

- **`tailwind.config: ""`** — the v4 CSS-first signal; satisfies the guardrail. Decline any CLI offer to scaffold a config.
- **`css: "src/index.css"`** — the real entry holding `@import 'tailwindcss'` + the bridge.
- **`cssVariables: true`** — REQUIRED so components emit semantic `bg-primary`/`text-muted-foreground` classes the bridge themes.
- **`utils: "@/lib/cn"`** — point at the repo's existing `cn` (`twMerge(clsx())`). Verify `@` resolves in `vite.config`/`tsconfig` paths; if the repo uses relative imports only, set this to the relative path and fix generated imports.
- **`style: "new-york"`** — denser variant, correct for a data-dense UI.

**Init + mandatory post-init cleanup:**

```
npx shadcn@canary init
```

1. The CLI appends a `:root { --background: oklch(...) }`, a `.dark { … }`, and possibly its own `@theme inline`. **Delete those colour blocks** — keep only the §2.3 bridge.
2. If the CLI added `@custom-variant dark (&:is(.dark *))`, **delete it**.
3. `tailwindcss-animate` via `@plugin` is fine if Skeleton/Sonner need it; otherwise the `motion/react` path covers app animation.

**Add the 10 locked components:**

```
npx shadcn@canary add button select dialog sheet dropdown-menu tooltip tabs command skeleton sonner
```

Post-add checks:
- Each lands in `src/components/ui/*`, referencing only `bg-primary text-primary-foreground bg-popover border-border ring-ring …`. **Grep generated files for raw `oklch(`/`hsl(`/hex** — there should be none.
- **Sonner** defaults to `next-themes` (not installed) — set a static `theme` prop or drop it, and alias its `--normal-bg`/`--normal-text`/`--normal-border` to `--ay-*` in the same bridge spirit (see §3.3).
- **Button** `variant="destructive"` resolves to `bg-destructive` (= `--ay-bear`), white text baked — confirm light-theme bear passes.
- **Icon-only** shadcn buttons (Dialog `<X>` close, DropdownMenu triggers) MUST carry an accessible name (shadcn's Dialog close ships `sr-only` "Close" — keep it).

### 2.5 — tweakcn & config-free notes

[tweakcn](https://tweakcn.com) is allowed **for eyeballing proportions only** — radius, spacing feel, shadow depth, control font-size — **never to generate or overwrite colour tokens.** It exports a `:root{--primary:oklch(...)}` block of hardcoded colours; never paste that in. If tweakcn helps decide `--radius` should change, change only the literal `--radius` in §2.3 — leave every `--*: var(--ay-*)` alias untouched. Treat it as a ruler, not a paint bucket.

**Config-free compliance:** no `tailwind.config.js` is created or required (`components.json` `config: ""` + `cssVariables: true`). All tokens live in `src/index.css` via `@theme inline`/`@theme`/`@utility` + a plain `:root` alias layer. All 5 themes flow with zero per-theme alias duplication. Licences: shadcn MIT, lucide-react ISC, Tailwind MIT, Sonner MIT — all import-safe, no AGPL.

---

## 3. Component specs

> **Shared convention across all component specs.** (1) The `Button` atom lives at `src/components/atoms/Button.tsx` and is the single base; shadcn's copied `button.tsx` is **not** used directly as the app button — the app `Button` is the `cva`-driven atom below (which may consume shadcn's `Slot`). QueryState/Skeleton snippets that `import { Button } from './ui/button.tsx'` should import the **app atom** (`../atoms/Button.tsx`) instead — resolved here. (2) Focus is handled globally by the §1.4 `:focus-visible` rule; component snippets that add `focus-visible:ring-2 focus-visible:ring-ring` are belt-and-suspenders and acceptable, but never pair `focus:outline-none` without a visible replacement.

### 3.1 Button + control replacements

#### 3.1.1 Token bridge

The bridge in §2 already declares `--color-primary`, `--color-destructive`, `--color-ring`, `--color-background`, `--color-foreground`. The Button spec needs no additional aliases. (The earlier Button-spec draft re-declared these plus `--color-destructive-foreground`; that is **superseded by §2** — use §2's set, which omits `destructive-foreground`.)

#### 3.1.2 Variant matrix (themed only via `--ay-*`)

| variant | base classes | replaces |
|---|---|---|
| `primary` | `bg-accent text-surface-0 hover:opacity-90` | `GoButton` |
| `secondary` | `bg-surface-2 text-ay-text hover:bg-surface-1` | menu chrome |
| `outline` | `border border-ay-border bg-surface-1 text-ay-text hover:border-accent` | the repeated bordered button (AppShell, Charts, ModeToggle) |
| `ghost` | `text-accent hover:bg-surface-2` | text-link tone (Settings, Cockpit) |
| `danger` | `bg-bear text-surface-0 hover:opacity-90` | destructive paper actions |
| `icon` | `outline` chrome, square, single lucide child | icon-only triggers |

**Sizes:** `sm` = `h-8 px-2 text-body-sm`; `md` = `h-9 px-4 text-body-sm`. `icon` overrides to square: `sm`→`size-8 px-0`, `md`→`size-9 px-0`.

**Shared base:** `inline-flex items-center justify-center gap-1.5 rounded-md font-medium transition-[opacity,background-color,border-color] duration-[var(--duration-fast)] ease-[var(--ease-standard)] disabled:opacity-50 disabled:pointer-events-none`. Focus comes from the global rule (§1.4).

> **CONFLICT RESOLVED — control text size.** The Button draft used `text-xs`/`text-sm` literals; the Foundation maps control text to `text-body-sm` (13px). **Decision: control text = `text-body-sm`.** Size `sm` keeps `text-body-sm` too (not `text-xs`) — 13px is the smallest control text; sub-13px controls hurt touch targets on the 480px mobile target. Topbar chrome that was `text-xs` becomes `text-caption` only for non-interactive labels, not buttons.

**Guardrails baked in:** `variant="icon"` makes `ariaLabel` a **required** prop (type-enforced) — an icon-only button without a name fails to typecheck. Loading renders a spinning lucide `Loader2`, sets `aria-busy`, and **keeps the text mounted** so the accessible name is stable (today's `GoButton` swaps the label to `'…'` — a latent flake).

#### 3.1.3 `Button.tsx` sketch (SPEC text — not committed)

```tsx
// src/components/atoms/Button.tsx
import { forwardRef } from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2, type LucideIcon } from 'lucide-react';
import { cn } from '../../lib/cn.ts';

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 rounded-md font-medium ' +
    'transition-[opacity,background-color,border-color] duration-[var(--duration-fast)] ease-[var(--ease-standard)] ' +
    'disabled:opacity-50 disabled:pointer-events-none',
  {
    variants: {
      variant: {
        primary: 'bg-accent text-surface-0 hover:opacity-90',
        secondary: 'bg-surface-2 text-ay-text hover:bg-surface-1',
        outline: 'border border-ay-border bg-surface-1 text-ay-text hover:border-accent',
        ghost: 'text-accent hover:bg-surface-2',
        danger: 'bg-bear text-surface-0 hover:opacity-90',
        icon: 'border border-ay-border bg-surface-1 text-ay-text hover:border-accent px-0',
      },
      size: { sm: 'h-8 px-2 text-body-sm', md: 'h-9 px-4 text-body-sm' },
    },
    compoundVariants: [
      { variant: 'icon', size: 'sm', class: 'size-8 px-0' },
      { variant: 'icon', size: 'md', class: 'size-9 px-0' },
    ],
    defaultVariants: { variant: 'primary', size: 'md' },
  },
);

type Variant = NonNullable<VariantProps<typeof buttonVariants>['variant']>;

// icon-only REQUIRES ariaLabel; text buttons must NOT pass it (label is the name).
type AriaName =
  | { variant: 'icon'; ariaLabel: string }
  | { variant?: Exclude<Variant, 'icon'>; ariaLabel?: never };

type BaseProps = Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label'> &
  VariantProps<typeof buttonVariants> & {
    loading?: boolean;
    icon?: LucideIcon;
    asChild?: boolean; // Slot — wrap a router <Link> while keeping button styling
  };

export type ButtonProps = BaseProps & AriaName;

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { className, variant, size, loading, disabled, icon: Icon, asChild, ariaLabel, children, ...rest },
  ref,
) {
  const Comp = asChild ? Slot : 'button';
  return (
    <Comp
      ref={ref}
      type={asChild ? undefined : (rest.type ?? 'button')}
      aria-label={ariaLabel}
      aria-busy={loading || undefined}
      disabled={disabled || loading}
      className={cn(buttonVariants({ variant, size }), className)}
      {...rest}
    >
      {loading ? (
        <Loader2 aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
      ) : (
        Icon && <Icon aria-hidden className="size-4" />
      )}
      {variant !== 'icon' && children}
    </Comp>
  );
});
```

`GoButton` becomes a 3-line wrapper preserving its `{onClick, loading}` signature and the e2e name `'Go'` (text stays mounted under the spinner → name stable):

```tsx
export const GoButton = ({ onClick, loading }: GoButtonProps) => (
  <Button onClick={onClick} loading={loading}>Go</Button>
);
```

`ModeToggle` is **not** folded into Button — it carries `aria-pressed` toggle semantics + a state label. It stays standalone but reuses `buttonVariants({variant:'outline'})` for its class string. Its e2e name `'Toggle live/history mode'` must not change.

#### 3.1.4 Select replacement (Radix under the existing API)

Keep the **exact** `SelectProps` shape (`value/options/onChange/ariaLabel/placeholder/disabled/className`) and the `SelectOption = {value;label} | string` union with `normalize`. Call sites need **zero** changes. Trigger styling uses `--ay-*` utilities + `shadow-e2` on the content; Radix exposes the trigger as a labelled `combobox`, so `getByLabel('Underlying'|'Interval'|'Expiry'|'Theme')` stays green.

> **CRITICAL constraint — do NOT swap the shared `Select` atom in place.** Three test patterns break on a native→Radix swap (enumerated in §3.1.5). The Radix Select ships as a **new atom** (`SelectMenu.tsx`) and migrates per-call-site so each page's spec moves with it. The native `Select` atom stays for FilterBar + BacktestRunner until their specs are rewritten.

#### 3.1.5 Native-control migration contract (at-risk patterns)

1. **`.locator('option')` against a Radix Select — BREAKS.** `options-chain.spec.ts` counts `<option>` elements (the empty-options regression). Radix renders no `<option>`s (portal, lazy). **Resolution:** keep FilterBar's Underlying/Interval/Expiry on the **native** `Select` atom; rewrite the regression to open the listbox + count `getByRole('option')` as a *separate* task — do not silently break it.
2. **`selectOption(values[0])` — BREAKS on Radix** (Playwright `selectOption` only works on native `<select>`). Same resolution as #1.
3. **`fireEvent.change(getByLabelText('Strategy'), …)` — BREAKS on Radix** (`BacktestRunnerPage.spec.tsx`). Radix needs click-open-then-click-item. **Resolution:** `BacktestRunner`'s Select stays native, OR its spec is updated in lockstep. Because it imports the **shared** `Select` atom, swapping the atom internals is **all-or-nothing** — so introduce Radix as a new atom and migrate call sites individually.
4. **`getByRole('button', { name: '…' })` with trailing glyphs** (`'All Menu ▾'`, `'Next ›'`). lucide icons are `aria-hidden`, so the name becomes the text **without** the glyph → test strings change to `'All Menu'`, `'Next'`. Grep `name: '.*[›‹▾▸▴●…]'` and update each selector in the same change.
5. **`name`-stable loading.** New Button keeps text mounted under the spinner → `getByRole('button',{name:'Go'})` is stable. Net-positive, no test change.
6. **`data-testid` hooks (`app-shell`, `candlechart`) — preserve verbatim.**
7. **`input[name="password"]`** (login) must retain `name="password"` if ever moved to a shadcn Input (out of scope — no Input in the locked set).

**The contract:** shared atoms are swapped behind a stable public API; accessible names a test asserts are frozen; icon children are `aria-hidden`; icon-only buttons carry `ariaLabel`; no bare `focus:outline-none`; theme only via `--ay-*`; `data-testid` verbatim.

**Recommended sequencing:** (1) add deps + bridge + fonts (no behaviour change); (2) ship `Button.tsx`, rewrite `GoButton` as a wrapper, migrate bordered/ghost natives page-by-page (only glyph-string specs need edits); (3) ship Radix Select as a **new** atom, migrate Theme picker + StrategyBuilder first (no `option`-locator/`selectOption` tests touch them), leave FilterBar + BacktestRunner native until their specs are rewritten.

### 3.2 DataTable — TanStack Table v8 (headless) under `DataTable.tsx`

#### 3.2.1 Invariants (must not break)

- **Public prop shape is frozen:** `columns`, `rows`, `rowKey`, `pageSize=0`, `initialSort`, `emptyMessage='No data.'`, `ariaLabel`, `rowClassName`. The `DataColumn<Row>` contract (`id, header, align?, sortValue?, sortType?, render, cellClassName?, headerClassName?, mobileLabel?`) is consumed by ~20 pages — **must stay byte-identical** or every call site breaks.
- **Test hooks (`DataTable.spec.tsx`):** `getByRole('table')`; `columnheader` accessible name = header text (chevron must be `aria-hidden`); sortable header is a `button` named by header text; `aria-sort` ∈ `ascending|descending|none` and **absent** on non-sortable columns; pagination renders exact `1–2 of 3` text + `button name="Prev"/"Next"` with disabled bounds; `cellClassName` lands on a `td`; empty message appears ≥1× (rendered in both desktop + mobile DOM); `mobileLabel` text appears exactly `ROWS.length` times. **All must pass unchanged.**
- **e2e:** `getByRole('region',{name})` (scalper checklist); `getByRole('table',{name})` (data-ops — needs `aria-label` on the `<table>`); `button name="Next"` (data-ops).
- **Theming via `--ay-*` utilities only.** Money never via `parseFloat` — decimal sort routes through `compareDecimal`.
- `OptionsChainTable` and `OiAnalysisTable` are **NOT** DataTable consumers — bespoke mirrored grids; they **stay bespoke** and adopt only three shared primitives (`pinnedStyle`, the virtualizer wrapper, the density token). See §3.2.6.

Deps: `@tanstack/react-table@^8`, `lucide-react`, (`@tanstack/react-virtual@^3` only when virtualization is first used).

#### 3.2.2 ColumnDef contract

Keep `DataColumn<Row>` as the authored type; add **optional** fields (existing call sites compile untouched): `pin?: 'left'`, `filter?: 'text' | 'select'`, `mono?: boolean` (default `true` for right-aligned), `defaultHidden?: boolean`, `lockVisible?: boolean`.

Internal adapter (`columnAdapter.ts`, new helper):

```ts
import { type ColumnDef, type SortingFn } from '@tanstack/react-table';
import { compareDecimal } from '../lib/decimal.ts';

const decimalSort: SortingFn<unknown> = (a, b, id) =>
  compareDecimal(String(a.getValue(id) ?? ''), String(b.getValue(id) ?? ''));

export function adaptColumns<Row>(cols: DataColumn<Row>[]): ColumnDef<Row>[] {
  return cols.map((c) => ({
    id: c.id,
    accessorFn: c.sortValue ?? (() => null),   // drives sort/filter/facet ONLY
    header: c.header,                           // plain string → header text === accessible name
    cell: (info) => c.render(info.row.original),
    enableSorting: !!c.sortValue,
    enableHiding: !c.lockVisible,
    enableColumnFilter: !!c.filter,
    sortingFn: c.sortType === 'decimal' ? decimalSort : c.sortType === 'text' ? 'text' : 'basic',
    sortUndefined: 'last',                      // reproduces nulls-last
    meta: {
      align: c.align ?? 'right',
      mono: c.mono ?? (c.align ?? 'right') === 'right',
      headerClassName: c.headerClassName,
      cellClassName: c.cellClassName,
      mobileLabel: c.mobileLabel,
      pin: c.pin,
      filter: c.filter,
    },
  }));
}
```

Table setup: `enableMultiSort: true`, `maxMultiSortColCount: 3`, `isMultiSortEvent: (e) => e.shiftKey`, `enableSortingRemoval: false` (so first click = desc, second = asc, never "off" — keeps the spec green), core/sorted/filtered/(paginated)/faceted row models. Column-pinning seed: `{ left: columns.filter(c => c.pin === 'left').map(c => c.id) }`.

Pinned-cell helper (applied to `<th>` + `<td>` of a pinned column):

```ts
function pinnedStyle(col): CSSProperties | undefined {
  if (col.getIsPinned() !== 'left') return undefined;
  return { position: 'sticky', left: col.getStart('left'), zIndex: 11 }; // header sticky uses z-20
}
// class adds opaque bg + right divider: 'bg-surface-1 shadow-[inset_-1px_0_0_var(--ay-border)]'
```

Cell renderers reuse existing badges (`OiBadge4`, `DataBar`, `ValueDeltaCell`, `SignedCount`) unchanged — TanStack only calls `c.render(row.original)`.

#### 3.2.3 Features (all behind optional props → omitting yields today's exact render)

New props: `enableColumnVisibility?`, `enableDensityToggle?`, `initialDensity? ('comfortable'|'compact', default 'comfortable')`, `enableMultiSort? (default true)`, `zebra? (default true)`, `virtualizeAfter?`, `persistKey?`.

- **Multi-sort** (shift-click) via `getToggleSortingHandler()`.
- **Lucide sort chevrons** (`ChevronUp`/`ChevronDown`/`ChevronsUpDown`, `aria-hidden`, `size-3 text-ay-muted`) replacing `▲▼⇅`; a `text-dense` `aria-hidden` index badge when multi-sorting.
- **Column-visibility menu** — toolbar `Columns3` button (icon-only carries `aria-label="Choose columns"`) opening a shadcn `DropdownMenu` of checkbox items from `getAllLeafColumns().filter(c=>c.getCanHide())`.
- **Density toggle** — `useState`, persisted per `persistKey` in `localStorage`. comfortable → `px-2 py-1` (today's values); compact → `px-2 py-0.5`. Control = shadcn `Tabs` or two `aria-pressed` buttons; icon-only variants carry `aria-label`.
- **Faceted/column filter** — `filter:'select'` → shadcn `Select`/`Command` from `getFacetedUniqueValues()`; `filter:'text'` → debounced input. Faceted models attached only when ≥1 column declares a filter.
- **Zebra + row-hover + sticky header** — keep the existing sticky `thead`; pinned header cells bump to `z-20`. Body `<tr>`: `odd:bg-transparent even:bg-surface-1/40 transition-colors hover:bg-surface-2/60` (alpha on `--ay-*` utils so every theme stays legible); `rowClassName` kept last so callers can override.

#### 3.2.4 a11y

Render a **real** `<table><thead><tr><th scope="col">…<tbody><tr><td>` (TanStack is headless — you own the markup). `aria-sort` on `<th>`: sortable → `ascending|descending|none`, non-sortable → omit the attribute. Sortable header = `<button type="button">` named by header text (chevron + index `aria-hidden`). **Put `aria-label={ariaLabel}` on BOTH the `role="region"` wrapper AND the `<table>`** (satisfies both `scalper-checklist` region-by-name and `data-ops` table-by-name). Empty message rendered in both desktop + mobile DOM; on filtered-to-zero, same empty cell with `colSpan={visibleLeafColumns.length}`. No bare `focus:outline-none`.

#### 3.2.5 Mobile + virtualization

- **Mobile (~480px):** keep the existing `md:hidden` card list verbatim, but iterate `table.getRowModel().rows` (post-sort/filter/paginate) so phone reflects sort/filter/density too. A column appears on mobile iff it sets `mobileLabel`. New toolbar renders above the cards; on 480px prefer a shadcn `Sheet` for the column/filter menus. Sticky-left pin is desktop-only (ignored in card mode).
- **Virtualization (opt-in):** default stays non-virtual. `virtualizeAfter?: number` — when `rows.length > virtualizeAfter` **and** `pageSize===0`, swap `<tbody>` for `@tanstack/react-virtual` (`overscan: 12`, top/bottom spacer). Mutually exclusive with pagination. Keep OFF for any table a spec counts rows on (the audited flat tables page, so never enter this path). Add `aria-rowcount`/`aria-rowindex` when virtualized. `@tanstack/react-virtual` added to deps only when first used.

#### 3.2.6 OptionsChainTable / OiAnalysisTable stay bespoke

They are mirrored CALL | STRIKE | PUT + PCR grids with two-row headers, per-side bar maxima, `putColumns = [...callColumns].reverse()`, per-side ITM/ATM/max-OI tints, and a `ChainColumn` type (`render(cell, ctx)`) structurally different from `DataColumn` (`render(row)`). Forcing them through `DataTable` would gut the public contract. They adopt three shared primitives only: **`pinnedStyle()`** (sticky-left STRIKE rail), the **`@tanstack/react-virtual` wrapper** (the chain is the *primary* virtualization customer — unpaginated, 200+ strikes), and the **density token**. They also gain `aria-label` on the `<table>` (same §3.2.4 fix) and the shared lucide icons. The dense mirror semantics (red/green OI bars, ATM cream-tint, max-cell ring, ITM `bg-accent/10`, `OiBadge4`/`DataBar`/`PulseValue`) are untouched.

### 3.3 QueryState + Skeleton + Sonner

#### 3.3.1 The bug this fixes

The OI hook helper maps **only** 422 to the empty shape and re-throws everything else, but every page renders its empty-state off `data == null && !isLoading` and **never checks `isError`**. So a backend 500/network drop renders a literal "No chain — pick an underlying" lie, with no retry and no toast. The toast plumbing (`ApiError.silenced`, `silenceToast`) is wired but **dead** — nothing consumes it; `main.tsx` constructs the `QueryClient` with no `queryCache`/`mutationCache` `onError`. Loading is bare inconsistent text; `DataTable` has no loading state (renders "No data." until data snaps in → CLS). Exactly one page (`FoldDrilldownModal`) does the correct 4-way branch.

#### 3.3.2 `<QueryState>` — the shared 4-way wrapper

Routes a TanStack result: **pending** → `<Skeleton>`; **error** → inline error card with **Retry** (`refetch()`); **empty** → illustrated empty (lucide icon + copy); **success** → `children(data)` (data narrowed non-null). Separates error from empty (kills the "500 looks like empty" bug). A render-prop lets it narrow `undefined` away; an `isEmpty` predicate override judges emptiness on a derived array.

```tsx
import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { AlertTriangle, Inbox, RefreshCw, type LucideIcon } from 'lucide-react';
import { Skeleton } from './ui/skeleton.tsx';
import { Button } from './atoms/Button.tsx';   // app atom (§3.1), NOT shadcn ui/button

type QueryLike<T> = Pick<UseQueryResult<T>, 'isPending' | 'isError' | 'isSuccess' | 'data' | 'refetch'>;

interface QueryStateProps<T> {
  query: QueryLike<T>;
  children: (data: NonNullable<T>) => ReactNode;
  skeleton?: ReactNode;
  isEmpty?: (data: NonNullable<T>) => boolean;
  empty?: { icon?: LucideIcon; title: string; hint?: string };
  errorTitle?: string;
  pendingWhenDisabled?: boolean;
}

function defaultIsEmpty(data: unknown): boolean {
  if (data == null) return true;
  if (Array.isArray(data)) return data.length === 0;
  if (typeof data === 'object') {
    const items = (data as { items?: unknown[] }).items;
    if (Array.isArray(items)) return items.length === 0;
  }
  return false;
}

export function QueryState<T>({
  query, children, skeleton, isEmpty = defaultIsEmpty, empty,
  errorTitle = "Couldn't load this data", pendingWhenDisabled = false,
}: QueryStateProps<T>) {
  if (query.isError) {                         // error wins over pending
    return (
      <div role="alert" data-testid="qs-error"
        className="flex flex-col items-center gap-3 rounded-lg border border-bear/40 bg-surface-1 px-4 py-8 text-center">
        <AlertTriangle aria-hidden="true" className="size-6 text-bear" />
        <p className="text-body font-medium text-ay-text">{errorTitle}</p>
        <p className="text-caption text-ay-muted">The server returned an error. This is not an empty result.</p>
        <Button variant="outline" size="sm" icon={RefreshCw} onClick={() => void query.refetch()}>Retry</Button>
      </div>
    );
  }
  if (query.isPending) {
    if (pendingWhenDisabled && empty) return <EmptyCard {...empty} />;
    return (
      <div data-testid="qs-loading">
        <span className="ay-sr-only" role="status" aria-live="polite">Loading…</span>
        <div aria-hidden="true">{skeleton ?? <Skeleton variant="card" />}</div>
      </div>
    );
  }
  const data = query.data as NonNullable<T>;
  if (isEmpty(data)) return <EmptyCard {...(empty ?? { title: 'No data for this selection.' })} />;
  return <>{children(data)}</>;
}

function EmptyCard({ icon: Icon = Inbox, title, hint }: { icon?: LucideIcon; title: string; hint?: string }) {
  return (
    <div data-testid="qs-empty"
      className="flex flex-col items-center gap-2 rounded-lg border border-ay-border bg-surface-1 px-4 py-8 text-center">
      <Icon aria-hidden="true" className="size-6 text-ay-muted" />
      <p className="text-body font-medium text-ay-text">{title}</p>
      {hint && <p className="text-caption text-ay-muted">{hint}</p>}
    </div>
  );
}
```

> **CONFLICT RESOLVED — QueryState prop surface.** The Foundation/Components draft and the hero-page drafts diverge on the prop names: the canonical wrapper above takes `query`, `children` (render-prop), `skeleton`, `isEmpty` (predicate), `empty` (`{icon,title,hint}`), `errorTitle`, `pendingWhenDisabled`. Some hero-page snippets show `loading=`, `error=(e)=>…`, `errorMessage=`, `onRetry=`, `emptyMessage=`, or `isEmpty={boolean}`. **Decision: the §3.3.2 signature is canonical.** Map the hero snippets onto it: `loading` → `skeleton`; `error`/`errorMessage`/`onRetry` → `errorTitle` (Retry always calls `query.refetch()` internally, so `onRetry` is unnecessary); `emptyMessage` → `empty.title`; `isEmpty={d => …}` is the predicate form (a boolean literal is not accepted — pass a predicate). The hero specs below are written against this canonical signature.

**a11y/selectors:** error card `role="alert"` + `data-testid="qs-error"`; Retry is a text `<button>` (no icon-only leak); `data-testid="qs-empty"`/`qs-loading` let e2e finally distinguish 500 from empty; skeleton is `aria-hidden` + SR-only `role="status"` "Loading…". `pendingWhenDisabled` shows empty (not an eternal skeleton) for symbol-gated hooks whose `isPending` stays true forever in TanStack v5.

#### 3.3.3 Skeleton variants (dimensioned to content → zero CLS)

shadcn `Skeleton` copy-in, bridged to `--ay-*` with a `.ay-skeleton` reduced-motion hook (the shadcn default hardcodes `animate-pulse` with no opt-out). Add to `index.css`:

```css
@media (prefers-reduced-motion: reduce) {
  .ay-skeleton { animation: none; }
}
```

Base atom uses `class="ay-skeleton animate-pulse rounded bg-surface-2"`. Variants: `table-rows` (mirrors `DataTable` metrics — pass `cols`=`columns.length`, `rows`=`pageSize`), `metric-strip` (`h-8 w-28` pills), `chart-block` (solid block at the chart's exact `height` prop → no reflow), `card` (3-line card body). Variant→page mapping: `table-rows` on every DataTable page; `metric-strip` on OptionsChain/Dashboard header strips; `chart-block` on EChart/CandleChart/Vix pages (pass the same `height` literal); `card` on Dashboard cards + FoldDrilldown body. DashboardPage cold-load becomes three `<Skeleton variant="card" />` in the same grid instead of three empty cards.

#### 3.3.4 Sonner + toast-vs-inline policy

Add `sonner` (MIT). Mount one `<Toaster theme="dark" closeButton position="top-right">` in `App.tsx` (sibling of the router outlet). Bridge via Sonner's CSS-var API in `index.css` (richColors OFF so our tokens win):

```css
[data-sonner-toaster] {
  --normal-bg: var(--ay-surface-1);
  --normal-border: var(--ay-border);
  --normal-text: var(--ay-text);
  --success-bg: var(--ay-surface-1);  --success-text: var(--ay-bull);
  --error-bg: var(--ay-surface-1);    --error-text: var(--ay-bear);
  --border-radius: var(--radius-md);
}
```

Provide lucide icons (`CheckCircle2`, `AlertCircle`) so glyphs are `currentColor`-themed.

**Revive the dead `silenced` plumbing** — rebuild the `QueryClient` with `QueryCache` + `MutationCache` `onError` honouring `ApiError.silenced`:

```ts
function reportError(error: unknown, fallback: string) {
  if (error instanceof ApiError && error.silenced) return; // 422 DATA_GAP stays silent
  toast.error(error instanceof Error ? error.message : fallback);
}
const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: false, refetchOnWindowFocus: false } },
  queryCache: new QueryCache({ onError: (e) => reportError(e, 'Failed to load data.') }),
  mutationCache: new MutationCache({ onError: (e) => reportError(e, 'Action failed.') }),
});
```

**Toast-vs-inline rule:**

| Situation | Surface | Why |
|---|---|---|
| Query (page-data) load error | **Inline** QueryState error card (canonical) **+ toast** (safety net) | region is the natural retry place; toast catches below-the-fold failures |
| Query empty (422 / no rows) | **Inline empty card only**, no toast | not an error; `silenced` suppresses it |
| Mutation success (publish, take signal, save note, sync) | **Toast** `toast.success(…)` | transient confirmation; the list already refetches |
| Mutation error mapping to a form field | **Inline** local `text-bear` card (keep) | user corrects input in place; tag those calls `silenceToast:true` to avoid a double surface |
| Background/optimistic mutation error | **Toast** | no inline anchor |

---

## 4. Hero page redesigns

> All three are **design only**. Each maps every visual to the locked toolkit; all colour flows through `--ay-*`; field names/props/hooks/test hooks are quoted from real files. QueryState usages below follow the canonical §3.3.2 signature.

### 4.0 Taste-pass tweaks (apply across all three hero pages)

From the §1.7 aesthetic direction — additive, guardrail-safe, fold into each page's section spec:

- **Signature header lockup (all pages).** §1.7.2: display-face `<h1>` + 2px `--ay-accent` left-rule + the existing live-state dot, composed as one repeated lockup (replaces three ad-hoc header layouts).
- **Live-dot breathing micro-interaction.** The "Live" `Circle` dot gets a slow breathing-opacity (`.ay-pulse`-family keyframe, **≥1.5s**, reduced-motion-gated) so liveness reads without text; **Stale** stops the breathing + flips the dot `--ay-warn`.
- **One orchestrated load beat (motion is ON by default — §7 Q3).** Replace each page's three separate motions with ONE sequence: header settles (0ms) → metric strip staggers in (`y 4–6px→0`, 30–40ms stagger) → chart/table block fades up (~120ms), ≤200ms total envelope, `--ease-standard`. **The data grid never animates row-by-row** (fights ResizeObserver + reads as jank under live ticks — extend the chart-wrapper no-motion rule to the chain grid).
- **Metric-card hierarchy rhythm.** Every KPI/metric tile: `text-caption tracking-wide uppercase text-ay-muted` label / `text-display`-or-`text-h3` `nums` value / `text-caption` delta-context sub-line. The uppercase wide-tracked label is the editorial "terminal" tell (pins the existing `tracking-wide` token to a real use).
- **Chart cards read as ONE plane (§4.3.D + any chart card).** The echarts `makeOption` closure **must** consume `--ay-chart-grid` (gridlines), `--ay-chart-crosshair` (crosshair), `--ay-bull`/`--ay-bear` (bars + header swatches), bg `transparent`. echarts defaults are forbidden — kills the "pasted-on" look.
- **Distill the chrome.** Drop the breadcrumb on desktop (last-crumb-on-mobile only) and collapse the FII/DII "as on / RefreshCw / 420d" cluster to one `text-caption` line — less chrome lets the display-face title + metric strip carry the weight.
- **Atmosphere only on zero-data canvases.** The §1.7.2 radial mesh on the Dashboard cold-load `EmptyCard` + login — never behind a grid.

### 4.1 Dashboard (`/dashboard`)

#### Current read

A single `<div>`: an `ay-sr-only` `<h1>` (no visible title), a flat `flex flex-wrap` run of bordered text `Pill`s as the status strip, then a `grid grid-cols-1 lg:grid-cols-3` of three minimally-elevated `Card`s (Active Signals / Paper P&L / Jobs) with an `Open →` literal-glyph link. The single big number (realized P&L, `text-2xl`) is the only thing with weight; headline KPIs are buried in one card's `<dl>`. **No loading/error states** — every block is gated on truthy data (`{s && …}`, `summary ? …`, `recentSignals.length > 0`) so on cold load/refetch/error the strip and tiles vanish. Jobs progress is a bare `bg-accent` div with no `role="progressbar"`.

**Must retain:** the one `<h1>` (axe `page-has-heading-one`); `useSystemStatus` strip fields polled @5s; Active Signals → `useSignals('ACTIVE')` `.slice(0,7)` → `/signals`; Paper P&L hooks → `/paper`; Jobs via `useRecentJobs` + `JOB_ACTIVE_STATES`; `money`/`toneClass`/`formatDecimal`/`isNegative` (never `parseFloat`); the exact asserted strings **`OPEN`**, **`1 running · 2 queued`**, **`RELIANCE`**, **`12500.00`**, **`backtest`**; `data-testid="app-shell"` (lives in AppShell, untouched).

#### Wireframe — desktop (≥1024px)

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Dashboard                                              ● Live · 13:30:00 IST │  ← visible <h1> + as-of
│  Cockpit — system health, signals, paper P&L and in-flight jobs               │  ← subtitle
├────────────────────────────────────────────────────────────────────────────┤
│  STATUS STRIP (5 elevated tiles, shadow-e1, icon + label + value)             │
│  [◷ System UP] [▤ Market OPEN] [⚿ Kite VALID] [∿ Ticker CONNECTED] [⚙ 1R·2Q] │
├────────────────────────────────────────────────────────────────────────────┤
│  KPI STRIP (Paper P&L hero numbers, hoisted out of the card)                  │
│  [Realized 12,500.00↑] [Day 2,500.00↑] [Open/Closed 1/4] [Win rate 75.0%]     │
├────────────────────────────────────────────────────────────────────────────┤
│  SECTIONS (grid-cols-3, elevated cards, lucide section icons)                 │
│  [◈ Active Signals ↗] [◔ Paper P&L ↗ (sparkline)] [⚙ Jobs (progressbar)]     │
└────────────────────────────────────────────────────────────────────────────┘
```

#### Wireframe — mobile (~480px)

```
┌──────────────────────────────┐
│ Dashboard      ● 13:30 IST    │
│ Cockpit overview              │
├──────────────────────────────┤
│ STATUS (2-col grid of tiles)  │
│ [◷System UP] [▤Market OPEN]   │
│ [⚿Kite]     [⚙Jobs 1R·2Q]    │
├──────────────────────────────┤
│ KPI (2-col grid)              │
│ [Realized 12500][Day 2500]    │
│ [Open/Trd 1/4 ][Win 75.0%]   │
├──────────────────────────────┤
│ SECTIONS (stacked, full-width)│
│ [◈ Active Signals ↗ ]         │
│ [◔ Paper P&L ↗ ]              │
│ [⚙ Jobs ]                     │
└──────────────────────────────┘
```

#### Section spec

- **A. Header (NEW visible title).** Replace the `ay-sr-only` h1 with a visible `<h1 className="text-h1 text-ay-text">Dashboard</h1>` + a `text-body-sm text-ay-muted` subtitle; the orphaned `s.asOf.slice(11,19)` moves up here as a `nums text-caption text-ay-muted` "Live · HH:mm:ss IST" line with a lucide `Circle` dot (`fill-bull`, `aria-hidden` — the word "Live" carries meaning). One `<h1>`, accessible name "Dashboard" → axe holds.
- **B. Elevated status strip.** Re-skin the flat `Pill` row as a `grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5` of `StatusTile`s — each `card` + `shadow-e1` + a leading lucide icon (`Activity`/`CalendarClock`/`KeyRound`/`Radio`/`Cog`, `aria-hidden`) + uppercase `text-caption tracking-wide` label + a `nums font-semibold` value. **Value strings unchanged** (`s.market.phase` → `OPEN`, jobs → `` `${running} running · ${queued} queued` ``). `phaseTone` map retained.
- **C. KPI strip.** Surface the four buried `<dl>` numbers as their own `shadow-e1` `Kpi` tiles (`grid grid-cols-2 lg:grid-cols-4`): Realized P&L (`money(summary.realizedTotal)` → **`12500.00`**), Day P&L, Open/Closed (`positions.items.length / summary.trades`), Win rate (`formatDecimal(summary.winRate, 4)` — value unchanged). `text-display nums` value with a decorative `ArrowUpRight`/`ArrowDownRight` (tone + the number carry meaning, never colour-only). **Realized must render in exactly ONE place** — keep it in the KPI strip and drop the old `text-2xl` card block (`getByText` throws on duplicate match).
- **D. Filter bar — NONE.** Passive aggregation of polled queries; do not add the OI `FilterBar` (wrong domain). Adding a control bar here is speculative.
- **E. Section cards.** Promote `Card` to `card` + `shadow-e1` + a lucide section icon (`Radar`/`Wallet`/`Cog`); replace the literal `→` with `ArrowUpRight`. **Do NOT** route these ≤7-row deep-link previews through `DataTable` (overkill). Active Signals keeps `.slice(0,7)`, `key={sig.id}`, `RELIANCE` verbatim. Paper P&L hosts a small realized-equity sparkline via the existing `EChart` (echarts kept). Jobs keeps `j.kind` (`backtest`) and upgrades the bare bar to `role="progressbar"` with `aria-valuenow/min/max` + `aria-label`.
- **F. States via QueryState/Skeleton.** Status strip → 5 `Skeleton` tiles while pending; KPI strip → 4 skeleton tiles; each card body wrapped in `QueryState` keeping today's empty copy ("No live signals yet…", "No paper activity yet.", "No jobs running."). The Dashboard spec mocks return `data` immediately (no `isPending`/`isError`), so the success branch fires and all five assertions stay green.

#### Motion

(1) Metric-tile mount stagger (`opacity 0→1, y 4px→0`, ~30ms stagger) on first data arrival via `motion/react`; (2) value-change pulse reusing the existing `.ay-pulse` keyframe on Realized/Day P&L; (3) Jobs progress-bar width tween between polls (`transition: width var(--duration-base)`). No hover-scale/card-lift. All reduced-motion-gated.

#### Retention check

All six hooks read identically; `.slice(0,7)` + `JOB_ACTIVE_STATES` unchanged; exact-decimal path preserved; deep-links preserved; exactly one visible `<h1>`; every lucide icon `aria-hidden`; new `role="progressbar"` is a net a11y gain; no bare `focus:outline-none`. **Watch-item:** realized P&L must render in exactly one place. Optional additive `data-testid="dashboard-status"`/`dashboard-kpis` for e2e stability (no removals).

### 4.2 Options Chain (`/options/chain`)

#### Current read

A bare `<div>` with no visible chrome: an `ay-sr-only` H1, a row mixing native-`<select>` `FilterBar` + solid `GoButton` + a hand-rolled `ColumnSettings` popover (trigger label `"Column Setting ▾"`), a flat `Metric`-pill "live header strip" (VIX/PCR/ATM/DTE/spot/`stale`), a one-line empty `<p>`, then the bespoke mirrored `OptionsChainTable`. Pending fields hide in `title=` tooltips; `stale` is a tiny bare chip; sort glyphs are Unicode; the strike column scrolls away horizontally; **no loading and no error state at all** (only a partial empty path).

**Must retain:** `FilterBar showName showExpiry`, `GoButton → chainQ.refetch()`, `ColumnSettings → optional/optionalKeys`, all 8 visible + 3 optional columns mirrored CALL/PUT + trailing PCR, `OiBadge4`/`DataBar`/`ValueDeltaCell`/`PulseValue`, ATM/ITM/max-cell tints, the live header metrics, `aria-live="polite"` on the strip, **`role="region"` + `aria-label="Options chain"`** on the scroll container, `scope="colgroup"/"col"` headers, the `ay-sr-only` ITM/ATM cues, `tabular-nums`/`nums` everywhere, decimal-string discipline.

#### Wireframe — desktop (~1440px)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Markets › Options ▸ Chain                                  [⏱ as of 14:32:07]  │
│ Options Chain                                              ● Live   ⚠ Stale     │  ← visible H1 + freshness chip
│ NIFTY 50 · 26 Jun expiry · black-76 live greeks                                 │
├──────────────────────────────────────────────────────────────────────────────┤
│ METRIC STRIP (elevated, shadow-e1; 2-line cards surfacing the title= data)      │
│ [NIFTY 24318▲][INDIA VIX 12.97 +2.37%][Total PCR 0.84][ATM 24300][DTE 3]        │
├──────────────────────────────────────────────────────────────────────────────┤
│ FILTER BAR  [Underlying▾][Expiry▾][Interval▾][Live│Hist](date) …  [⊞ Columns▾][⟳ Go] │
├──────────────────────────────────────────────────────────────────────────────┤
│ CHAIN GRID (role=region aria-label="Options chain"; sticky header + sticky STRIKE)│
│ ───── CALL ─────│ Strike │───── PUT ─────│PCR   (zebra, hover, ATM warn-tint)     │
│  …pinned-left shadow on Strike when scrolled…                                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

#### Wireframe — mobile (~480px)

```
┌────────────────────────────────┐
│ Markets › Options ▸ Chain       │
│ Options Chain        ● Live      │
│ NIFTY 50 · 26 Jun · greeks       │
├────────────────────────────────┤
│ metric strip → snap-scroll row   │
│ [NIFTY 24318▲][VIX 12.97][PCR…]  │
├────────────────────────────────┤
│ [Underlying▾]  [Expiry▾]         │
│ [Live│Hist]          [⊞] [⟳ Go]  │  ← Columns = Sheet on mobile
├────────────────────────────────┤
│ per-strike CALL/PUT cards        │  ← md:hidden path kept
└────────────────────────────────┘
```

#### Section spec

- **A. Header (NEW).** A `<header>` with a muted breadcrumb (`Markets › Options ▸ Chain`, lucide `ChevronRight` `aria-hidden`), a **visible** `<h1 className="text-h1">Options Chain</h1>` (replaces the `ay-sr-only` one — still satisfies axe, strictly better), a `text-body-sm text-ay-muted` subtitle (`{chain.underlying} · {expiry} · black-76 live greeks`), a right-aligned `nums text-caption` as-of pill (`chain.asOf` → `HH:mm:ss`, lucide `Clock`), and a **FreshnessChip** promoting the buried `stale` flag: stale → lucide `AlertTriangle` + "Stale" `text-warn`; else lucide `Circle` (`fill-bull`) + "Live" `text-bull`.
- **B. Elevated metric strip.** Keep the same six metrics + the `aria-live="polite"` wrapper. Upgrade to `card` + `shadow-e1` with `divide-x divide-ay-border` cells on desktop / scroll-snap on mobile. Each metric is **two-line** — primary `text-h3 nums font-semibold` over a `text-caption text-ay-muted` secondary line that **surfaces** the data currently hidden in `title=` (VIX DH/DL/DO, prev-PCR placeholder, spot band, days). Genuinely-supplementary tooltips move to keyboard-accessible **shadcn Tooltip**. Keep `vixLabel`/`nearestStrike`/`daysToExpiry` helpers.
- **C. Filter bar.** One cohesive row on the shared shadcn Button/Select base behind the bridge. `FilterBar` props unchanged; native `<select>` → the **new** Radix Select atom (keeps `ariaLabel`). Right cluster: a **Columns** control (shadcn `DropdownMenu` desktop / `Sheet` mobile, lucide `Columns3` + "Columns", replacing `"Column Setting ▾"`) holding the 3 optional-column checkboxes (`OPTIONAL_COLUMN_META`, same `onToggle`) + a density toggle; and **Go** (shadcn primary Button, lucide `RefreshCw` spinning on `chainQ.isFetching`, label "Go" with icon `aria-hidden`, `onClick={() => chainQ.refetch()}` unchanged).
- **D. Chain grid.** Apply the **same TanStack Table v8 headless core** that backs `DataTable` to the bespoke mirrored grid (it can't consume the flat `DataColumn` API). Sticky strike pin (`pinnedStyle` from §3.2.2) + the existing sticky header. Click-to-sort on OI/ΔOI/IV/LTP/Volume per side with lucide chevrons; default sort = strike ascending; `aria-sort` on `<th>`. Density toggle (Comfortable `py-1.5` ↔ Compact `py-0.5`, compact = scalper default). All numeric columns get `nums` (`--font-mono`). Zebra + row-hover layered **under** the ATM warn-tint + max-cell rings (which keep priority via `color-mix`). Mobile cards kept (restyled). `role="region"` + `aria-label="Options chain"` wrapper **byte-identical**. (Flag the pre-existing mobile-card bug where the PUT column iterates `callColumns` — do NOT fix, surgical-change rule.)
- **E. States via QueryState.** Route `useChainTable()` through QueryState: **loading** → a shadcn-Skeleton chain (header + ~12 rows, sticky-strike skeleton); **empty** → keep exact copy "No chain — pick an underlying + expiry with a live option chain." in an `EmptyCard` (lucide `SearchX`); **error** → error card (lucide `AlertTriangle`) + Retry → `chainQ.refetch()` (genuinely new). `stale` stays a non-blocking header chip — stale data still renders the grid.
- **No chart card** — max-pain/sentiment/charts belong to sibling pages; the boundary is kept.

#### Motion

(1) LTP flash kept as-is (`.ay-pulse`); (2) metric-strip value cross-fade (~150ms `motion/react` `AnimatePresence` keyed on value under `aria-live`); (3) Columns/Density popover Radix open/close ≤150ms `--duration-fast` (Sheet slide ≤200ms). Not animated: sort re-order, sticky-strike scroll, Skeleton→grid swap.

#### Retention check

All hooks/props/data paths verbatim; visuals preserved, sticky-strike/sort/density/zebra additive; decimal discipline preserved (mono is a face swap). **Selectors at risk + mitigation:** keep the `role="region"`/`aria-label="Options chain"` wrapper exactly (TanStack drives only the inner `<table>`); visible H1 still satisfies `page-has-heading-one`; Radix Select keeps `ariaLabel` so `getByLabel('Underlying')` etc. stay green — **rephrase any Playwright `selectOption` to the combobox open+click pattern** (one verified behavioural change); Go keeps name "Go" (icon `aria-hidden`); the **`"Column Setting" → "Columns"` rename** lands with its e2e edit in the same PR; `stale` chip keeps the word "Stale" in accessible text. **Two intentional selector renames need a paired e2e edit** (Select interaction pattern; Columns label). One pre-existing mobile-card bug flagged, untouched.

### 4.3 FII/DII Capital Market (`/fii-dii/capital-market`)

This page is the proof-of-pattern for **metric strip + chart card + table-with-states** together.

#### Current read

Top→bottom: a `text-base` `<h1>` + a `text-xs` muted subtitle (the "In Market = FII Net + DII Net" formula is load-bearing); a single unframed `EChart` (`height={300}`, two `bar` series, per-bar sign colour, `dataZoom`) rendered **only when `asc.length > 0`**; then a `DataTable` (8 cols, `pageSize={25}`, read-only/no-sort, `ariaLabel`). **No metric strip** (the latest-day FII/DII/In-Market + cumulative flows are buried in row 1). The chart is a bare `<div>` floating on surface-0, raw-number tooltip, shadow crosshair. **No loading skeleton and no error surface** — `q.isLoading`/`q.isError` never read; a slow/failed fetch shows an empty table indistinguishable from a no-data day.

**Must retain:** the one `<h1>` text + the subtitle formula/legend; the 2-series bar chart with per-bar sign colour + `dataZoom` + `aria:{enabled:true}` + chart `ariaLabel="FII Net and DII Net cash flow bars per day"`; the 8 columns (exact order/headers) with `ValueDeltaCell` on the 3 net columns and `cr()` on buy/sell; **read-only (no sort)**; `pageSize={25}`; `rowKey={r=>r.tradeDate}`; `mobileLabel` on every column; `emptyMessage`; table `ariaLabel="Detailed FII/DII capital market activity"`; the `from`/`to` 420-day window; `useFiiDiiCash`/`foldFiiDiiCash`/`FiiDiiCashRow` fields consumed exactly. **Test surface = ARIA roles only** (no `data-testid`): `role="table"`, `columnheader` (header text), `role="img"` (chart), `role="region"` (table), the `<h1>` name — the sibling RTL spec asserts `getByRole('table')` + `getByRole('columnheader',{name})` + `getByText(date)`.

#### Wireframe — desktop (~1280px)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Markets › FII/DII › Capital Market          ⟳ as on 2026-06-23   [420d]    │
│ FII/DII Capital Market Activity                              <h1>          │
│ Values in ₹ Crore · In Market = FII Net + DII Net · green buy, red sell    │
├──────────────────────────────────────────────────────────────────────────┤
│ METRIC STRIP (elevated, shadow-e1)                                         │
│ [FII Net ↑+1,240.50][DII Net ↓−430.10][In Market ↑+810.40][FII Net 20dΣ]   │
├──────────────────────────────────────────────────────────────────────────┤
│ CHART CARD (card + shadow-e1)                                              │
│ Net cash flow per day            FII▮ DII▮                                  │
│ [ echarts bars (FII Net, DII Net) — per-bar sign colour, cross crosshair ] │
│ [════ dataZoom slider ════]                                                │
├──────────────────────────────────────────────────────────────────────────┤
│ TABLE CARD                                                                  │
│ Daily flows                                  [▢ density]   25 / page        │
│ Date(pinned) │FII Buy│FII Sell│FII Net│In Market│DII Net│DII Buy│DII Sell   │
│                                            1–25 of 287   ‹ Prev   Next ›     │
└──────────────────────────────────────────────────────────────────────────┘
```

#### Wireframe — mobile (~480px)

```
┌────────────────────────────────────┐
│ Markets › … › Capital Market    ⟳  │
│ FII/DII Capital Market Activity     │  ← <h1> text-h1
│ ₹ Crore · In Mkt = FII+DII Net      │
├────────────────────────────────────┤
│ metric strip: 2-col grid            │
│ [FII Net ↑+1,240][DII Net ↓−430]   │
│ [In Market ↑+810][FII 20dΣ −12,305]│
├────────────────────────────────────┤
│ chart card (height 220)             │
├────────────────────────────────────┤
│ flows → DataTable md:hidden cards   │
│   ‹ Prev   1–25 of 287   Next ›     │
└────────────────────────────────────┘
```

#### Section spec

- **A. Header.** Muted breadcrumb (lucide `ChevronRight`; last crumb only on mobile); keep the exact `<h1>` text, promote to `text-h1` (was `text-base`); keep the subtitle wording (`text-body-sm`/`text-caption`); right-aligned read-only "as on `{latestDate}`" stamp with lucide `RefreshCw` + a "420d" label. Do **not** add a working range Select (the hook window is hardcoded — unrequested scope; flag as a follow-up).
- **B. Elevated metric strip (NEW — the hero element).** A `flex flex-wrap` / mobile `grid grid-cols-2` of `MetricStat` cards derived from the folded `rows` (newest-first) — **no new query/field**: FII Net (`rows[0].fiiNet`), DII Net (`rows[0].diiNet`), In Market (`rows[0].inMarket`), FII Net 20-session Σ (exact-decimal sum of `rows[0..19].fiiNet`). Each `card` + `shadow-e1`, `text-caption` uppercase label, `text-h3 nums font-semibold` value with a lucide `TrendingUp`/`TrendingDown` + an explicit sign (never colour-only), tone `text-bull/bear/ay-text`, a `text-caption` sub-line ("₹ Cr · {latestDate}" / "trailing 20 ses"). Uses `isNegative`/`compareDecimal`/`formatDecimal`. Renders only when `rows.length > 0` (else QueryState owns the state).
- **C. Filter bar — NONE today** (fixed window, no symbol context). Do not invent filters. The read-only range label in (A) is the only control. A future range picker is a spawn-worthy follow-up (shadcn Button/Select, lucide `Calendar`, accessible name).
- **D. Chart card.** Wrap the existing `EChart` in a `card` + `shadow-e1` ChartCard with a header ("Net cash flow per day" + FII/DII swatches; drop echarts' own `legend` into the card header to densify the canvas). `makeOption` polish inside the existing `(t) =>` closure — **keep `aria:{enabled:true}` and the per-bar sign colouring verbatim**: switch `axisPointer` to `{type:'cross', lineStyle:{color: t.crosshair}}`; add a ₹-formatting `valueFormatter` (`+sign`, `toLocaleString('en-IN')`, `Cr` suffix). Keep `grid`/`dataZoom`/`yAxis.name:'₹ Cr'`/date categories; `height={300}` desktop, `220` mobile; **keep the chart `ariaLabel`** (`role="img"` name).
- **E. Table card (DataTable upgraded).** Same `columns`/`rows`/`rowKey`/`pageSize`/`ariaLabel`/`emptyMessage` props; internals → TanStack. Card header "Daily flows" + a density toggle (lucide `Rows3`/`Rows2`, icon-only **with `aria-label="Toggle row density"`**). **Date column pinned sticky-left**; the 3 net columns keep `ValueDeltaCell` + a subtle `font-medium`. **Keep read-only** (no `sortValue` → no sort chrome renders; the lucide-chevron glyph-replacement applies only to sortable columns, so zero visual change here). Zebra + row-hover; mobile card list kept (all 8 `mobileLabel`s).
- **F. States via QueryState.** Wrap the data region (strip + chart + table): **loading** → shadcn Skeleton (4 metric tiles + chart-height block + ~6 row bars); **empty** → `EmptyCard` reusing the `emptyMessage` string + lucide `Inbox`; **error** → error card + Retry → `q.refetch()` (new). Inner `DataTable.emptyMessage` stays as a belt-and-suspenders fallback.

#### Motion

(1) Metric-strip stagger-in (`opacity 0→1, y 6px→0`, `0.16s`, `staggerChildren 0.04s`) on first resolve; (2) latest-value flash reusing `.ay-pulse` when `rows[0]` changes; (3) row-hover tint as CSS `transition-colors duration-150`. No chart-wrapper entrance motion (fights ResizeObserver). All reduced-motion-gated.

#### Retention check

`<h1>` text + single-h1 invariant (only the type token changes); subtitle/legend wording unchanged; chart edits additive (crosshair, formatter) with `aria` + sign-colour untouched; chart `role="img"` name unchanged; 8 columns/order/headers identical (TanStack stays a semantic `<table>` with `<th scope="col">` — **do NOT switch to `role="grid"`/div-grid**, or `getByRole('columnheader',{name})` breaks); read-only preserved; `pageSize={25}`/Prev/Next/`rowKey` preserved; `mobileLabel` cards intact; `emptyMessage`/region `aria-label` kept (QueryState wraps but doesn't remove them); `useFiiDiiCash` window + 422→empty unchanged. **Selectors at risk:** `role="table"`/`columnheader`/`getByText(date)` survive iff the TanStack swap keeps a native `<table>`; `role="region"`/`role="img"` survive because the new wrappers are presentational `<div>`s (the `aria-label`-bearing nodes pass through); new icon-only controls (density/retry/refresh) each carry an explicit `aria-label`, decorative glyphs `aria-hidden`, no bare `focus:outline-none`. No `data-testid` exists today, so none can break.

---

## 5. Implementation phasing and acceptance criteria

Five phases. Each phase's done-criteria include the standing gates: **axe clean** (no violations on touched routes), **Playwright role/name green**, **5-theme switch verified in a prod build** (dark/light/oipulse-red/midnight-blue/high-contrast — hard-reload after rebuild to avoid stale chunks), **480px (S24 Ultra) layout green**, **no `tailwind.config.js`**, **fonts self-hosted (no CDN request)**, and **functional parity** (every retained hook/prop/selector intact). Verify trio per phase (PowerShell `Push-Location frontend-react`): `npm run lint` + `npm run test:ci` + `npm run build`.

### Phase 1 — Foundation tokens + fonts (no behaviour change)
**Do:** §0 deps + §1 (`@fontsource-variable/*` import in `main.tsx`, the merged `@theme inline`, per-theme `--ay-shadow-*`/`--ay-focus`, `shadow-e*` utilities, `@utility` ramp/`nums`/`page`/`card`, global `:focus-visible`, blanket reduced-motion). **Done when:** build green; fonts load from `@fontsource` (Network shows no `fonts.googleapis.com`); the global focus ring is visible on Tab in all 5 themes (white in high-contrast); no visual regression beyond intended (Inter now actually renders); zero `tailwind.config.js`; existing specs unchanged and green.

### Phase 2 — shadcn bridge + 10 components (no app wiring yet)
**Do:** §2 (`components.json`, init, **delete CLI colour/`.dark`/variant blocks**, paste the bridge, `add` the 10 components, Sonner CSS-var bridge). **Done when:** grep of `src/components/ui/*` finds **no** raw `oklch(`/`hsl(`/hex; a throwaway page mounting each component re-themes across all 5 themes; Dialog/Sheet/Dropdown icon-only triggers have accessible names; build + lint green; no `tailwind.config.js` created.

### Phase 3 — Button + Select atoms + QueryState/Skeleton/Sonner
**Do:** §3.1 `Button.tsx` + `GoButton` wrapper + page-by-page bordered/ghost native migration (glyph-string specs updated in lockstep); §3.1.4 Radix Select as a **new** atom (migrate Theme picker + StrategyBuilder only); §3.3 `QueryState` + `Skeleton` variants + `<Toaster>` + `QueryClient` `onError` revival. **Done when:** `getByRole('button',{name:'Go'})` stable under loading; the three at-risk Select tests untouched (FilterBar/BacktestRunner still native); a forced 500 shows the inline Retry card **and** a toast, a 422 shows the empty card and **no** toast (`qs-error`/`qs-empty` distinguish them); skeletons honour reduced-motion; all gates green.

### Phase 4 — DataTable TanStack swap (behind the frozen API)
**Do:** §3.2 (`columnAdapter.ts`, TanStack core under `DataTable.tsx`, `aria-label` on both region + `<table>`, lucide chevrons, opt-in features; chain/OI adopt only `pinnedStyle`/virtualizer/density). **Done when:** all `DataTable.spec.tsx` cases pass **unmodified** (header names, desc-then-asc, decimal sort, `aria-sort` present/absent, pagination text/bounds, `cellClassName` on `td`, empty ≥1, `mobileLabel` count); `data-ops`/`scalper-checklist` e2e green; all ~20 call sites compile with zero edits; 5-theme + 480px green.

### Phase 5 — Hero pages (Dashboard, Options Chain, FII/DII)
**Do:** §4.1–§4.3 — header/strip/KPI/sections + QueryState wiring + motion, one page at a time (each its own branch/commit). **Done when:** every asserted string survives (`OPEN`/`1 running · 2 queued`/`RELIANCE`/`12500.00`/`backtest`; chain `role="region"` name; FII/DII `columnheader` names + `getByText(date)`); realized P&L renders in exactly one DOM node; the two Options-Chain selector renames land with paired e2e edits; visible H1 on each page satisfies `page-has-heading-one`; motion ≤200ms and reduced-motion-gated; 5-theme + 480px + axe green.

---

## 6. Guardrails and risks

### Locked guardrails (recap)
- **Theme through `--ay-*` only** — no hardcoded hex, no shadcn default colours. Every new value resolves to `--ay-accent/border/surface-*/bull/bear/warn/text*` or a neutral black/white alpha in a shadow recipe.
- **a11y green** — global `:focus-visible` ring everywhere (never bare `focus:outline-none`); icon-only buttons carry an accessible name (type-enforced for `variant="icon"`); decorative lucide icons `aria-hidden`; one visible `<h1>` per page; new `role="progressbar"`/`role="alert"` are net gains; preserve every `data-testid` + `ariaLabel` selector.
- **CSS-first** — reject any `tailwind.config.js`; all tokens via `@theme inline`/`@theme`/`@utility`/`@media` in `src/index.css`.
- **Self-hosted fonts** — `@fontsource-variable/*` only, no Google CDN (offline box).
- **Licences** — Inter + JetBrains Mono OFL, lucide ISC, shadcn/Tailwind/Sonner/TanStack MIT, Radix MIT — all import-safe; AGPL = appliance-only (none here).
- **Motion** — every animation <200ms, `prefers-reduced-motion`-gated.

### Risks surfaced by the specs
1. **Radix Select breaks 3 native-control tests** (`option`-locator, `selectOption`, `fireEvent.change`). Mitigation: Radix ships as a **new** atom; the shared `Select` stays native for FilterBar + BacktestRunner until their specs are rewritten as a separate task. The shared-atom swap is all-or-nothing — do not mutate it in place.
2. **Glyph removal renames accessible names** (`'All Menu ▾'`→`'All Menu'`, `'Column Setting ▾'`→`'Columns'`, pager `'Next ›'`→`'Next'`). Mitigation: grep `name: '.*[›‹▾▸▴●…]'` and update each selector in the same PR as the glyph removal.
3. **Options-Chain `selectOption` → combobox interaction** is the one verified behavioural e2e change when FilterBar eventually moves to Radix — scope it explicitly, don't fold it into a styling PR.
4. **`getByText` duplicate-match on Dashboard** if realized P&L renders in both the KPI strip and the card. Mitigation: render it in exactly one node (KPI strip); drop the old card block.
5. **TanStack DataTable must keep a native `<table>`** — switching to `role="grid"`/div-grid breaks `getByRole('table'|'columnheader')` across ~20 pages + the FII/DII RTL spec. Mitigation: headless TanStack with hand-owned semantic markup; verify the shared spec stays green.
6. **Virtualization vs row-count assertions** — a virtualized `<tbody>` drops rows from the DOM. Mitigation: opt-in only, kept off for any audited/paged table; add `aria-rowcount`/`aria-rowindex` when used.
7. **Pre-existing OptionsChainTable mobile-card bug** (PUT column iterates `callColumns`) — flagged, left untouched per surgical-change; fix is a separate task.
8. **Stale cached chunk after rebuild** renders the old UI / white charts — hard-reload after every prod rebuild during theme verification.
9. **Radius token collision** between Foundation `--radius-md` (6px) and a shadcn-derived `calc()` — resolved in §1.1/§2.3 (Foundation owns the scale; bridge keeps only the `--radius` literal). Watch for any copied component referencing `rounded-xl` (add `--radius-xl` then, not speculatively).

---

## 7. Open questions for the owner

1. **Mono `nums` utility name.** `nums` (combined mono + tabular) is the smallest-diff choice but collides conceptually with Tailwind's built-in `tabular-nums`. Accept `nums`, or prefer `font-num`, or keep `tabular-nums` and add only the mono family via a separate utility? (Affects every numeric cell call site.)
2. **Self-host the mono font at all?** JetBrains Mono Variable adds a WOFF2 payload purely for digit-column alignment. Confirm we want true mono numerics (recommended for a dense trading grid) vs. `tabular-nums` on Inter alone (lighter, slightly less aligned). — **RESOLVED: yes** (taste pass — tabular alignment is non-negotiable on a trading grid; cheap offline WOFF2).
3. **Motion default — on or off?** Stagger-in / value cross-fade / progress tween are all <200ms and reduced-motion-gated. — **RESOLVED: ON by default** (owner, 2026-06-24). Delivered as the §4.0 single orchestrated load beat.
4. **Density default.** Chain grid proposes **Compact** as the scalper default; generic DataTable keeps **Comfortable** (today's spacing). — **RESOLVED: Compact-by-default on the chain, Comfortable elsewhere** (taste pass — calm header / dense grid contrast is the intended "instrument" feel).
5. **Options-Chain breadcrumb.** Add the `Markets › Options ▸ Chain` breadcrumb (extra chrome) or keep the page lean with just the H1 + subtitle? (Same question for FII/DII.) — **RESOLVED: drop on desktop; last-crumb-on-mobile only** (taste pass §4.0 — distil chrome so the display-face title + metric strip carry the weight).
6. **Range picker on FII/DII.** The 420-day window is hardcoded. Leave the read-only "420d" label, or is a working history-window Select wanted (new query-param scope)?
7. **High-contrast focus colour.** Confirmed `#ffffff` (≥7:1). Acceptable, or should it tint toward `--ay-accent` for brand consistency at the cost of contrast headroom?
8. **shadcn `style`.** `new-york` (denser) chosen for the data-dense UI — confirm over `default`.
9. **Selector-rename PRs.** Confirm the two Options-Chain renames (Select interaction pattern; `"Column Setting"`→`"Columns"`) land **with** their e2e edits in the same PR (vs. a staged toggle), per the all-or-nothing constraint.

### Taste-pass additions (frontend-design + impeccable, 2026-06-24)

10. **Display face (`--font-display`).** — **RESOLVED: Newsreader (serif)** (owner, 2026-06-24). `--font-display: 'Newsreader', Georgia, serif`, self-hosted via `@fontsource/newsreader`, applied to `text-display`/`text-h1` (≥22px word-titles) ONLY. Inter (`--font-sans`) + JetBrains Mono (`--font-mono`) stay for all data/controls/grids. Display wraps **titles only** — the big KPI *numbers* stay mono (alignment + `tnum`).
11. **The one signature.** — **RESOLVED: approved, lockup + radial mesh** (owner, 2026-06-24). Repeated header lockup (display-face `<h1>` + 2px `--ay-accent` left-rule + live-state dot) is the single brand gesture; the token-built radial-mesh atmosphere is confined to zero-data canvases (login / standalone EmptyCard / Dashboard cold-load), never behind a grid.
