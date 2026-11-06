import sys
from faster_whisper import WhisperModel
m = WhisperModel("small", device="cpu", compute_type="int8")
segs, info = m.transcribe(sys.argv[1], beam_size=1, vad_filter=True)
with open(sys.argv[2], "w") as f:
    f.write(f"# language={info.language} p={info.language_probability:.2f}\n")
    n = 0
    for s in segs:
        t = int(s.start); f.write(f"[{t//3600}:{t%3600//60:02d}:{t%60:02d}] {s.text.strip()}\n"); n += 1
        if n % 100 == 0: print("segments", n, "at", t, "s", flush=True)
print("done", n, flush=True)
