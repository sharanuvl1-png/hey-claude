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
                   "high_52w": round(float(lv["hi52"]), 2),
                   # how many swing lows / highs make up each zone (0 = no real touches; a fallback zone from ATR)
                   "touches": {"support": int(lv["immediate"][2]), "strong_support": int(lv["strong"][2]),
                               "resistance": int(lv["resistance"][2])}},
        "patterns": r["patterns"], "candles": r["candles"], "divergences": r["divergences"],
        "closes": r["closes"], "volume_ratio": round(float(r.get("volume_ratio") or 0), 2),
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
SMALLCAP_CSV = "ind_niftysmallcap250list.csv"
NIFTY100_CSV = "ind_nifty100list.csv"
MIDCAP_PICKS = 20
SR_MIDCAP = 30   # the Support & Resistance tab covers the Midcap top 30 (the Midcap tab's 20, plus ranks 21-30)


def industries(csv_name):
    """{SYMBOL: industry} from NSE's own index list (the Industry column); {} if the list can't be fetched."""
    import io
    import urllib.request
    import pandas as pd
    import screener as sc
    for base in sc.SOURCES:
        try:
            req = urllib.request.Request(base + csv_name, headers={"User-Agent": "Mozilla/5.0", "Accept": "text/csv,*/*"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                frame = pd.read_csv(io.StringIO(resp.read().decode("utf-8", "replace")))
            return {str(r["Symbol"]).strip(): str(r["Industry"]).strip() for _, r in frame.iterrows()
                    if isinstance(r.get("Industry"), str)}
        except Exception:
            continue
    return {}


def results_date(symbol, today):
    """Next quarterly results date from Yahoo Finance's calendar (can be an estimate), within 45 days; None if unknown."""
    import yfinance as yf
    try:
        cal = yf.Ticker(symbol + ".NS").calendar or {}
        dates = cal.get("Earnings Date") or [] if isinstance(cal, dict) else []
        future = sorted(d.date() if isinstance(d, dt.datetime) else d for d in dates if d is not None)
        future = [d for d in future if isinstance(d, dt.date) and 0 <= (d - today).days <= 45]
        return future[0].isoformat() if future else None
    except Exception:
        return None


def midcap_picks(n=MIDCAP_PICKS, csv=MIDCAP_CSV, label="Midcap 150", extra=0):
    """Top n stocks of an index by the daily scan's own ranking (screener.py): technical score
    plus fundamentals (ROE, debt/equity, earnings and revenue growth, margins, P/E) plus 6-month
    performance, among stocks trading at least ₹5 crore a day. Returns ([(symbol, fundamentals, rank score)], notes).
    extra > 0 also returns the next `extra` names in rank order after the first n (the first n are unchanged)."""
    import screener as sc
    symbols, err = sc.constituents(csv)
    if not symbols:
        return [], [f"{label} list unavailable ({(err or '')[:160]}); no picks this run."]
    prices = sc.download(symbols)
    if len(prices) < 0.6 * len(symbols):
        return [], [f"{label} prices came back for only {len(prices)} of {len(symbols)} stocks; no picks this run."]
    scored = []
    for s, frame in prices.items():
        try:
            t = sc.tech_score(frame)
        except Exception:
            continue
        if t["turnover"] >= sc.MIN_TURNOVER_CR:
            scored.append((s, t))
    best = sc.strongest(scored, n + extra, pool_size=max(40, 3 * n))
    notes = [] if len(best) >= n else [f"Only {len(best)} {label} stocks had fundamentals data (wanted {n})."]
    return [(s, sc._FUND.get(s) or {}, rank) for rank, s, _ in best], notes


def fund_summary(f, rank):
    def r(v, k=1):
        return None if v is None else round(float(v) * k, 1)
    return {"rank_score": rank, "score": f.get("score"), "name": f.get("name"), "roe_pct": r(f.get("roe"), 100),
            "debt_equity": r(None if f.get("de") is None else f["de"] / 100, 1), "earnings_growth_pct": r(f.get("eg"), 100),
            "revenue_growth_pct": r(f.get("rg"), 100), "pe": r(f.get("pe"))}


def analyse_all(symbols, out, work, label, prices=None):
    rows, errors = [], []
    for i, s in enumerate(symbols):
        try:
            r = tc.run(s, s.replace(".NS", ""), work)
            row = summarise(r)
            if prices is not None:
                prices[row["symbol"]] = (r["close_dates"], r["closes"])
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


def picks_section(csv, label, out, work, prices, skip, extra=0, extra_out=None):
    """A ranked picks tab (Midcap or Smallcap): top 20 by the scan's ranking, each run through the chart rules.
    With extra > 0 the next `extra` names (ranks 21, 22, ...) are analysed too and put in extra_out
    (a dict like the section itself); the top 20 are exactly the same as without it."""
    sec = {"stocks": [], "errors": [], "notes": []}
    if skip:
        return sec
    try:
        picks, pnotes = midcap_picks(MIDCAP_PICKS, csv, label, extra)
    except Exception as e:
        picks, pnotes = [], [f"{label} picks unavailable this run ({type(e).__name__})."]
    picks, more = picks[:MIDCAP_PICKS], picks[MIDCAP_PICKS:]
    sec["notes"] = pnotes
    rows, errs = analyse_all([p[0] for p in picks], out, work, label, prices)
    _add_fund(rows, picks)
    sec.update(stocks=rows, errors=errs)
    if extra_out is not None:
        rows2, errs2 = analyse_all([p[0] for p in more], out, work, f"{label} ranks {MIDCAP_PICKS + 1}+", prices)
        _add_fund(rows2, more)
        extra_out.update(stocks=rows2, errors=errs2, notes=[] if len(more) >= extra or not picks else
                         [f"Only {len(more)} more {label} stocks had fundamentals data after the top {MIDCAP_PICKS} (wanted {extra})."])
    return sec


def _add_fund(rows, picks):
    info = {p[0].replace(".NS", ""): fund_summary(p[1], p[2]) for p in picks}
    for row in rows:
        row["fundamentals"] = info.get(row["symbol"])
        if row["fundamentals"] and row["fundamentals"].get("name"):
            row["name"] = row["fundamentals"]["name"]
    rows.sort(key=lambda r: -(r["fundamentals"] or {}).get("rank_score", 0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site")
    ap.add_argument("--cache", default="", help="last good Nifty 100 list (nifty100.txt from the breakouts branch)")
    ap.add_argument("--rules", default=os.path.join(HERE, "breakout_rules.json"))
    ap.add_argument("--limit", type=int, default=0, help="only the first N stocks (for a quick test)")
    ap.add_argument("--skip-midcap", action="store_true", help="leave out the Midcap and Smallcap top 20")
    ap.add_argument("--history", default="", help="history.json from the swing-history branch (read and updated)")
    ap.add_argument("--alerts", default="", help="write watchlist alerts (for the ntfy push) to this file")
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
    checks, market, nifty = {}, None, None
    try:
        frames, _ = bo.download(symbols) if symbols else ({}, {})
        nf = frames.pop(bo.NIFTY, None)
        market = bo.market_of(nf)
        if nf is not None and len(nf):
            nifty = ([ix.date().isoformat() for ix in nf.index], [round(float(v), 2) for v in nf["Close"]])
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
    prices = {}
    rows, errors = analyse_all(symbols, out, work, "Nifty 100", prices)
    for row in rows:
        row["breakout"] = breakout_status(checks.get(row["symbol"] + ".NS"))
    # Midcap ranks 21-30 are only for the Support & Resistance tab; the Midcap top 20 tab is unchanged.
    sr_midcap_more = {"stocks": [], "errors": [], "notes": []}
    midcap = picks_section(MIDCAP_CSV, "Midcap 150", out, work, prices, args.skip_midcap,
                           extra=SR_MIDCAP - MIDCAP_PICKS, extra_out=sr_midcap_more)
    smallcap = picks_section(SMALLCAP_CSV, "Smallcap 250", out, work, prices, args.skip_midcap)
    shutil.rmtree(work, ignore_errors=True)

    # Sector (NSE's own industry column) and the next results date for every stock on the board.
    sectors = {}
    for csv in (NIFTY100_CSV, MIDCAP_CSV, SMALLCAP_CSV):
        sectors.update(industries(csv))
    groups = (("n100", rows), ("mid", midcap["stocks"]), ("small", smallcap["stocks"]))
    today = now.date()
    for g, rs in groups:
        for row in rs:
            row["group"] = g
            row["sector"] = sectors.get(row["symbol"])
            row["results_date"] = results_date(row["symbol"], today)
            time.sleep(0.1)
    every = [r for _, rs in groups for r in rs]
    for row in sr_midcap_more["stocks"]:   # not part of the history or track record, which follow the tabs above
        row["group"] = "mid"
        row["sector"] = sectors.get(row["symbol"])
        row["results_date"] = results_date(row["symbol"], today)
        time.sleep(0.1)

    # What changed since the last scan, and how past calls did (needs the history branch).
    changes, record, sr_record = None, None, None
    if args.history:
        import history as hi
        h = hi.load(args.history)
        changes = hi.changes(h.get("last_scan"), every)
        hi.record(h, every, now.isoformat(timespec="minutes"))
        record = hi.track_record(h, prices, nifty)
        # Support & Resistance tab: its own signals and track record (Nifty 100 + Midcap top 30).
        hi.sr_record(h, rows + midcap["stocks"] + sr_midcap_more["stocks"])
        sr_record = hi.sr_track_record(h, prices, nifty)
        hi.save(h, args.history)
        if args.alerts:
            watch = {w.strip().upper() for w in os.environ.get("WATCHLIST", "").replace(";", ",").split(",") if w.strip()}
            alerts = []
            for m in changes["moves"]:
                if m["symbol"] in watch and m["to"] == "BUY NOW":
                    alerts.append({"id": f"board:{m['symbol']}:{today}:{m['to']}", "title": f"{m['symbol']} turned Buy now",
                                   "text": f"{m['symbol']} moved from {m['from'].title()} to Buy now on the swing board."})
            for sym in changes["new_breakouts"]:
                if sym in watch:
                    alerts.append({"id": f"board:{sym}:{today}:bo", "title": f"{sym} broke out",
                                   "text": f"{sym} passed every breakout rule on the swing board."})
            with open(args.alerts, "w", encoding="utf-8") as f:
                json.dump(alerts, f)

    data = {"generated_at": now.isoformat(timespec="minutes"),
            "source": "Yahoo Finance (NSE). Prices can be about 15 minutes behind.",
            "market": None if market is None else {k: (round(v, 4) if isinstance(v, float) else bool(v)) for k, v in market.items()},
            "rules": {k: rules[k] for k in ("volume_multiple", "require_above_ema", "max_below_52w_high_pct") if k in rules},
            "stocks": rows, "errors": errors, "notes": notes, "midcap": midcap, "smallcap": smallcap,
            "sr_midcap_more": sr_midcap_more, "changes": changes, "track_record": record,
            "sr_track_record": sr_record}
    with open(os.path.join(out, "data.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    if symbols:
        with open(os.path.join(out, "nifty100.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(symbols) + "\n")
    print(f"{len(rows)} analysed, {len(errors)} failed; midcap {len(midcap['stocks'])} (+{len(sr_midcap_more['stocks'])} for S/R), smallcap {len(smallcap['stocks'])}; notes: {notes}")
    if not rows:
        sys.exit(1)


if __name__ == "__main__":
    main()
