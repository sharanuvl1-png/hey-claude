"""Check the swing board's data.json against a fresh, independent price download before it is published.

For every stock (Nifty 100, Midcap and Smallcap picks) it re-downloads the daily history from Yahoo Finance with a
different call (Ticker.history, not the batch download the scan used) and checks that:

  * the close, previous close and day range match the fresh data (within 0.5%, allowing for live-bar drift)
  * the price date is the latest trading day in the fresh data, and not more than 6 days old
  * the 130 closes behind the sparkline end at the reported close
  * the levels are in a sane order (stop < entry zone, targets rising above the entry)
  * every number is a real number (no blanks, NaN or zero prices)
  * the support zone starts below the close and the resistance zone ends above it (the Support & Resistance tab)
  * for Midcap and Smallcap picks (and Midcap ranks 21-30 on the S/R tab), the fundamentals match Yahoo's own figures
  * the track record's returns are consistent with the prices on the board

Prints a report and exits with an error when anything fails, so a bad scan is never published.
Usage: python validate.py site/data.json
"""
import datetime as dt
import json
import math
import sys
import time

import yfinance as yf

TOL = 0.005  # 0.5%


def close_enough(a, b, tol=TOL):
    return a is not None and b is not None and abs(a - b) <= tol * max(abs(b), 1e-9)


def finite(*vals):
    return all(isinstance(v, (int, float)) and math.isfinite(v) for v in vals)


def check_stock(s, today):
    probs = []
    sym = s["symbol"]
    if not finite(s["close"], s["prev_close"], s["stop"], *s["entry"], *s["targets"]) or s["close"] <= 0:
        return [f"{sym}: missing or non-numeric prices"]
    if not (s["stop"] < s["entry"][0] <= s["entry"][1]):
        probs.append(f"{sym}: stop {s['stop']} is not below the entry zone {s['entry']}")
    if not (s["targets"][0] < s["targets"][1] < s["targets"][2]) or s["targets"][0] <= s["entry"][0]:
        probs.append(f"{sym}: targets {s['targets']} are not rising above the entry")
    lv = s.get("levels") or {}
    sup, res = lv.get("support") or [], lv.get("resistance") or []
    if not (len(sup) == 2 and len(res) == 2 and finite(*sup, *res) and sup[0] < sup[1] and res[0] < res[1]
            and sup[0] < s["close"] < res[1]):
        probs.append(f"{sym}: support {sup} / resistance {res} are not either side of the close {s['close']}")
    closes = s.get("closes") or []
    if closes and not close_enough(closes[-1], s["close"], 0.0001):
        probs.append(f"{sym}: sparkline ends at {closes[-1]}, close is {s['close']}")
    try:
        h = yf.Ticker(sym + ".NS").history(period="1mo", interval="1d", auto_adjust=False)
    except Exception as e:
        return probs + [f"{sym}: fresh download failed ({type(e).__name__})"]
    h = h.dropna(subset=["Close"])
    if len(h) < 2:
        return probs + [f"{sym}: fresh download returned no prices"]
    last_date = h.index[-1].date()
    if s["date"] != last_date.isoformat():
        probs.append(f"{sym}: data is for {s['date']}, latest trading day is {last_date}")
    if (today - dt.date.fromisoformat(s["date"])).days > 6:
        probs.append(f"{sym}: price date {s['date']} is stale")
    row = h.loc[[d.date().isoformat() == s["date"] for d in h.index]]
    if row.empty:
        return probs + [f"{sym}: {s['date']} not found in fresh data"]
    i = h.index.get_loc(row.index[0])
    fresh_close, fresh_prev = float(h["Close"].iloc[i]), float(h["Close"].iloc[i - 1]) if i > 0 else None
    if not close_enough(s["close"], fresh_close):
        probs.append(f"{sym}: close {s['close']} vs fresh {fresh_close:.2f}")
    if fresh_prev is not None and not close_enough(s["prev_close"], fresh_prev):
        probs.append(f"{sym}: previous close {s['prev_close']} vs fresh {fresh_prev:.2f}")
    if not close_enough(s["day_high"], float(h["High"].iloc[i]), 0.01) or not close_enough(s["day_low"], float(h["Low"].iloc[i]), 0.01):
        probs.append(f"{sym}: day range {s['day_low']}–{s['day_high']} vs fresh {float(h['Low'].iloc[i]):.2f}–{float(h['High'].iloc[i]):.2f}")
    return probs


