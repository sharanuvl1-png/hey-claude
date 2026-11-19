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


def portfolio_symbols():
    """NSE symbols of the owner's stocks: portfolio_symbols.txt (symbols only, no quantities), else site/pf.json."""
    try:
        with open(os.path.join(HERE, "portfolio_symbols.txt"), encoding="utf-8") as f:
            syms = [x.strip().upper() for x in f if x.strip() and not x.startswith("#")]
        if syms:
            return syms
    except OSError:
        pass
    try:
        with open(os.path.join(HERE, "site", "pf.json"), encoding="utf-8") as f:
            return [h["sym"].upper() for h in json.load(f).get("stocks", []) if h.get("sym")]
    except (OSError, ValueError, KeyError):
        return []


def nse_official(today):
    """(date, {symbol: close}) from NSE's end-of-day bhavcopy for the latest session it has published,
    or (None, {}) when NSE can't be reached. A weekday with no file was a holiday (or isn't published yet)."""
    import csv
    import io
    import urllib.request
    import zipfile
    hdr = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
           "Accept": "*/*", "Referer": "https://www.nseindia.com/"}
    for back in range(0, 10):
        d = today - dt.timedelta(days=back)
        if d.weekday() >= 5:
            continue
        url = f"https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_{d:%Y%m%d}_F_0000.csv.zip"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=hdr), timeout=20) as r:
                raw = r.read()
            z = zipfile.ZipFile(io.BytesIO(raw))
            text = z.read(z.namelist()[0]).decode("utf-8", "replace")
        except Exception:
            continue
        closes = {}
        for row in csv.DictReader(io.StringIO(text)):
            if row.get("SctySrs") in ("EQ", "BE", "BZ") and row.get("ClsPric"):
                try:
                    closes[row["TckrSymb"].strip()] = float(row["ClsPric"])
                except ValueError:
                    pass
        if closes:
            return d.isoformat(), closes
    return None, {}


def official_check(rows, now):
    """Compare the board's closes with NSE's official closes for the same day; mark the last trading day."""
    try:
        day, closes = nse_official(now.date())
    except Exception as e:
        return {"error": type(e).__name__}
    if not day:
        return {"error": "NSE bhavcopy not reachable"}
    same, mism, stale = [], [], []
    for r in rows:
        c = closes.get(r["symbol"])
        if c is None:
            continue
        if r["date"] == day:
            r["nse_close"] = c
            same.append(r)
            if abs(r["close"] / c - 1) > 0.005:
                mism.append({"symbol": r["symbol"], "board": r["close"], "nse": c})
        elif r["date"] < day:
            stale.append(r["symbol"])
    today = now.date().isoformat()
    last = today if any(r["date"] == today for r in rows) and today > day else day
    print(f"NSE official close {day}: {len(same)} checked, {len(mism)} differ, {len(stale)} older than NSE's day; last trading day {last}")
    return {"date": day, "last_trading_day": last, "checked": len(same), "n_mismatch": len(mism), "mismatches": mism[:40],
            "n_stale": len(stale), "stale": stale[:40]}


GLOBAL = [("^GSPC", "S&P 500"), ("^IXIC", "Nasdaq"), ("^DJI", "Dow Jones"), ("^N225", "Nikkei"),
          ("^HSI", "Hang Seng"), ("BZ=F", "Brent crude"), ("INR=X", "USD/INR")]
NEWS_RSS = "https://news.google.com/rss/search?q=Indian+stock+market+Sensex+Nifty+when:1d&hl=en-IN&gl=IN&ceid=IN:en"


def _last_two(sym):
    """(date, close, % change vs previous close) from Yahoo daily bars, or None."""
    import yfinance as yf
    d = yf.Ticker(sym).history(period="10d", interval="1d", auto_adjust=False)
    d = d.dropna(subset=["Close"])
    if len(d) < 2:
        return None
    c, p = float(d["Close"].iloc[-1]), float(d["Close"].iloc[-2])
    return {"date": d.index[-1].date().isoformat(), "close": round(c, 2), "chg_pct": round((c / p - 1) * 100, 2)}


