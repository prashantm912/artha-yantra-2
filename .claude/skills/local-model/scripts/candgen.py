"""Local candidate generator: qwen3.8 + the no-discard prompt.

Measured 2026-08-15: under the terse "list only real defects" prompt this model scored
0/2 on a known-defect diff. Under the no-discard prompt below it produced the real defect
as candidate #1, KEPT. It is a CANDIDATE GENERATOR, not a reviewer -- every candidate is
judged by Claude. Known failure mode: template degeneration (late candidates become verbatim
repeats of earlier ones), so dedupe before reading.

Usage: python candgen.py <context_file> <diff_file> <out_file>
"""
import io, json, os, sys, time, urllib.request

MODEL = "qwen3.8:27b-q4_K_M"

TASK = """TASK: list EVERY candidate defect you considered while reading this change, as a numbered list.
For each candidate give: (a) the exact code it concerns, (b) the concrete failure scenario -- specific
inputs or state leading to a specific wrong outcome, (c) your verdict KEPT or DISCARDED, and (d) a
one-line reason for the verdict.

Rules:
- A candidate you are UNSURE about MUST still appear, with your doubt stated. Do not silently drop
  anything you noticed. The discarded ones are as useful to me as the kept ones.
- Do not repeat a candidate you have already listed. If you have no more distinct candidates, stop.
- Do not report style, logging, formatting, or hypothetical NPEs on constructor-injected final fields.
- The list is the entire output. No prose before or after it."""


def post(body):
    req = urllib.request.Request("http://127.0.0.1:11434/api/generate",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=7200).read())


def main():
    context = io.open(sys.argv[1], encoding="utf-8").read()
    diff = io.open(sys.argv[2], encoding="utf-8").read()
    out_path = sys.argv[3]

    prompt = context + "\n\n=== THE CHANGE UNDER REVIEW ===\n" + diff + "\n\n" + TASK
    body = {"model": MODEL, "prompt": prompt, "stream": False, "think": False,
            "options": {"temperature": 0.2, "num_ctx": 32768, "num_predict": 4000}}

    t = time.time()
    d = post(body)
    el = time.time() - t

    with io.open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("MODEL=%s ELAPSED=%.1fs PROMPT_TOK=%s EVAL_TOK=%s\n=====\n"
                % (MODEL, el, d.get("prompt_eval_count"), d.get("eval_count")))
        f.write(d.get("response", ""))
    print("OK %s  %.1fs  prompt=%s eval=%s"
          % (os.path.basename(out_path), el, d.get("prompt_eval_count"), d.get("eval_count")))


if __name__ == "__main__":
    main()
