"""Nifty 100 breakout watch: alerts when a stock breaks out by the rules in breakout_rules.json.

Runs every 30 minutes in market hours (prices from Yahoo Finance, which can be up to
15 minutes behind NSE) and once after the close to say whether each breakout held.
Every number in an alert comes from downloaded prices; when data can't be fetched the
output says so and sends nothing.

    python breakouts.py --out out [--prev PREVIOUS_BREAKOUTS.json] [--rules breakout_rules.json]

Writes out/BREAKOUTS.json (read by the Mehitha app, same shape as ALERTS.json) and
out/nifty100.txt (last good index list, used when the index site can't be reached).
Analysis only: this never places, changes or cancels an order.
"""
import argparse
import datetime as dt
import json
import os
import sys
import urllib.request

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import techchart as tc  # noqa: E402

IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
OPEN, CLOSE = dt.time(9, 15), dt.time(15, 30)
SESSION_MIN = 375
NEAR_PCT = 2.0  # "close to breaking out" list: within 2% under the level


def load_rules(path):
    with open(path, encoding="utf-8") as fh:
        return {k: v for k, v in json.load(fh).items() if not k.startswith("_")}


def index_symbols(rules, cache):
    """(symbols, note). Tries the index sites, then the last good list."""
    from screener import constituents
    symbols, err = constituents(rules["index_csv"])
    if symbols:
        return symbols, None
    if cache and os.path.exists(cache):
        cached = [s.strip() for s in open(cache, encoding="utf-8") if s.strip() and not s.startswith("#")]
        if cached:
            return cached, f"Index list site unreachable; used the saved list ({len(cached)} stocks)."
    return [], f"Could not get the Nifty 100 list ({(err or '')[:200]})."


def download(symbols):
    """{symbol: daily frame} and {symbol: last intraday bar time in IST}."""
    import yfinance as yf
    daily = yf.download(symbols, period="1y", interval="1d", auto_adjust=False, group_by="ticker",
                        progress=False, threads=True)
    frames = {}
    for s in symbols:
        try:
            f = daily[s][["Open", "High", "Low", "Close", "Volume"]].dropna()
        except Exception:
            continue
        if len(f) >= 210:
            f.index = pd.DatetimeIndex(f.index).tz_localize(None).normalize()
            frames[s] = f
    stamps = {}
    try:
        intra = yf.download(list(frames), period="1d", interval="5m", auto_adjust=False, group_by="ticker",
                            progress=False, threads=True)
        for s in frames:
            try:
                bars = intra[s]["Close"].dropna()
                if len(bars):
                    t = bars.index[-1]
                    t = t.tz_convert("Asia/Kolkata") if t.tzinfo else t.tz_localize("Asia/Kolkata")
                    stamps[s] = (t + pd.Timedelta(minutes=5)).to_pydatetime()  # bar end
            except Exception:
                pass
    except Exception as e:
        print(f"intraday download: {e}", file=sys.stderr)
    return frames, stamps


def level_of(hist, rules):
    if rules["level"] == "high20":
        return float(hist["High"].iloc[-20:].max()), "20-day high"
    if rules["level"] == "high60":
        return float(hist["High"].iloc[-60:].max()), "60-day high"
    if rules["level"] == "high52w":
        return float(hist["High"].iloc[-250:].max()), "52-week high"
    d = tc.analyse(hist)
    return float(tc.key_levels(d["df"], d["highs"], d["lows"])["breakout"]), "breakout level"


