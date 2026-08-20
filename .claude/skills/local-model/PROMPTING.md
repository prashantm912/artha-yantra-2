# Prompting local models — the mistakes we already made

Read this before writing any prompt for a local model, and before recording any local-model
capability claim.

## The one thing to internalise

On 2026-08-15, **three capability verdicts flipped on a prompt change** with the model, the data and
the temperature all held fixed. Two of the wrong verdicts reached merged ledger entries before the
owner's questions caught them.

| verdict | terse / unfair prompt | fair prompt | what actually changed |
|---|---|---|---|
| code review, real diff | **0 / 2** | **1 / 2, real defect at RANK 1** | asked for *every candidate considered* with KEPT/DISCARDED verdicts, instead of *"list only real defects, max 6 sentences"* |
| psql triage, `qwen3.5:9b` | **0 / 5** | **5 / 5** | supplied the operator rule the grader was privately using |
| 7B on a known defect | looked like a **PASS** | **0 / 2** | evidence had been pre-assembled adjacent in one prompt (leading) |

**We were measuring the probes, not the models.** Every number in the lane table is therefore
*"this model, under that prompt"* — never a property of the model.

## The four rules

### 1. Before recording a FAILURE, re-read your own prompt
If the answer you wanted requires knowledge that is not in the prompt and not in the data, **the
probe is broken, not the model.**

The case that taught this: a psql dump where the load-bearing row was
`NOTIFIER_HEALTH | DONE | BOOT_CATCHUP`. It sat **alone in its own single-row result block** — no
column of `SCHEDULED` rows for `BOOT_CATCHUP` to stand out against. Flagging it needs domain
knowledge nowhere in the dump, and the prompt also said *"Do not speculate."* Five runs were scored
as failures for not knowing something they were never told. With the rule supplied, the same model
scores 5/5 and explains it correctly.

⚠️ This fails in the **alarming** direction, which is why it survives review: nobody argues with a
negative result. Same family as `filter-artifacts-look-like-outages`.

### 2. State the rubric IN the prompt when it depends on domain knowledge
A rubric the grader holds privately is not a test — it is a guess about what the model will
volunteer. If you will score "did it flag X", the prompt must contain the general rule that makes X
flaggable.

State the rule **generally**, never as a pointer at the answer:
- ✅ *"`SCHEDULED` means the cron fired on time; any other source means the scheduled run did not
  happen and something recovered it after the fact."*
- ❌ *"Note the NOTIFIER_HEALTH row."*

⚠️ And check your general rule for false positives before you blame the model for them. The rule
above wrongly implicates `MINERVINI_PLANE_DIVERGENCE`, whose `MINERVINI_SCHEDULER` source IS its
normal trigger — it produced a false positive in 4/5 runs, and that is the rule's fault.

### 3. Fair ≠ minimal — both directions produce confident wrong numbers
- **Over-supplying** (leading): pre-assembling the evidence made a 7B look like it found a defect it
  cannot find. Hand over evidence **unassembled**, as a real reader meets it.
- **Under-supplying**: withholding the domain rule made a capable model look incapable.

Fair means: the context a competent operator would already have, and nothing about *this particular*
answer.

### 4. A capability claim without its prompt attached is not a claim
Store the probe file next to the result. When you quote a number later, you are quoting the pair.

## Task-shape rule — what is stable vs what is not

**Stable where the document already MARKS what matters. Unstable where the model must DECIDE what
matters.**

- CI/surefire output carries `Run 1/2/3` markers and a totals line → the answer is near-extractive →
  **5/5 on both models**.
- A service log is ~200 undifferentiated lines where the model must choose which six matter → it
  chose differently each run → **3/5 on the 9b** (dropped the batch summary twice).

If a new task is the second kind, **mark the structure yourself in the prompt** — and this is
measured, not advice: naming the six CATEGORIES an operator wants (service lifecycle / load health /
actions taken / risk refusals / warnings-aggregated / batch tally) took the 9b's service-log score
from **3/5 to 5/5**, with all six categories present in every run. Naming the categories is fair;
naming the values would be leading.

⚠️ **But structure-marking fixes COVERAGE, not COMPREHENSION.** In the same 5 runs the 9b still
misread `would-enter 5` as "5 blocked" — and on inspection **the probe file's own log line is
truncated mid-word** (`(would-enter 5, admit`), so no reader could have got it right. That was the
FOURTH probe defect found in one day. Before blaming a model for a wrong number, check the number is
actually present and intact in what you handed it.

This supersedes the earlier "structure vs needle" framing, which did not survive measurement.

## Prompt patterns that are measured to work

**Digest a log** — cap the output, demand aggregation, forbid speculation, and supply any domain
rule you intend to grade:
```
Below is <what it is>. In at most N bullets, state what an operator needs to know.
Aggregate repetitive warnings rather than listing them. Flag anything unusual. Do not speculate.

Context an operator has (general rules, not hints about this data):
- <rule 1>
- <rule 2>
```

**Generate defect candidates** — the no-discard shape. The discard step was the defect, not the
perception: the model *found* the real bug under the terse prompt and then talked itself out of it.
```
List EVERY candidate defect you considered, numbered. For each: (a) the exact code, (b) a concrete
failure scenario, (c) verdict KEPT or DISCARDED, (d) a one-line reason.
A candidate you are UNSURE about MUST still appear, with your doubt stated. Do not silently drop
anything you noticed. Do not repeat a candidate already listed.
```

**Never** ask a local model for a verdict, a severity ranking you will act on, or "is this correct?".
It will answer confidently either way, and it is 0/2 across seven models at that job.

## Parameters

- `"think": false` — mandatory on `qwen3.x`. Thinking-on produced **zero answer tokens** in 4/4 runs.
- `temperature: 0.2` for anything factual.
- `num_ctx`: 8k keeps the 9b fully on GPU; 16k drops it to 86%; 32k pushes q3.8 to 72% CPU.
- `num_predict` caps **total** output — a truncated answer looks complete, so compare `eval_count`
  against the cap.
- Tuning advice that does **not** work, tested: temperature/presence-penalty tuning does not fix
  convergence; structural micro-headers do not bound the chain; `reasoning_budget` does not exist in
  ollama.
