import io, json, os, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def post(body):
    req = urllib.request.Request("http://127.0.0.1:11434/api/generate",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=3600).read())


def run(model, probefile, outfile, num_predict=3000, think=None):
    prompt = io.open(probefile, encoding='utf-8').read()
    body = {"model": model, "prompt": prompt, "stream": False,
            "options": {"temperature": 0.2, "num_ctx": 16384, "num_predict": int(num_predict)}}
    if think is not None:
        body["think"] = think
    t = time.time()
    try:
        d = post(body)
    except urllib.error.HTTPError as e:
        msg = e.read().decode(errors='replace')
        if think is not None and 'think' in msg.lower():
            body.pop("think")
            t = time.time()
            d = post(body)
        else:
            raise RuntimeError(msg)
    el = time.time() - t
    ec = d.get("eval_count", 0)
    ts = ec / max(d.get("eval_duration", 1) / 1e9, 1e-9)
    with io.open(outfile, 'w', encoding='utf-8', newline='\n') as out:
        out.write("MODEL=%s PROBE=%s ELAPSED=%.1fs PROMPT_TOK=%s EVAL_TOK=%s TOKS=%.1f\n=====\n"
                  % (model, os.path.basename(probefile), el,
                     d.get("prompt_eval_count"), ec, ts))
        out.write(d.get("response", ""))
    with io.open(os.path.join(HERE, 'results.csv'), 'a', encoding='utf-8') as csv:
        csv.write("%s,%s,%.1f,%s,%s,%.1f\n" % (model, os.path.basename(outfile), el,
                                               d.get("prompt_eval_count"), ec, ts))
    print("OK %s %s %.1fs %.1f tok/s" % (model, os.path.basename(outfile), el, ts))


if __name__ == '__main__':
    m, pf, of = sys.argv[1], sys.argv[2], sys.argv[3]
    np = sys.argv[4] if len(sys.argv) > 4 else 3000
    tk = sys.argv[5] if len(sys.argv) > 5 else None
    tk = {"false": False, "true": True, None: None, "none": None}[tk]
    run(m, pf, of, np, tk)