def check(symbol, frame, rules, session_frac):
    """Rule check on the last bar (today, maybe still forming) against levels from the bars before it."""
    hist, bar = frame.iloc[:-1], frame.iloc[-1]
    if len(hist) < 200:
        return None
    df = tc.add_indicators(frame)
    last = df.iloc[-1]
    price, prev_close = float(bar["Close"]), float(hist["Close"].iloc[-1])
    level, level_name = level_of(hist, rules)
    vol_avg = float(hist["Volume"].iloc[-20:].mean())
    vol_ratio = float(bar["Volume"]) / (vol_avg * session_frac) if vol_avg > 0 else 0.0
    turnover = float((hist["Close"] * hist["Volume"]).iloc[-20:].mean() / 1e7)
    atr = float(df["ATR"].iloc[-2])
    emas_ok = all(price > float(last[f"EMA{n}"]) for n in rules["require_above_ema"])
    rsi = float(last["RSI"]) if not np.isnan(last["RSI"]) else 50.0
    stop = level - rules["stop_atr_below_level"] * atr
    r = {"symbol": symbol, "price": price, "prev_close": prev_close, "level": level, "level_name": level_name,
         "vol_ratio": vol_ratio, "rsi": rsi, "emas_ok": emas_ok, "turnover": turnover,
         "ext_pct": 100 * (price / level - 1), "stop": stop, "risk_pct": 100 * (price - stop) / price,
         "date": frame.index[-1].date().isoformat()}
    r["crossed"] = price > level >= prev_close
    r["passes"] = (r["crossed"] and vol_ratio >= rules["volume_multiple"] and emas_ok
                   and rules["rsi_min"] <= rsi <= rules["rsi_max"] and turnover >= rules["min_turnover_cr"])
    r["near"] = (not r["crossed"] and price <= level and 100 * (1 - price / level) <= NEAR_PCT and emas_ok
                 and turnover >= rules["min_turnover_cr"])
    return r


def name_of(sym):
    return sym.replace(".NS", "")


def describe(r, rules, when):
    sym = name_of(r["symbol"])
    text = (f"{sym} at ₹{r['price']:,.1f} ({when}), above its {r['level_name']} of ₹{r['level']:,.1f}. "
            f"Volume {r['vol_ratio']:.1f}x usual, RSI {r['rsi']:.0f}. "
            f"Rule stop ₹{r['stop']:,.1f} ({r['risk_pct']:.1f}% risk).")
    if r["ext_pct"] > rules["max_extension_pct"]:
        text += f" Already {r['ext_pct']:.1f}% above the level, so wait for a retest rather than chase."
    return text


def build_alerts(results, prev, mode, day, when, rules):
    """Today's alerts so far (kept from the previous run) plus new ones."""
    old = prev.get("alerts", []) if prev.get("date") == day else []
    ids = {a["id"] for a in old}
    new = []

    def add(aid, title, text):
        if aid not in ids:
            ids.add(aid)
            new.append({"id": aid, "title": title, "text": text})

    for r in results:
        sym = name_of(r["symbol"])
        if r["passes"]:
            if mode == "intraday":
                add(f"breakout:{sym}:{day}", f"{sym} is breaking out",
                    describe(r, rules, when) + " Not confirmed until the close.")
            else:
                add(f"breakout-close:{sym}:{day}", f"{sym} broke out at the close", describe(r, rules, f"close {when}"))
        elif mode == "close" and f"breakout:{sym}:{day}" in ids:
            why = (f"closed at ₹{r['price']:,.1f}, back under ₹{r['level']:,.1f}" if r["price"] <= r["level"]
                   else f"closed at ₹{r['price']:,.1f} but volume ({r['vol_ratio']:.1f}x) or trend didn't confirm")
            add(f"breakout-failed:{sym}:{day}", f"{sym} breakout did not hold", f"{sym} {why}. No breakout by your rules today.")
    return old + new, new


