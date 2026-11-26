"""Runs Gemma (LiteRT-LM, CPU) on the prompts the app sends, with the same JSON constraints.

usage: python run_gemma.py MODEL.litertlm app/build/eval/prompts.jsonl app/build/eval/outputs.jsonl
"""
import json, sys, time
import litert_lm as lm

model, prompts, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
done = set()
try:
    done = {json.loads(l)["id"] for l in open(out_path)}
except FileNotFoundError:
    pass
engine = lm.Engine(model, backend=lm.Backend.CPU(), max_num_tokens=8192)
cd = lm.ConstrainedDecodingConfig(enable=True, provider=lm.LiteRtLmConstraintProviderType.LL_GUIDANCE)
with open(out_path, "a") as out:
    for line in open(prompts):
        p = json.loads(line)
        if p["id"] in done:
            continue
        t0 = time.time()
        conv = engine.create_conversation(
            sampler_config=lm.SamplerConfig(top_k=40, top_p=0.95, temperature=0.0, seed=1234),
            constrained_decoding_config=cd if "schema" in p else None, max_output_tokens=p.get("max", 1200))
        if "schema" in p:
            msg = conv.send_message(p["prompt"], response_format=lm.ResponseFormat.json(p["schema"]))
        else:
            msg = conv.send_message(p["prompt"])
        text = "".join(str(c) for c in msg.contents)
        conv.close()
        out.write(json.dumps({"id": p["id"], "text": text, "seconds": round(time.time() - t0, 1)}) + "\n")
        out.flush()
        print(p["id"], round(time.time() - t0, 1), "s", text[:150].replace("\n", " "), flush=True)
