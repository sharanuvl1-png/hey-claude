"""Nifty 100 breakout watch: alerts when a stock breaks out by the rules in breakout_rules.json.

Runs every 30 minutes in market hours (prices from Yahoo Finance, which can be up to
15 minutes behind NSE) and once after the close to say whether each breakout held.
Every number in an alert comes from downloaded prices; when data can't be fetched the
output says so and sends nothing.

    python breakouts.py --out out [--prev PREVIOUS_BREAKOUTS.json] [--rules breakout_rules.json]

Writes out/BREAKOUTS.json (read by the Mehitha app, same shape as ALERTS.json, plus an
"image" and "link" per alert), out/charts/<SYMBOL>/<date>-<intraday|close>.png/.md (the full
Technical chart for each alert, kept 7 days) and out/nifty100.txt (last good index list,
used when the index site can't be reached).
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
NIFTY = "^NSEI"
REPO = os.environ.get("GITHUB_REPOSITORY", "sharanuvl1-png/hey-claude")
RAW = f"https://raw.githubusercontent.com/{REPO}/breakouts/"
VIEW = f"https://github.com/{REPO}/blob/breakouts/"
KEEP_CHART_DAYS = 7


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
    daily = yf.download(symbols + [NIFTY], period="1y", interval="1d", auto_adjust=False, group_by="ticker",
                        progress=False, threads=True)
    frames = {}
    for s in symbols + [NIFTY]:
        try:
            f = daily[s][["Open", "High", "Low", "Close", "Volume"]].dropna()
        except Exception:
            continue
        if len(f) >= 210:
            f.index = pd.DatetimeIndex(f.index).tz_localize(None).normalize()
            frames[s] = f
    stamps = {}
    try:
        intra = yf.download([s for s in frames if s != NIFTY], period="1d", interval="5m", auto_adjust=False, group_by="ticker",
                            progress=False, threads=True)
        for s in frames:
            if s == NIFTY:
                continue
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


def market_of(nifty):
    """Nifty 50 trend and 3-month return, for the market filter and relative strength."""
    if nifty is None or len(nifty) < 70:
        return None
    c = nifty["Close"]
    ema50 = float(c.ewm(span=50, adjust=False).mean().iloc[-1])
    return {"close": float(c.iloc[-1]), "ema50": ema50, "up": float(c.iloc[-1]) > ema50,
            "ret3m": float(c.iloc[-1] / c.iloc[-64] - 1)}


def check(symbol, frame, rules, session_frac, market=None):
    """Rule check on the last bar (today, maybe still forming) against levels from the bars before it."""
    hist, bar = frame.iloc[:-1], frame.iloc[-1]
    if len(hist) < 200:
        return None
    df = tc.add_indicators(frame)
    last = df.iloc[-1]
    price, prev_close = float(bar["Close"]), float(hist["Close"].iloc[-1])
    level, level_name = level_of(hist, rules)
    vol_avg = float(hist["Volume"].iloc[-rules.get("volume_avg_days", 20):].mean())
    vol_ratio = float(bar["Volume"]) / (vol_avg * session_frac) if vol_avg > 0 else 0.0
    turnover = float((hist["Close"] * hist["Volume"]).iloc[-20:].mean() / 1e7)
    atr = float(df["ATR"].iloc[-2])
    rsi = float(last["RSI"]) if not np.isnan(last["RSI"]) else 50.0
    stop = level - rules["stop_atr_below_level"] * atr
    day_range = float(bar["High"] - bar["Low"])
    close_pos = (price - float(bar["Low"])) / day_range if day_range > 0 else 1.0
    hi52 = float(frame["High"].iloc[-250:].max())
    ret3m = float(price / hist["Close"].iloc[-63] - 1)
    rs = 100 * (ret3m - market["ret3m"]) if market else None
    base = hist.iloc[-rules.get("base_days", 15):]
    base_depth = 100 * float(base["High"].max() - base["Low"].min()) / level
    r = {"symbol": symbol, "price": price, "prev_close": prev_close, "level": level, "level_name": level_name,
         "vol_ratio": vol_ratio, "rsi": rsi, "turnover": turnover, "atr": atr,
         "ext_pct": 100 * (price / level - 1), "stop": stop, "risk_pct": 100 * (price - stop) / price,
         "close_pos": close_pos, "from_hi52_pct": 100 * (1 - price / hi52), "rs": rs, "base_depth": base_depth,
         "date": frame.index[-1].date().isoformat()}
    r["crossed"] = price > level >= prev_close
    # Each rule: (name, passed). Optional rules switch off with null / false in breakout_rules.json.
    tests = [("volume", vol_ratio >= rules["volume_multiple"]),
             ("above EMAs", all(price > float(last[f"EMA{n}"]) for n in rules["require_above_ema"])),
             ("RSI", rules["rsi_min"] <= rsi <= rules["rsi_max"]),
             ("liquidity", turnover >= rules["min_turnover_cr"])]
    if rules.get("require_ema200_rising"):
        tests.append(("EMA200 rising", float(df["EMA200"].iloc[-1]) > float(df["EMA200"].iloc[-21])))
    if rules.get("max_below_52w_high_pct") is not None:
        tests.append(("near 52-week high", r["from_hi52_pct"] <= rules["max_below_52w_high_pct"]))
    if rules.get("min_close_in_range") is not None:
        tests.append(("strong close", close_pos >= rules["min_close_in_range"]))
    if rules.get("min_rs_vs_nifty_pct") is not None and rs is not None:
        tests.append(("beating Nifty", rs >= rules["min_rs_vs_nifty_pct"]))
    if rules.get("max_base_depth_pct") is not None:
        tests.append(("tight base", base_depth <= rules["max_base_depth_pct"]))
    if rules.get("require_market_uptrend") and market:
        tests.append(("market uptrend", market["up"]))
    r["failed"] = [n for n, ok in tests if not ok]
    trend_ok = "above EMAs" not in r["failed"] and "liquidity" not in r["failed"]
    r["passes"] = r["crossed"] and not r["failed"]
    r["near"] = not r["crossed"] and price <= level and 100 * (1 - price / level) <= NEAR_PCT and trend_ok
    return r


def name_of(sym):
    return sym.replace(".NS", "")


def money(v):
    return f"₹{v:,.1f}"


def describe(r, rules, when, chart=None, market=None):
    """Alert text: the breakout facts, then the full chart analysis when the chart was built."""
    sym = name_of(r["symbol"])
    lines = [f"{sym} {money(r['price'])} ({when}) is above its {r['level_name']} {money(r['level'])}. "
             f"Volume {r['vol_ratio']:.1f}x usual, RSI {r['rsi']:.0f}"
             + (f", {r['rs']:+.0f}% vs Nifty over 3 months" if r["rs"] is not None else "") + "."]
    if r["ext_pct"] > rules["max_extension_pct"]:
        lines.append(f"Already {r['ext_pct']:.1f}% above the level: wait for a retest, don't chase.")
    if chart:
        s, lv = chart["setup"], chart["levels"]
        t = s["targets"]
        lines.append(f"Chart: {chart['rec']}, grade {chart['grade']}, confidence {chart['conf']}/10. "
                     f"Trend {chart['trend'].lower()} (daily), {str(chart['h4_trend']).lower()} (4H); "
                     f"MACD {'above' if chart['macd_up'] else 'below'} signal.")
        lines.append(f"Entry {money(s['entry'][0])}–{money(s['entry'][1])} or retest {money(s['alt'][0])}–{money(s['alt'][1])}. "
                     f"Stop-loss {money(s['stop_cons'])} (aggressive {money(s['stop_aggr'])}). "
                     f"Targets {money(t[0])} / {money(t[1])} / {money(t[2])}.")
        lines.append(f"Support {money(lv['immediate'][0])}–{money(lv['immediate'][1])}, strong support "
                     f"{money(lv['strong'][0])}–{money(lv['strong'][1])}, next resistance "
                     f"{money(lv['resistance'][0])}–{money(lv['resistance'][1])}.")
        seen = chart["patterns"] + chart["candles"] + chart["divergences"]
        if seen:
            lines.append("Signals: " + ", ".join(dict.fromkeys(seen)) + ".")
        lines.append("Why: " + "; ".join(chart["reasons"]) + f". Next: {chart['next']}.")
        mid = sum(s["entry"]) / 2
        risk = 100 * (mid - s["stop_cons"]) / mid
        if risk > rules.get("max_risk_pct", 100):
            lines.append(f"Careful: the chart stop is {risk:.0f}% under the entry, wider than your {rules['max_risk_pct']:.0f}% limit. "
                         f"Tighter rule stop {money(r['stop'])} ({r['risk_pct']:.1f}%).")
    else:
        lines.append(f"Rule stop {money(r['stop'])} ({r['risk_pct']:.1f}% risk).")
    if market and not market["up"]:
        lines.append("Market caution: Nifty 50 is below its 50-day EMA.")
    return " ".join(lines)


def build_chart(r, out, tag):
    """Full technical chart (same as the Technical chart action) for an alert; None if it can't be built."""
    try:
        chart = tc.run(r["symbol"], name_of(r["symbol"]), os.path.join(out, "charts"))
    except BaseException as e:  # techchart raises SystemExit on bad or stale data
        print(f"chart {r['symbol']}: {e}", file=sys.stderr)
        return None
    for key in ("png", "md"):
        path = chart[key]
        dest = path[:-len(key) - 1] + f"-{tag}.{key}"
        os.replace(path, dest)
        chart[key] = os.path.relpath(dest, out).replace(os.sep, "/")
    md = os.path.join(out, chart["md"])
    text = open(md, encoding="utf-8").read().replace(f"({chart['date']}.png)", f"({os.path.basename(chart['png'])})")
    open(md, "w", encoding="utf-8").write(text)
    return chart