def regime_of(nf):
    """Nifty 50 mood from real daily closes: up (trending above a rising 50 EMA), weak (below the 50 EMA)
    or sideways (stuck in a narrow 20-day range close to the 50 EMA)."""
    if nf is None or len(nf) < 60:
        return None
    c = nf["Close"].astype(float)
    ema50 = c.ewm(span=50, adjust=False).mean()
    last, e = float(c.iloc[-1]), float(ema50.iloc[-1])
    hi20, lo20 = float(nf["High"].iloc[-20:].max()), float(nf["Low"].iloc[-20:].min())
    rng = (hi20 / lo20 - 1) * 100
    gap = (last / e - 1) * 100
    slope = (e / float(ema50.iloc[-11]) - 1) * 100
    if rng < 4.5 and abs(gap) < 2 and abs(slope) < 1:
        mood = "sideways"
    elif last < e:
        mood = "weak"
    else:
        mood = "up"
    return {"mood": mood, "date": nf.index[-1].date().isoformat(), "close": round(last, 2), "ema50": round(e, 2),
            "gap_pct": round(gap, 2), "range20_pct": round(rng, 2), "high20": round(hi20, 2), "low20": round(lo20, 2),
            "ema50_slope10_pct": round(slope, 2)}


def news_headlines(n=6):
    import urllib.request
    import xml.etree.ElementTree as ET
    req = urllib.request.Request(NEWS_RSS, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=20) as r:
        root = ET.fromstring(r.read())
    out = []
    for it in root.iter("item"):
        t = (it.findtext("title") or "").strip()
        src = (it.findtext("source") or "").strip()
        if src and t.endswith(" - " + src):
            t = t[: -len(src) - 3]
        if t:
            out.append({"title": t, "source": src, "link": (it.findtext("link") or "").strip(),
                        "published": (it.findtext("pubDate") or "").strip()})
        if len(out) >= n:
            break
    return out


