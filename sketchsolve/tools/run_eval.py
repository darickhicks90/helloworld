"""Runs Gemma (LiteRT-LM, CPU) on the exported word-problem prompts.

usage: python run_eval.py MODEL.litertlm build/eval/prompts.jsonl build/eval/outputs.jsonl [readings]
"""
import json, sys, time
import litert_lm as lm

model, prompts, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
readings = int(sys.argv[4]) if len(sys.argv) > 4 else 2
done = set()
try:
    for l in open(out_path):
        o = json.loads(l); done.add((o["id"], o["reading"]))
except FileNotFoundError:
    pass

engine = lm.Engine(model, backend=lm.Backend.CPU(), max_num_tokens=4096)
cd = lm.ConstrainedDecodingConfig(enable=True, provider=lm.LiteRtLmConstraintProviderType.LL_GUIDANCE)
with open(out_path, "a") as out:
    for line in open(prompts):
        p = json.loads(line)
        fmt = lm.ResponseFormat.json(p["schema"])
        for r in range(readings):
            if (p["id"], r) in done:
                continue
            t0 = time.time()
            conv = engine.create_conversation(
                sampler_config=lm.SamplerConfig(top_k=40, top_p=0.95, temperature=0.0 if r == 0 else 0.6, seed=1234 + r),
                constrained_decoding_config=cd, max_output_tokens=900)
            msg = conv.send_message(p["prompt"], response_format=fmt)
            text = "".join(str(c) for c in msg.contents)
            conv.close()
            out.write(json.dumps({"id": p["id"], "reading": r, "text": text, "seconds": round(time.time() - t0, 1)}) + "\n")
            out.flush()
            print(p["id"], r, round(time.time() - t0, 1), "s", text[:120].replace("\n", " "), flush=True)
