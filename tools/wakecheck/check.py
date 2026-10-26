"""Offline check of the "Hi Mehitha" wake word with the same Vosk model and grammar
the app uses. Speaks test phrases with several espeak-ng voices and reports which
ones would trigger. Synthetic voices are only a sanity check, not a real accent test."""
import json, os, re, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
src = open("app/src/main/java/com/personal/heyclaude/WakeWordService.kt").read()
def kt_list(name):
    body = re.search(name + r" = listOf\((.*?)\)", src, re.S).group(1)
    return re.findall(r'"([^"]+)"', body)
greet, names = kt_list("GREETINGS"), kt_list("NAME_SOUNDS")
phrases = [f"{g} {n}" for g in greet for n in names]
grammar = json.dumps(phrases + kt_list("DECOYS") + ["[unk]"])
model = Model(sys.argv[1])

def heard(wav_path):
    wf = wave.open(wav_path)
    rec = KaldiRecognizer(model, wf.getframerate(), grammar)
    hit, texts = False, []
    while True:
        data = wf.readframes(4000)
        if not data:
            break
        res = json.loads(rec.Result()) if rec.AcceptWaveform(data) else json.loads(rec.PartialResult())
        t = res.get("text") or res.get("partial") or ""
        if t:
            texts.append(t)
        hit |= any(p in t for p in phrases)
    t = json.loads(rec.FinalResult()).get("text", "")
    texts.append(t)
    hit |= any(p in t for p in phrases)
    return hit, texts[-1] or (texts[-2] if len(texts) > 1 else "")

positives = ["Hi Mehitha", "Hey Mehitha", "Hi Meh-hee-tha", "Hello Mehitha", "Hi Mehita"]
negatives = ["Hi, how are you", "Hey, what is the time", "Meta stock is up today", "Hello mother",
             "I hit a ball", "Hey Google", "Hi Bixby", "Please call Mehta tomorrow",
             "Hi there", "Hey listen to this", "He hit the ball hard", "Hello Mitha",
             "OK Google", "Hey Google, what's the weather", "Hi Google", "Hey Siri"]
voices = ["en-us", "en-gb", "en-in" if subprocess.run(["espeak-ng", "-v", "en-in", "x", "-w", "/dev/null"]).returncode == 0 else "en",
          "en-us+f3", "en-gb+m3"]
speeds = [140, 175]
ok = tot = fp = ntot = 0
os.makedirs("wav", exist_ok=True)
for text, want in [(p, True) for p in positives] + [(n, False) for n in negatives]:
    for v in voices:
        for s in speeds:
            raw, out = "wav/raw.wav", "wav/t.wav"
            subprocess.run(["espeak-ng", "-v", v, "-s", str(s), "-w", raw, text], check=True)
            subprocess.run(["sox", raw, "-r", "16000", "-c", "1", "-b", "16", out], check=True)
            hit, got = heard(out)
            if want:
                tot += 1; ok += hit
            else:
                ntot += 1; fp += hit
            print(f"{'POS' if want else 'NEG'} {'HIT ' if hit else 'miss'} voice={v:9} speed={s} said={text!r:32} heard={got!r}")
print(f"\nSUMMARY: wake phrase detected {ok}/{tot}; false triggers {fp}/{ntot}")
