---
name: local-model
description: Use when digesting a large log / CI failure / psql dump before it enters context, drafting SQL or a commit message, summarizing a long doc, or generating defect CANDIDATES before a review round — the two local ollama models, what each is measured to do, and the gates that make their output safe to use. Also read before adding any new local-model use.
---

# Local models — measured lanes and mandatory gates

Two models run on this laptop. They exist for **one reason: token burn.** They are not faster in
wall-clock terms for anything interactive-sized, and they never make a decision.

⚠️ **Before writing any new prompt for these models, read [PROMPTING.md](PROMPTING.md).** On
2026-08-15 three separate capability verdicts flipped on a prompt change alone, with model, data and
temperature held fixed. Two wrong verdicts reached merged ledger entries before the owner caught
them. That file is the whole reason this skill exists rather than a one-line note.

## The two models

| | `qwen3.5:9b` | `qwen3.8:27b-q4_K_M` |
|---|---|---|
| size / speed | 6.6 GB · **43 tok/s** · 86% GPU @16k | 17 GB · **2.6 tok/s** · 28% GPU (dense, offloaded) |
| lane | **interactive** — answers in seconds | **unattended** — minutes; queue it, do something else |
| never | writes code. Ever. | verdicts anything |

Binary: `C:\Users\prash\AppData\Local\Programs\Ollama\ollama.exe`. PowerShell needs the call
operator (`& "path\ollama.exe" …`); a bare quoted path is a parse error. Models unload after ~5 min
idle and pay a reload on the next call.

## Lanes — what each is MEASURED to do (×5 runs per probe, fair prompts)

| task | 9b | q3.8 | use it? |
|---|---|---|---|
| **CI failure log** → summary | **5/5** | **5/5** | ✅ either. Biggest token win; surefire output is near-extractive |
| **psql operational dump** | **5/5** | 5/5 (3/5 terse) | ✅ either — **but only with the domain rule stated in the prompt**, see PROMPTING.md |
| **service log (docker logs)** | **3/5** | **5/5** | ⚠️ q3.8 only. The 9b dropped the batch summary twice and misread a real number once |
| **doc summarization** | ~6/8 | **7/8, zero fabrication** | ✅ q3.8 |
| **SQL drafting** | partial (ignored format) | **exact rows, dodged the IST trap** | ✅ q3.8, then RUN IT and diff the rows |
| **commit-message draft** | ✅ most specific of 3 | ✅ | ✅ either — you read the diff anyway |
| **prod code from a tight spec** | ✗ infinite loop, JVM hang | **8/8 hidden tests** | ⚠️ q3.8, non-money surfaces only, hidden tests written FIRST |
| **defect CANDIDATES before review** | — | **real defect at rank 1** | ✅ q3.8 — generator only, see below |
| **code review VERDICT** | **0/2** | **0/2** | ❌ NEVER. 7 models, 0/2 every time |
| **money / parity / exit-doctrine / migration / live-engine code** | ❌ | ❌ | ❌ NEVER, at any size |

## The gates — non-negotiable

1. **A local model never merges, deploys, verdicts, or decides.** It produces text a human-grade
   model then judges. No exceptions, including "it's obviously right".
2. **Any generated TEST gets the red-proof gate before it counts.** Measured: a local model emitted
   a 4/4-GREEN suite that detected neither of two planted bugs. A green suite from a local model is
   uninformative by construction — only a red-proof distinguishes a real test from ceremony.
3. **Any generated SQL gets RUN and its rows diffed** against a known-good query. Cheap, total.
4. **Never quote a local summary as evidence in a ledger entry or a PR body.** Use it to decide what
   to read; cite the raw source. It compresses well and drops the occasional load-bearing line.
5. **`"think": false` is mandatory** on `qwen3.x`. With thinking on, four separate runs produced
   **zero answer tokens**. `think:"low"` is accepted by the API and IGNORED by the model.
6. **One run is not a measurement.** Every capability claim in the table above is ×5. A single pass
   or fail tells you nothing — that error has bitten this repo in both directions in one day.

## Calling them

`scripts/run.py <model> <prompt-file> <out-file> [num_predict] [think]` — one-shot, records
elapsed/tokens/throughput into `results.csv` next to the output.

```bash
python .claude/skills/local-model/scripts/run.py qwen3.5:9b /tmp/ci.txt /tmp/out.txt 1500 false
```

`scripts/candgen.py <context-file> <diff-file> <out-file>` — the candidate generator (q3.8 +
no-discard prompt). Feeds a review round; never replaces one.

**Cheapest possible call — prefill-after-`<think>`.** Measured 261 eval tokens / 124 s → **31 tokens
/ 16 s, same answer**. Costs the server-side chat template, so it must be maintained per model
family; `run.py` does not use it by default. Shape:

```
{"raw": true, "prompt": "<|im_start|>user\n…<|im_end|>\n<|im_start|>assistant\n<think>\nNo extended reasoning needed.\n</think>\n"}
```

## Where these sit in the workflow

Read `.claude/skills/codex/ROUTING.md` for the authoritative table. In short: local models sit
**before** the human-grade stages, never inside or after them.

- **Digest → then work.** A 8.4k-token log becomes ~320 tokens; you then read the raw lines the
  summary points at. This is the ~96% context reduction and the entire point.
- **Candidates → then review.** `candgen.py` on the diff, dedupe, hand the survivors to the review
  round as leads. It found a real defect at rank 1 once; it also duplicated itself heavily and found
  nothing the builder had not already flagged. Treat it as a confirmation net.
- ⚠️ **It does NOT restore cross-vendor review.** Codex is rationed (owner, 2026-08-15) to money/
  parity/migration/live-engine slots; every other review is same-vendor. A local model
  scoring 0/2 as a reviewer cannot substitute for a second vendor's judgement — see ROUTING.md's
  honest statement of that loss.

## Known failure modes, measured

- **Template degeneration** on long outputs: late candidates become verbatim repeats of earlier ones
  (16–20 repeated 11–15 in one run). Dedupe before reading; do not count repeats as agreement.
- **`num_predict` caps TOTAL output**, so a truncated answer looks like a complete one. Check
  `eval_count` against the cap — equal means truncated.
- **The 9b fails differently every time.** Three attempts at one test-authoring task produced three
  distinct failures (impossible assertions / truncation / `Thread.sleep(301*1000)` in a unit test).
  Instability is its signature; do not infer a pattern from one bad run.
- **No coding-specialised variant runs on Windows** — every `*-coding-*` qwen3.5/3.6 tag is
  macOS-gated (`412: this model requires macOS`). Not a hardware limit; an OS gate in the registry.
- **`ollama pull` pre-allocates blobs to full size**, so `ls -l` shows 100% while still downloading.
  Real progress is `fsutil sparse queryrange <blob>-partial`. Misreading this killed three healthy
  downloads.

## Adding a new lane

Do not add one from a single impressive answer. The procedure that works:

1. Write the rubric **in a file, before generating anything**, and state in it what a PASS requires.
2. Check the prompt supplies everything the rubric grades. If the rubric needs domain knowledge, the
   prompt must contain it — see PROMPTING.md rule 2.
3. Run ×5. Score against the written rubric.
4. ≥4/5 → the lane is usable **with** the gates above. 3/5 → index only. ≤2/5 → no.
5. Record the result **with its probe file** in memory topic `local-model-evaluation`.