def prune_charts(out, today):
    root = os.path.join(out, "charts")
    for d, _, files in os.walk(root):
        for f in files:
            try:
                if (today - dt.date.fromisoformat(f[:10])).days > KEEP_CHART_DAYS:
                    os.remove(os.path.join(d, f))
            except ValueError:
                pass


def build_alerts(results, prev, mode, day, when, rules, out, market=None, charts=True):
    """Today's alerts so far (kept from the previous run) plus new ones, each with its chart."""
    old = prev.get("alerts", []) if prev.get("date") == day else []
    ids = {a["id"] for a in old}
    new = []

    def add(aid, title, r=None, text=None, tag=None, suffix=""):
        if aid in ids:
            return
        ids.add(aid)
        alert = {"id": aid, "title": title}
        chart = build_chart(r, out, tag) if r is not None and charts else None
        if r is not None:
            alert["text"] = describe(r, rules, when if mode == "intraday" else f"close {when}", chart, market) + suffix
        else:
            alert["text"] = text
        if chart:
            alert["image"] = RAW + chart["png"]
            alert["link"] = VIEW + chart["md"]
        new.append(alert)

    for r in results:
        sym = name_of(r["symbol"])
        if r["passes"]:
            if mode == "intraday":
                add(f"breakout:{sym}:{day}", f"{sym} is breaking out", r, tag="intraday",
                    suffix=" Not confirmed until the close.")
            else:
                add(f"breakout-close:{sym}:{day}", f"{sym} broke out at the close", r, tag="close")
        elif mode == "close" and f"breakout:{sym}:{day}" in ids:
            why = (f"closed at {money(r['price'])}, back under {money(r['level'])}" if r["price"] <= r["level"]
                   else f"closed at {money(r['price'])} but failed: {', '.join(r['failed'])}")
            add(f"breakout-failed:{sym}:{day}", f"{sym} breakout did not hold",
                text=f"{sym} {why}. No breakout by your rules today.")
    return old + new, new


