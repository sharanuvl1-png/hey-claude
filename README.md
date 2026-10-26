# Hey Claude

A small personal Android app that listens for **"Hey Claude"** and opens the Claude app.

- Speech recognition runs fully on the phone (Vosk, offline). No audio leaves the device.
- It only listens for a few phrases ("hey claude", "hey cloud", "hi claude").
- After it opens Claude it pauses for a minute so Claude can use the microphone.
- If the phone is locked, it turns the screen on and asks you to unlock, then opens Claude.

## Install

1. Every push to `main` builds the app. Open the repository's **Releases** page on your phone and download `hey-claude.apk` from the latest build.
2. Open the file and allow "Install unknown apps" for your browser or Files app when asked.
3. Open **Hey Claude** and tap the three setup buttons (microphone, background opening, battery), then **Start listening**.

## Good to know

- A small "Listening" notification stays visible. Android requires this for any app using the microphone in the background.
- After a phone restart, open Hey Claude once and tap Start.
- On Samsung, also set Settings → Battery → Background usage limits → **Never auto sleeping apps** → add Hey Claude.
