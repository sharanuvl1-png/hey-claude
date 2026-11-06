import sys, subprocess
import numpy as np
from faster_whisper import WhisperModel
m = WhisperModel("small", device="cpu", compute_type="int8")
raw = subprocess.run(["ffmpeg","-v","error","-i",sys.argv[1],"-ac","1","-ar","16000","-f","f32le","-"],check=True,capture_output=True).stdout
audio = np.frombuffer(raw, dtype=np.float32)
segs, info = m.transcribe(audio, beam_size=1, vad_filter=True)
with open(sys.argv[2], "w") as f:
    f.write(f"# language={info.language} p={info.language_probability:.2f}\n")
    n = 0
    for s in segs:
        t = int(s.start); f.write(f"[{t//3600}:{t%3600//60:02d}:{t%60:02d}] {s.text.strip()}\n"); n += 1
        if n % 100 == 0: print("segments", n, "at", t, "s", flush=True)
print("done", n, flush=True)