def ntfy(topic, alerts):
    """Optional instant push through the ntfy app, when the NTFY_TOPIC secret is set."""
    for a in alerts:
        try:
            headers = {"Title": a["title"].encode("ascii", "ignore").decode(), "Tags": "chart_with_upwards_trend"}
            if a.get("image"):
                headers["Attach"] = a["image"]
            if a.get("link"):
                headers["Click"] = a["link"]
            req = urllib.request.Request(f"https://ntfy.sh/{topic}", data=a["text"].encode("utf-8"), headers=headers)
            urllib.request.urlopen(req, timeout=15).read()
        except Exception as e:
            print(f"ntfy: {e}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--prev", default="")
    ap.add_argument("--rules", default=os.path.join(HERE, "breakout_rules.json"))
    ap.add_argument("--mode", choices=["auto", "intraday", "close"], default="auto")
    ap.add_argument("--new", default="", help="also write just this run's new alerts here")
    ap.add_argument("--ntfy", default="", help="send the alerts in this file to ntfy (after the charts are pushed)")
    args = ap.parse_args()
    if args.ntfy:
        if os.environ.get("NTFY_TOPIC") and os.path.exists(args.ntfy):
            ntfy(os.environ["NTFY_TOPIC"], json.load(open(args.ntfy, encoding="utf-8")))
        return
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

    market = market_of(frames.pop(NIFTY, None))
    if market is None:
        notes.append("Nifty 50 data missing: no market filter or relative strength this run.")
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
            r = check(s, f, rules, frac, market)
        except Exception as e:
            print(f"{s}: {e}", file=sys.stderr)
            continue
        if r:
            results.append(r)

    prune_charts(args.out, now.date())
    alerts, new = build_alerts(results, prev, mode, day.isoformat(), when, rules, args.out, market)
    out.update({"date": day.isoformat(), "mode": mode, "as_of": when, "alerts": alerts, "scanned": len(results),
                "market": market,
                "near": [{"symbol": name_of(r["symbol"]), "price": round(r["price"], 2), "level": round(r["level"], 2),
                          "gap_pct": round(100 * (1 - r["price"] / r["level"]), 2)}
                         for r in sorted((r for r in results if r["near"]), key=lambda r: r["price"] / r["level"], reverse=True)]})
    json.dump(out, open(os.path.join(args.out, "BREAKOUTS.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    if args.new:
        json.dump(new, open(args.new, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"{mode} {day} as of {when}: {len(results)} stocks checked, {len(new)} new alerts, {len(out['near'])} near a breakout")
    for a in new:
        print(f"- {a['title']}: {a['text']}")
    for n in notes:
        print(f"note: {n}")


if __name__ == "__main__":
    main()
