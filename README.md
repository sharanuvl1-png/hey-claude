# Hi Mehitha

A small personal Android app that listens for **"Hi Mehitha"** and opens the Claude app.

- Speech recognition runs fully on the phone (Vosk, offline). No audio leaves the device.
- It only listens for "Hi/Hey/Hello Mehitha" (matched through similar-sounding English words, since the offline model has no word "Mehitha").
- After it opens Claude it pauses for a minute so Claude can use the microphone.
- If the phone is locked, it turns the screen on and asks you to unlock, then opens Claude.

## Install

1. Every push to `main` builds the app. Open the repository's **Releases** page on your phone and download `hey-claude-buildN.apk` from the latest build (N is the build number, e.g. `hey-claude-full-build27.apk`).
2. Open the file and allow "Install unknown apps" for your browser or Files app when asked.
3. Open **Hi Mehitha** and tap the three setup buttons (microphone, background opening, battery), then **Start listening**.

## Good to know

- A small "Listening" notification stays visible. Android requires this for any app using the microphone in the background.
- After a phone restart, open Hi Mehitha once and tap Start.
- On Samsung, also set Settings → Battery → Background usage limits → **Never auto sleeping apps** → add Hi Mehitha.

## Technical charts (swing trading)

`tools/techchart/techchart.py` builds a daily + 4H swing-trade analysis for any NSE stock from real prices (Yahoo Finance): EMA20/50/200, MACD, RSI with divergences, swing structure, chart patterns, candlestick signals, key levels, entry/targets/stop-loss and a final verdict, plus an annotated chart image.

Run it from the **Actions → Technical chart → Run workflow** button with a company name (for example `PC Jeweller`). The chart and report are saved to the `charts` branch under `<SYMBOL>/<date>.png` and `.md`. Everything is rules-based and educational, not investment advice.

## Phone data for Mehitha

The app can copy phone data into a private Dropbox **App folder** (`/Apps/<your app name>/`) so Claude's Dropbox connector can search it:

| Folder | What | How often |
|---|---|---|
| `sms/YYYY-MM.md` | Text messages (OTPs skipped), with contact names | every 30 min |
| `notifications/DATE.txt` | WhatsApp and other app messages, captured from notifications from the day you enable it | every 30 min |
| `files/index.tsv` | Path, size and date of every file in Download, Documents, DCIM, Pictures, WhatsApp media… | every 30 min |
| `files/docs/…` | Documents (PDF, Word, Excel, …, up to 25 MB) from Download, Documents and WhatsApp Documents | every 30 min |
| `requests/NAME.txt` → `requested/` | Put a phone path or part of a file name in a request file; the phone uploads that file (up to 140 MB) within ~3 minutes | while listening |

Setup: create a Dropbox app (Scoped access → App folder) with `files.content.write`, `files.content.read` and `files.metadata.read`, paste its App key in the app, approve, paste the code back, then grant SMS, all-files and notification access. WhatsApp has no API for chat history, so older chats need **Export chat** into Dropbox.
