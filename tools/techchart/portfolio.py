"""Quick check of the stocks you hold: today's move plus the Technical chart verdict.

For every stock in the list it runs the same analysis as techchart.py (EMA20/50/200,
MACD, RSI, swing structure, patterns, candles, key levels, entry / stop-loss / targets
and the BUY NOW / BUY ON DIP / WAIT / AVOID verdict) and translates that verdict for
someone who already holds the stock:

  BUY NOW     -> HOLD / ADD       (setup is strong and price is inside the entry zone)
  BUY ON DIP  -> HOLD, ADD ON DIP (add only inside the entry zone)
  WAIT        -> HOLD, DON'T ADD  (add only on a breakout above the breakout level)
  AVOID       -> REVIEW / EXIT    (trend is against you: respect the stop-loss)

The stop-loss, targets and levels are exactly the ones techchart.py computes. Only
stock symbols are read; quantities and buy prices never enter this script.

Usage: python portfolio.py --stocks "HDFCBANK, TCS, PC Jeweller" [--out out]
       (or the PORTFOLIO environment variable: one stock per line or comma-separated;
        anything after a ':' or whitespace on a line, such as a quantity, is ignored)
"""
import argparse
import datetime as dt
import json
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import techchart as tc  # noqa: E402

HOLDER_VIEW = {
    "BUY NOW": ("HOLD / ADD", "green"),
    "BUY ON DIP": ("HOLD, ADD ON DIP", "green"),
    "WAIT": ("HOLD, DON'T ADD", "amber"),
    "AVOID": ("REVIEW / EXIT", "red"),
}


def parse_stocks(text):
    out = []
    for part in re.split(r"[,\n;]+", text or ""):
        part = part.strip()
        if not part or part.startswith("#"):
            continue
        part = re.split(r"[:@]", part)[0].strip()
        # "HDFCBANK 20" -> "HDFCBANK"; keep multi-word company names ("PC Jeweller").
        part = re.sub(r"\s+\d[\d.,]*$", "", part).strip()
        if part and part.upper() not in (s.upper() for s in out):
            out.append(part)
    return out


def symbol_for(query):
    q = query.strip()
    if re.fullmatch(r"[A-Za-z0-9&\-]+", q) and q.upper() == q:
        return q + ".NS", q  # already an NSE symbol such as HDFCBANK or BAJAJ-AUTO
    return tc.resolve_symbol(q)


def holder_view(r):
    key = next(k for k in HOLDER_VIEW if k in r["rec"])
    action, tone = HOLDER_VIEW[key]
    s, lv = r["setup"], r["levels"]
    e_lo, e_hi = s["entry"]
    stop = s["stop_cons"]
    if key == "BUY NOW":
        plan = f"Setup is strong and price is in the entry zone ₹{e_lo:.1f}–₹{e_hi:.1f}. Keep the stop below ₹{stop:.1f}."
    elif key == "BUY ON DIP":
        plan = f"Hold. Add only on a pullback to ₹{e_lo:.1f}–₹{e_hi:.1f}. Keep the stop below ₹{stop:.1f}."
    elif key == "WAIT":
        plan = f"Hold with the stop below ₹{stop:.1f}. Add only on a daily close above ₹{s['alt'][0]:.1f} on good volume."
    else:
        plan = (f"Trend is against you. Exit on a daily close below ₹{stop:.1f}; "
                f"the trend turns only if it reclaims EMA50 (₹{r['ema50']:.1f}) and makes a higher low.")
    if r["close"] < stop:  # the stop-loss techchart.py sets has already been broken
        action, tone = "STOP HIT: EXIT / REVIEW", "red"
        plan = f"Price ₹{r['close']:.1f} is below the stop-loss ₹{stop:.1f}. " + plan
    return {"key": key, "action": action, "tone": tone, "plan": plan,
            "stop_hit": bool(r["close"] < stop), "below_strong_support": bool(r["close"] < lv["strong"][0])}


def zone(z):
    return [round(float(z[0]), 2), round(float(z[1]), 2)]


def summarise(r):
    s, lv = r["setup"], r["levels"]
    hv = holder_view(r)
    return {
        "symbol": r["symbol"].replace(".NS", "").replace(".BO", ""), "yahoo": r["symbol"], "name": r["name"],
        "date": r["date"], "close": round(r["close"], 2), "prev_close": round(r["prev_close"], 2),
        "change_pct": round(100 * (r["close"] / r["prev_close"] - 1), 2),
        "day_high": round(r["day_high"], 2), "day_low": round(r["day_low"], 2),
        "verdict": r["rec"], "confidence": int(r["conf"]), "grade": r["grade"], "score": float(r["score"]),
        "reasons": r["reasons"], "next": r["next"], "holder": hv,
        "trend": r["trend"], "h4_trend": r["h4_trend"], "rsi": round(r["rsi"], 1), "macd_up": r["macd_up"],
        "ema": {"20": round(r["ema20"], 2), "50": round(r["ema50"], 2), "200": round(r["ema200"], 2)},
        "entry": zone(s["entry"]), "breakout_entry": zone(s["alt"]),
        "stop": round(float(s["stop_cons"]), 2), "stop_aggressive": round(float(s["stop_aggr"]), 2),
        "targets": [float(t) for t in s["targets"]], "rr1": round(float(s["rr1"]), 2),
        "levels": {"strong_support": zone(lv["strong"]), "support": zone(lv["immediate"]),
                   "resistance": zone(lv["resistance"]), "breakout": round(float(lv["breakout"]), 2),
                   "high_52w": round(float(lv["hi52"]), 2)},
        "patterns": r["patterns"], "candles": r["candles"], "divergences": r["divergences"],
        "chart": f"{r['symbol'].replace('.NS', '').replace('.BO', '')}.png",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stocks", default=os.environ.get("PORTFOLIO", ""))
    ap.add_argument("--out", default="out")
    args = ap.parse_args()
    stocks = parse_stocks(args.stocks)
    if not stocks:
        raise SystemExit("No stocks given. Set the PORTFOLIO secret or pass --stocks.")
    os.makedirs(args.out, exist_ok=True)
    work = os.path.join(args.out, "_work")
    rows, errors = [], []
    for q in stocks:
        try:
            symbol, name = symbol_for(q)
            r = tc.run(symbol, name, work)
            row = summarise(r)
            shutil.copy(r["png"], os.path.join(args.out, row["chart"]))
            shutil.copy(r["md"], os.path.join(args.out, row["symbol"] + ".md"))
            rows.append(row)
            print(f"{row['symbol']}: {row['close']} ({row['change_pct']:+.2f}%) {row['verdict']} -> {row['holder']['action']}")
        except SystemExit as e:
            errors.append({"stock": q, "error": str(e)})
            print(f"{q}: {e}", file=sys.stderr)
        except Exception as e:  # one bad symbol must not stop the rest
            errors.append({"stock": q, "error": f"{type(e).__name__}: {e}"})
            print(f"{q}: {type(e).__name__}: {e}", file=sys.stderr)
    shutil.rmtree(work, ignore_errors=True)
    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=5, minutes=30)))
    result = {"generated_at": now.isoformat(timespec="minutes"), "source": "Yahoo Finance (NSE), can be ~15 min behind",
              "stocks": rows, "errors": errors}
    with open(os.path.join(args.out, "LATEST.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print(f"{len(rows)} analysed, {len(errors)} failed")
    if not rows:
        sys.exit(1)


if __name__ == "__main__":
    main()