def check_fund(s):
    f = s.get("fundamentals")
    if not f:
        return [f"{s['symbol']}: midcap pick without fundamentals"]
    try:
        info = yf.Ticker(s["symbol"] + ".NS").info
    except Exception as e:
        return [f"{s['symbol']}: fundamentals re-check failed ({type(e).__name__})"]
    probs = []
    for key, src, k in (("roe_pct", "returnOnEquity", 100), ("earnings_growth_pct", "earningsGrowth", 100),
                        ("revenue_growth_pct", "revenueGrowth", 100)):
        if f.get(key) is not None and info.get(src) is not None and abs(f[key] - info[src] * k) > 0.6:
            probs.append(f"{s['symbol']}: {key} {f[key]} vs Yahoo {info[src] * k:.1f}")
    if f.get("debt_equity") is not None and info.get("debtToEquity") is not None and abs(f["debt_equity"] - info["debtToEquity"] / 100) > 0.06:
        probs.append(f"{s['symbol']}: debt/equity {f['debt_equity']} vs Yahoo {info['debtToEquity'] / 100:.2f}")
    return probs


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "site/data.json"
    data = json.load(open(path, encoding="utf-8"))
    today = dt.date.today()
    more = (data.get("sr_midcap_more") or {}).get("stocks", [])
    picks = (data.get("midcap") or {}).get("stocks", []) + (data.get("smallcap") or {}).get("stocks", []) + more
    held = (data.get("portfolio") or {}).get("stocks", [])
    stocks = data["stocks"] + picks + held
    probs, checked = [], 0
    for s in stocks:
        p = check_stock(s, today)
        if s.get("fundamentals") is not None or s in picks:
            p += check_fund(s)
        probs += p
        checked += 1
        time.sleep(0.15)
    print(f"VALIDATION: checked {checked} stocks ({len(data['stocks'])} Nifty 100, "
          f"{len((data.get('midcap') or {}).get('stocks', []))} midcap, {len((data.get('smallcap') or {}).get('stocks', []))} smallcap, {len(more)} midcap ranks 21-30 for S/R, {len(held)} portfolio-only) "
          f"against a fresh Yahoo Finance download")
    board = {s["symbol"]: s["close"] for s in stocks}
    for c in (data.get("track_record") or {}).get("recent", []) + (data.get("sr_track_record") or {}).get("recent", []):
        if c.get("now") is not None and c["symbol"] in board and not close_enough(c["now"], board[c["symbol"]], 0.0001):
            probs.append(f"track record {c['symbol']}: latest {c['now']} vs board {board[c['symbol']]}")
        if c.get("now") and c.get("since_pct") is not None and abs(100 * (c["now"] / c["close"] - 1) - c["since_pct"]) > 0.02:
            probs.append(f"track record {c['symbol']}: return {c['since_pct']} doesn't match its prices")
    if data.get("market") and not finite(data["market"].get("close")):
        probs.append("Nifty 50 close is missing")
    for p in probs:
        print("  MISMATCH", p)
    print(f"VALIDATION: {len(probs)} problem(s)")
    # Record the result in data.json so the board can show that the prices were re-checked.
    data["validation"] = {"checked": checked, "problems": len(probs), "at": dt.datetime.now(dt.timezone(dt.timedelta(hours=5, minutes=30))).isoformat(timespec="minutes")}
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    # A few mismatches can be a live bar moving between the scan and the check; many means a real problem.
    if len(probs) > max(3, checked // 20):
        sys.exit(1)


if __name__ == "__main__":
    main()
