"""Nifty 100 swing dashboard: the Technical chart analysis for every Nifty 100 stock.

For each stock it runs techchart.run (EMA20/50/200, MACD, RSI, swing structure, patterns,
candles, key levels, entry / stop-loss / targets and the final BUY NOW / BUY ON DIP /
WAIT / AVOID verdict with confidence and grade) and the breakout rules from
breakout_rules.json (breakouts.check), then writes a static website:

    site/index.html          the dashboard (copied from tools/techchart/site/)
    site/data.json           every stock's numbers, verdict and breakout status
    site/charts/<SYM>.png    the annotated Technical chart
    site/reports/<SYM>.md    the full written analysis

Every number comes from downloaded prices (Yahoo Finance, which can be about 15 minutes
behind NSE). A stock whose data can't be fetched is listed under "errors", never guessed.
Analysis only: this never places, changes or cancels an order.

Usage: python dashboard.py --out site [--cache nifty100.txt] [--limit N]
"""
import argparse
import datetime as dt
import json
import os
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import breakouts as bo  # noqa: E402
import techchart as tc  # noqa: E402


def zone(z):
    return [round(float(z[0]), 2), round(float(z[1]), 2)]


def summarise(r):
    s, lv = r["setup"], r["levels"]
    sym = r["symbol"].replace(".NS", "").replace(".BO", "")
    verdict = next(k for k in ("BUY NOW", "BUY ON DIP", "WAIT", "AVOID") if k in r["rec"])
    return {
        "symbol": sym, "date": r["date"], "close": round(r["close"], 2), "prev_close": round(r["prev_close"], 2),
        "change_pct": round(100 * (r["close"] / r["prev_close"] - 1), 2),
        "day_high": round(r["day_high"], 2), "day_low": round(r["day_low"], 2),
        "verdict": verdict, "confidence": int(r["conf"]), "grade": r["grade"], "score": float(r["score"]),
        "reasons": r["reasons"], "next": r["next"],
        "trend": r["trend"], "h4_trend": r["h4_trend"], "rsi": round(r["rsi"], 1), "macd_up": bool(r["macd_up"]),
        "ema": {"20": round(r["ema20"], 2), "50": round(r["ema50"], 2), "200": round(r["ema200"], 2)},
        "entry": zone(s["entry"]), "breakout_entry": zone(s["alt"]),
        "stop": round(float(s["stop_cons"]), 2), "stop_aggressive": round(float(s["stop_aggr"]), 2),
        "targets": [float(t) for t in s["targets"]], "rr1": round(float(s["rr1"]), 2),
        "levels": {"strong_support": zone(lv["strong"]), "support": zone(lv["immediate"]),
                   "resistance": zone(lv["resistance"]), "breakout": round(float(lv["breakout"]), 2),
                   "high_52w": round(float(lv["hi52"]), 2)},
        "patterns": r["patterns"], "candles": r["candles"], "divergences": r["divergences"],
        "closes": r["closes"],
        "chart": f"charts/{sym}.png", "report": f"reports/{sym}.md",
    }


def breakout_status(c):
    if c is None:
        return None
    status = "breakout" if c["passes"] else "near" if c["near"] else "crossed" if c["crossed"] else None
    return {"status": status, "level": round(c["level"], 2), "vol_ratio": round(c["vol_ratio"], 2),
            "gap_pct": round(100 * (1 - c["price"] / c["level"]), 2), "failed": c["failed"],
            "rs_vs_nifty": None if c["rs"] is None else round(c["rs"], 1)}


MIDCAP_CSV = "ind_niftymidcap150list.csv"
MIDCAP_PICKS = 20


def midcap_picks(n=MIDCAP_PICKS):
    """Top n Nifty Midcap 150 stocks by the daily scan's own ranking (screener.py): technical score
    plus fundamentals (ROE, debt/equity, earnings and revenue growth, margins, P/E) plus 6-month
    performance, among stocks trading at least ₹5 crore a day. Returns ([(symbol, fundamentals, rank score)], notes)."""
    import screener as sc
    symbols, err = sc.constituents(MIDCAP_CSV)
    if not symbols:
        return [], [f"Midcap 150 list unavailable ({(err or '')[:160]}); no midcap picks this run."]
    prices = sc.download(symbols)
    if len(prices) < 0.6 * len(symbols):
        return [], [f"Midcap prices came back for only {len(prices)} of {len(symbols)} stocks; no midcap picks this run."]
    scored = []
    for s, frame in prices.items():
        try:
            t = sc.tech_score(frame)
        except Exception:
            continue
        if t["turnover"] >= sc.MIN_TURNOVER_CR:
            scored.append((s, t))
    best = sc.strongest(scored, n)
    notes = [] if len(best) >= n else [f"Only {len(best)} midcap stocks had fundamentals data (wanted {n})."]
    return [(s, sc._FUND.get(s) or {}, rank) for rank, s, _ in best], notes


