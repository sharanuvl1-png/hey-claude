# Mehitha

A small personal Android AI assistant. Open the app and chat with Mehitha: type, or tap the mic and speak.

- Answers come from Claude (with your Claude API key) plus your phone's own data: spending from SMS, bills, the daily stock scan, WhatsApp and app messages, documents and reminders.
- Suggestion chips for the common asks: brief me, any alerts, spending report, odd charges, check a stock, what did I miss, weekly review, remind me, find a document.
- Questions you speak are read back aloud; typed answers have a **Read aloud** button. Each chat keeps its context for follow-ups; **+** starts a new chat.
- Without an API key, Mehitha hands the question to the Claude app instead (the voice/full builds type it in for you).
- Nothing listens in the background: the microphone is only on after you tap it.

## Install

1. Every push to `main` builds the app. Open the repository's **Releases** page on your phone and download the APK from the latest build (e.g. `hey-claude-full-build32.apk`).
2. Open the file and allow "Install unknown apps" for your browser or Files app when asked.
3. Open **Mehitha**, tap ⚙ **Settings** and add your Claude API key. Microphone, Dropbox and phone-data access are optional and live there too.

## Technical charts (swing trading)

`tools/techchart/techchart.py` builds a daily + 4H swing-trade analysis for any NSE stock from real prices (Yahoo Finance): EMA20/50/200, MACD, RSI with divergences, swing structure, chart patterns, candlestick signals, key levels, entry/targets/stop-loss and a final verdict, plus an annotated chart image.

Run it from the **Actions → Technical chart → Run workflow** button with a company name (for example `PC Jeweller`). The chart and report are saved to the `charts` branch under `<SYMBOL>/<date>.png` and `.md`. Everything is rules-based and educational, not investment advice.

## Nifty 100 breakout alerts

`tools/techchart/breakouts.py` watches all Nifty 100 stocks and alerts when one breaks out by the rules in `tools/techchart/breakout_rules.json`. Default: price crosses above its nearest resistance (the "Breakout level" on the charts) on volume at least 1.5x the 50-day average, above the 50 and 200-day EMAs with the 200-day EMA rising, RSI 50–80, within 25% of the 52-week high, closing in the top half of the day's range and beating the Nifty 50 over 3 months. Edit that file to change or switch off any rule.

Each alert carries the full Technical chart analysis (verdict, grade, confidence, trend, entry, stop-loss, targets, key levels, patterns, candles) and the chart image itself, which shows in the phone notification. Tapping opens the full analysis.

- The **Nifty 100 breakouts** action runs every 30 minutes from 9:45 AM to 3:15 PM IST on weekdays, and at 3:45 PM to say whether each breakout held at the close. Prices come from Yahoo Finance and can be up to 15 minutes behind NSE; GitHub can also start scheduled runs a few minutes late.
- Results go to `BREAKOUTS.json` on the `breakouts` branch. The Mehitha app checks it every 15 minutes on market days and shows each alert once; "any alerts?" reads them back.
- Optional instant push: install the ntfy app, subscribe to a long random topic name, and save that name as the repository secret `NTFY_TOPIC`.
- Analysis only. Nothing here places, changes or cancels an order.

## Phone data for Mehitha

The app can copy phone data into a private Dropbox **App folder** (`/Apps/<your app name>/`) so Claude's Dropbox connector can search it:

| Folder | What | How often |
|---|---|---|
| `sms/YYYY-MM.md` | Text messages (OTPs skipped), with contact names | every 30 min |
| `notifications/DATE.txt` | WhatsApp and other app messages, captured from notifications from the day you enable it | every 30 min |
| `files/index.tsv` | Path, size and date of every file in Download, Documents, DCIM, Pictures, WhatsApp media… | every 30 min |
| `files/docs/…` | Documents (PDF, Word, Excel, …, up to 25 MB) from Download, Documents and WhatsApp Documents | every 30 min |
| `requests/NAME.txt` → `requested/` | Put a phone path or part of a file name in a request file; the phone uploads that file (up to 140 MB) within a few minutes (longer while the phone is asleep) | every ~3 min |

Setup: create a Dropbox app (Scoped access → App folder) with `files.content.write`, `files.content.read` and `files.metadata.read`, paste its App key in Mehitha Settings, approve, paste the code back, then grant SMS, all-files and notification access. WhatsApp has no API for chat history, so older chats need **Export chat** into Dropbox.
