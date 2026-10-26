# Hi Mehitha

A small personal Android app that listens for **"Hi Mehitha"** and opens the Claude app.

- Speech recognition runs fully on the phone (Vosk, offline). No audio leaves the device.
- It only listens for "Hi/Hey/Hello Mehitha" (matched through similar-sounding English words, since the offline model has no word "Mehitha").
- After it opens Claude it pauses for a minute so Claude can use the microphone.
- If the phone is locked, it turns the screen on and asks you to unlock, then opens Claude.

## Install

1. Every push to `main` builds the app. Open the repository's **Releases** page on your phone and download `hey-claude.apk` from the latest build.
2. Open the file and allow "Install unknown apps" for your browser or Files app when asked.
3. Open **Hi Mehitha** and tap the three setup buttons (microphone, background opening, battery), then **Start listening**.

## Good to know

- A small "Listening" notification stays visible. Android requires this for any app using the microphone in the background.
- After a phone restart, open Hi Mehitha once and tap Start.
- On Samsung, also set Settings → Battery → Background usage limits → **Never auto sleeping apps** → add Hi Mehitha.

## Technical charts (swing trading)

`tools/techchart/techchart.py` builds a daily + 4H swing-trade analysis for any NSE stock from real prices (Yahoo Finance): EMA20/50/200, MACD, RSI with divergences, swing structure, chart patterns, candlestick signals, key levels, entry/targets/stop-loss and a final verdict, plus an annotated chart image.

Run it from the **Actions → Technical chart → Run workflow** button with a company name (for example `PC Jeweller`). The chart and report are saved to the `charts` branch under `<SYMBOL>/<date>.png` and `.md`. Everything is rules-based and educational, not investment advice.
