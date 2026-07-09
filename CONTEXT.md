# CONTEXT — domain & design glossary

A glossary only: what the core concepts *are*, devoid of implementation detail. The `docs/design/`
stage files + ADRs remain the authority; this names the seams so architecture reviews share language.

## Trading domain

- **Book** — a paper-trading account with its own capital, risk limits and open positions. The live
  system runs several (`scalper`, `minervini`, `manas-arora`); a signal is attributed to exactly one.
- **Strategy** — a published, versioned config that emits signals. Immutable per version; the live
  engine loads the `enabled` strategies whose published version resolves.
- **Signal** — a fired ENTRY or EXIT row (gate passed AND composite ≥ threshold). Anchors a paper
  position via auto-paper.
- **Anchor** — an open ENTRY signal that a position is held against; the exit pass manages it to close.

## Swing sub-domain (daily-bar equity holds)

- **Swing family** — a doctrine that trades daily-bar equity swings off a selection funnel and holds
  across sessions (currently **Minervini SEPA** and **Manas Arora**). Distinct from the tick-driven
  scalper path — funnel equities do not tick, so a family runs as a post-close batch, not on ticks.
- **Swing batch** — the post-close daily run for one family: an *entry pass* over the family's funnel
  candidates, then an *exit pass* over its open holdings (the batch is a holding's ONLY exit evaluator).
- **Swing doctrine** — the family-specific rules a swing batch varies by, and nothing more: the book,
  the funnel universe, how a candidate seeds indicator context, the detail side-channel record, which
  strategies are eligible for which candidate (setup routing), and the pyramid policy. The shared batch
  engine is a pure function of the doctrine.
- **Swing candidate** — a family-neutral funnel candidate handed from the doctrine to the batch engine:
  a symbol plus the indicator-context seeds it should be scored under. The engine never sees a family's
  own candidate type.
- **Pyramid policy** — a family's rule for adding lots to a winning holding (Manas Arora §3.4: add on a
  fresh move if aggregate open risk stays capped). "None" = single-lot; the batch engine speaks
  multi-lot natively and treats a single-lot family as the degenerate case.
- **Selection funnel** — the day's ranked candidate triad (immediately-buyable / on-deck / watch) a
  family's screener produces; the swing batch enters off buyable + on-deck.
