# Update Logs — `/app/website-update-logs`

**Purpose:** product changelog / release history. Vertical timeline of dated changes.
Sub-tabs: `Website update logs | Info`.

## Layout
```
sub-tabs: [ Website update logs ] [ Info ]   ;  ticker strip
 Update logs   (title)
 │ 13-09-2024  [Release]  Added Equity returns
 │ 22-08-2024  [Release]  Added Open & High/Low for equity
 │ 13-07-2024  [Release]  Added FII Long short ratio feature
 │ 05-06-2024  [Release]  1Cliq - SS Corporate broker added
 │             [Release]  1Cliq - SW Capital broker added
 │ 04-06-2024  [Update]   1Cliq - LTP price range added in 1Cliq trading window
 │ …
 (vertical timeline line with a node per date)
```

## Components
| Component | Type | Contents | Visual |
|---|---|---|---|
| Timeline | vertical line + nodes | one node per date (descending, newest first) | thin white vertical rail, dot per entry |
| Date | text (left) | `DD-MM-YYYY` | |
| Type badge | badge | `Release` / `Update` | **green** `Release` (`badge-success`), **orange** `Update` (`badge-warning`) |
| Description | text | change summary | one line per change; a date can have multiple stacked entries |

## Data source
No `api.oipulse.com` call observed — changelog is **static** (hardcoded/bundled content).

## Replication notes (→ ArthaYantra)
- Low priority. If kept: a simple static timeline component (date + Release/Update tag + text),
  newest first. No backend.

## Screenshot
ss_8162g5ixf (release/update timeline).