def fund_summary(f, rank):
    def r(v, k=1):
        return None if v is None else round(float(v) * k, 1)
    return {"rank_score": rank, "score": f.get("score"), "name": f.get("name"), "roe_pct": r(f.get("roe"), 100),
            "debt_equity": r(None if f.get("de") is None else f["de"] / 100, 1), "earnings_growth_pct": r(f.get("eg"), 100),
            "revenue_growth_pct": r(f.get("rg"), 100), "pe": r(f.get("pe"))}


def analyse_all(symbols, out, work, label):
    rows, errors = [], []
    for i, s in enumerate(symbols):
        try:
            r = tc.run(s, s.replace(".NS", ""), work)
            row = summarise(r)
            shutil.copy(r["png"], os.path.join(out, row["chart"]))
            shutil.copy(r["md"], os.path.join(out, row["report"]))
            rows.append(row)
            print(f"[{label} {i + 1}/{len(symbols)}] {row['symbol']}: {row['close']} ({row['change_pct']:+.2f}%) {row['verdict']}")
        except SystemExit as e:
            errors.append({"symbol": s.replace(".NS", ""), "error": str(e)})
        except Exception as e:  # one bad stock must not stop the rest
            errors.append({"symbol": s.replace(".NS", ""), "error": f"{type(e).__name__}: {e}"})
        time.sleep(0.2)  # be gentle with Yahoo
    return rows, errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site")
    ap.add_argument("--cache", default="", help="last good Nifty 100 list (nifty100.txt from the breakouts branch)")
    ap.add_argument("--rules", default=os.path.join(HERE, "breakout_rules.json"))
    ap.add_argument("--limit", type=int, default=0, help="only the first N stocks (for a quick test)")
    ap.add_argument("--skip-midcap", action="store_true", help="leave out the Midcap top 20")
    args = ap.parse_args()
    rules = bo.load_rules(args.rules)
    out = args.out
    for sub in ("charts", "reports"):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
    # The page plus its app files (manifest, offline worker, icons) so it installs on a phone.
    shutil.copytree(os.path.join(HERE, "site"), out, dirs_exist_ok=True)
    now = dt.datetime.now(bo.IST)
    notes = []

    symbols, note = bo.index_symbols(rules, args.cache)
    if note:
        notes.append(note)
    if args.limit:
        symbols = symbols[:args.limit]

    # Breakout rules need one batch download plus the Nifty 50.
    checks, market = {}, None
    try:
        frames, _ = bo.download(symbols) if symbols else ({}, {})
        market = bo.market_of(frames.pop(bo.NIFTY, None))
        live = now.weekday() < 5 and bo.OPEN <= now.time() < bo.CLOSE
        elapsed = (now - now.replace(hour=9, minute=15, second=0, microsecond=0)).total_seconds() / 60
        frac = min(max(elapsed / bo.SESSION_MIN, 0.25), 1.0) if live else 1.0
        for s, f in frames.items():
            try:
                checks[s] = bo.check(s, f, rules, frac, market)
            except Exception as e:
                print(f"breakout check {s}: {e}", file=sys.stderr)
    except Exception as e:
        notes.append(f"Breakout check unavailable this run ({type(e).__name__}).")

    work = os.path.join(out, "_work")
    rows, errors = analyse_all(symbols, out, work, "Nifty 100")
    for row in rows:
        row["breakout"] = breakout_status(checks.get(row["symbol"] + ".NS"))

    midcap = {"stocks": [], "errors": [], "notes": []}
    if not args.skip_midcap:
        try:
            picks, mnotes = midcap_picks()
        except Exception as e:
            picks, mnotes = [], [f"Midcap picks unavailable this run ({type(e).__name__})."]
        midcap["notes"] = mnotes
        mrows, merrors = analyse_all([p[0] for p in picks], out, work, "Midcap")
        info = {p[0].replace(".NS", ""): fund_summary(p[1], p[2]) for p in picks}
        for row in mrows:
            row["fundamentals"] = info.get(row["symbol"])
            if row["fundamentals"] and row["fundamentals"].get("name"):
                row["name"] = row["fundamentals"]["name"]
        mrows.sort(key=lambda r: -(r["fundamentals"] or {}).get("rank_score", 0))
        midcap.update(stocks=mrows, errors=merrors)
    shutil.rmtree(work, ignore_errors=True)

    data = {"generated_at": now.isoformat(timespec="minutes"),
            "source": "Yahoo Finance (NSE). Prices can be about 15 minutes behind.",
            "market": None if market is None else {k: (round(v, 4) if isinstance(v, float) else bool(v)) for k, v in market.items()},
            "rules": {k: rules[k] for k in ("volume_multiple", "require_above_ema", "max_below_52w_high_pct") if k in rules},
            "stocks": rows, "errors": errors, "notes": notes, "midcap": midcap}
    with open(os.path.join(out, "data.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    if symbols:
        with open(os.path.join(out, "nifty100.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(symbols) + "\n")
    print(f"{len(rows)} analysed, {len(errors)} failed; notes: {notes}")
    if not rows:
        sys.exit(1)


if __name__ == "__main__":
    main()