def market_context(nf, now):
    """Nifty mood, India VIX, overnight global markets and morning headlines. Anything that can't be
    fetched is left out (never guessed)."""
    ctx = {"fetched_at": now.isoformat(timespec="minutes"), "regime": None, "vix": None, "global": [], "news": [],
           "errors": []}
    try:
        ctx["regime"] = regime_of(nf)
    except Exception as e:
        ctx["errors"].append(f"regime: {type(e).__name__}")
    try:
        v = _last_two("^INDIAVIX")
        if v:
            v["level"] = "high" if v["close"] >= 20 else "elevated" if v["close"] >= 16 else "calm"
        ctx["vix"] = v
    except Exception as e:
        ctx["errors"].append(f"vix: {type(e).__name__}")
    for sym, name in GLOBAL:
        try:
            g = _last_two(sym)
            if g:
                ctx["global"].append(dict(g, symbol=sym, name=name))
        except Exception as e:
            ctx["errors"].append(f"{sym}: {type(e).__name__}")
    try:
        ctx["news"] = news_headlines()
    except Exception as e:
        ctx["errors"].append(f"news: {type(e).__name__}")
    r, v = ctx["regime"], ctx["vix"]
    mood = r["mood"] if r else None
    if mood == "weak" or (v and v["level"] == "high"):
        ctx["size"] = "half"
    elif mood == "sideways" or (v and v["level"] == "elevated"):
        ctx["size"] = "reduced"
    elif mood == "up":
        ctx["size"] = "normal"
    else:
        ctx["size"] = None
    print(f"Market context: mood {mood}, VIX {v and v['close']}, {len(ctx['global'])} global, {len(ctx['news'])} headlines, "
          f"size {ctx['size']}, errors {ctx['errors']}")
    return ctx


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
            if r.get("bars"):
                os.makedirs(os.path.join(out, "bars"), exist_ok=True)
                with open(os.path.join(out, "bars", f"{row['symbol']}.json"), "w") as f:
                    json.dump(r["bars"], f, separators=(",", ":"))
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
    checks, market, nifty, nf = {}, None, None, None
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
    # The owner's holdings (site/pf.json) that none of the tabs above scan: same chart rules, shown only
    # on the My portfolio tab. Not part of the history or track record.
    portfolio = {"stocks": [], "errors": [], "notes": []}
    held = portfolio_symbols()
    done = {r["symbol"] for r in rows + midcap["stocks"] + smallcap["stocks"] + sr_midcap_more["stocks"]}
    todo = sorted({h.upper() for h in held} - done)
    if todo and not args.limit:
        prow, perr = analyse_all([t + ".NS" for t in todo], out, work, "Portfolio", prices)
        for row in prow:
            row["group"] = "pf"
        portfolio.update(stocks=prow, errors=perr)
    # Watched stocks (the WATCHLIST secret plus the stocks the phone app asks to watch): scanned for the
    # alerts only when no tab covers them. Not shown on the board.
    import push
    watch = {w.strip().upper() for w in os.environ.get("WATCHLIST", "").replace(";", ",").split(",") if w.strip()}
    h_watch = {}
    if args.history and os.environ.get("NTFY_TOPIC"):
        import history as hi0
        h_watch = hi0.load(args.history)
        watch |= push.watch_from_phone(os.environ["NTFY_TOPIC"], h_watch, now)
    watched_rows = []
    wtodo = sorted(watch - done - {r["symbol"] for r in portfolio["stocks"]})[:30]
    if wtodo and not args.limit:
        watched_rows, _ = analyse_all([t + ".NS" for t in wtodo], out, work, "Watchlist", None)
        for row in watched_rows:
            row["group"] = "watch"
    print(f"Watching {len(watch)} stock(s): {', '.join(sorted(watch)) or 'none'}")
    shutil.rmtree(work, ignore_errors=True)
    try:
        context = market_context(nf, now)
    except Exception as e:
        context = {"errors": [type(e).__name__]}

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
        record["auto_paper"] = hi.auto_paper(h, prices)
        # Support & Resistance tab: its own signals and track record (Nifty 100 + Midcap top 30).
        hi.sr_record(h, rows + midcap["stocks"] + sr_midcap_more["stocks"])
        sr_record = hi.sr_track_record(h, prices, nifty)
        hi.save(h, args.history)
        if args.alerts:
            if h_watch.get("watch"):
                h["watch"] = h_watch["watch"]
            results = {r["symbol"]: r.get("results_date") for r in every + sr_midcap_more["stocks"] if r.get("results_date")}
            for sym in sorted(set(held) | watch):
                if sym not in results and not args.limit:
                    rd = results_date(sym, today)
                    if rd:
                        results[sym] = rd
            board = {"n100": rows, "mid": midcap["stocks"] + sr_midcap_more["stocks"], "small": smallcap["stocks"],
                     "pf": portfolio["stocks"], "watch": watched_rows}
            alerts = push.build(h, now, board, set(held), watch, context, results)
            hi.save(h, args.history)
            with open(args.alerts, "w", encoding="utf-8") as f:
                json.dump(alerts, f)

    seen, board_rows = set(), []
    for r in rows + midcap["stocks"] + smallcap["stocks"] + sr_midcap_more["stocks"] + portfolio["stocks"]:
        if r["symbol"] not in seen:
            seen.add(r["symbol"])
            board_rows.append(r)
    official = official_check(board_rows, now)
    data = {"generated_at": now.isoformat(timespec="minutes"),
            "source": "Yahoo Finance (NSE). Prices can be about 15 minutes behind.",
            "market": None if market is None else {k: (round(v, 4) if isinstance(v, float) else bool(v)) for k, v in market.items()},
            "rules": {k: rules[k] for k in ("volume_multiple", "require_above_ema", "max_below_52w_high_pct") if k in rules},
            "stocks": rows, "errors": errors, "notes": notes, "midcap": midcap, "smallcap": smallcap,
            "sr_midcap_more": sr_midcap_more, "portfolio": portfolio, "official": official, "context": context, "changes": changes, "track_record": record,
            "sr_track_record": sr_record}
    with open(os.path.join(out, "data.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    if symbols:
        with open(os.path.join(out, "nifty100.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(symbols) + "\n")
    print(f"{len(rows)} analysed, {len(errors)} failed; midcap {len(midcap['stocks'])} (+{len(sr_midcap_more['stocks'])} for S/R), smallcap {len(smallcap['stocks'])}, portfolio {len(portfolio['stocks'])}/{len(portfolio['stocks']) + len(portfolio['errors'])}; notes: {notes}")
    if not rows:
        sys.exit(1)


if __name__ == "__main__":
    main()