def ntfy(topic, alerts):
    """Optional instant push through the ntfy app, when the NTFY_TOPIC secret is set."""
    for a in alerts:
        try:
            req = urllib.request.Request(f"https://ntfy.sh/{topic}", data=a["text"].encode("utf-8"),
                                         headers={"Title": a["title"].encode("ascii", "ignore").decode(), "Tags": "chart_with_upwards_trend"})
            urllib.request.urlopen(req, timeout=15).read()
        except Exception as e:
            print(f"ntfy: {e}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--prev", default="")
    ap.add_argument("--rules", default=os.path.join(HERE, "breakout_rules.json"))
    ap.add_argument("--mode", choices=["auto", "intraday", "close"], default="auto")
    args = ap.parse_args()
    rules = load_rules(args.rules)
    os.makedirs(args.out, exist_ok=True)
    prev = {}
    if args.prev and os.path.exists(args.prev) and os.path.getsize(args.prev):
        try:
            prev = json.load(open(args.prev, encoding="utf-8"))
        except ValueError:
            prev = {}
    now = dt.datetime.now(IST)
    cache = os.path.join(args.out, "nifty100.txt")
    symbols, note = index_symbols(rules, cache)
    symbols = list(dict.fromkeys(symbols + [s if "." in s else s + ".NS" for s in rules.get("extra_symbols", [])]))
    notes = [note] if note else []
    out = {"date": prev.get("date", ""), "checked_at": now.strftime("%Y-%m-%d %H:%M IST"), "alerts": [], "near": [],
           "rules": rules, "notes": notes, "source": "Yahoo Finance; intraday prices can be up to 15 minutes behind NSE."}

    frames, stamps = download(symbols) if symbols else ({}, {})
    if symbols and len(frames) < 0.6 * len(symbols):
        notes.append(f"Price data came back for only {len(frames)} of {len(symbols)} stocks; no alerts this run.")
        frames = {}
    if not frames:
        out["alerts"] = prev.get("alerts", [])
        json.dump(out, open(os.path.join(args.out, "BREAKOUTS.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print("\n".join(notes) or "no data")
        return
    with open(cache, "w", encoding="utf-8") as fh:
        fh.write(f"# Nifty 100 list used on {now:%Y-%m-%d}\n" + "\n".join(symbols) + "\n")

    day = max(f.index[-1] for f in frames.values()).date()
    live = day == now.date() and now.weekday() < 5 and OPEN <= now.time() < CLOSE
    mode = args.mode if args.mode != "auto" else ("intraday" if live else "close")
    as_of = max(stamps.values()) if stamps else None
    if mode == "intraday":
        elapsed = ((as_of or now) - (as_of or now).replace(hour=9, minute=15, second=0, microsecond=0)).total_seconds() / 60
        frac = float(np.clip(elapsed / SESSION_MIN, 0.25, 1.0))  # early volume runs heavy; don't over-project it
        when = f"{as_of:%-I:%M %p} IST" if as_of else f"checked {now:%-I:%M %p} IST"
    else:
        frac, when = 1.0, f"{day:%-d %b}"

    results = []
    for s, f in frames.items():
        if f.index[-1].date() != day:
            continue  # no bar for today: suspended or stale
        try:
            r = check(s, f, rules, frac)
        except Exception as e:
            print(f"{s}: {e}", file=sys.stderr)
            continue
        if r:
            results.append(r)

    alerts, new = build_alerts(results, prev, mode, day.isoformat(), when, rules)
    out.update({"date": day.isoformat(), "mode": mode, "as_of": when, "alerts": alerts, "scanned": len(results),
                "near": [{"symbol": name_of(r["symbol"]), "price": round(r["price"], 2), "level": round(r["level"], 2),
                          "gap_pct": round(100 * (1 - r["price"] / r["level"]), 2)}
                         for r in sorted((r for r in results if r["near"]), key=lambda r: r["price"] / r["level"], reverse=True)]})
    json.dump(out, open(os.path.join(args.out, "BREAKOUTS.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    if os.environ.get("NTFY_TOPIC") and new:
        ntfy(os.environ["NTFY_TOPIC"], new)
    print(f"{mode} {day} as of {when}: {len(results)} stocks checked, {len(new)} new alerts, {len(out['near'])} near a breakout")
    for a in new:
        print(f"- {a['title']}: {a['text']}")
    for n in notes:
        print(f"note: {n}")


if __name__ == "__main__":
    main()
