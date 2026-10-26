"""Daily swing scan: the user's watchlist plus the top 5 technically and
fundamentally strong stocks from the Nifty 50, Nifty Midcap 150 and Nifty
Smallcap 100.

The watchlist is the user's own list (watchlist.txt) plus an automatic list of
the strongest stocks (20 Nifty 50, 10 Midcap 150, 10 Smallcap 100), rebuilt
every Monday and saved as watchlist_auto.txt next to the charts.

Every number comes from downloaded data. When an index list, prices or
fundamentals cannot be fetched, the summary says so instead of guessing.

Usage: python screener.py [--out out] [--watchlist watchlist.txt]
"""
import argparse
import datetime as dt
import io
import os
import sys
import time
import urllib.request

import pandas as pd
import yfinance as yf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import techchart as tc  # noqa: E402

# (title, constituents file, stocks kept on the automatic watchlist)
INDICES = [
    ("Nifty 50", "ind_nifty50list.csv", 20),
    ("Midcap 150", "ind_niftymidcap150list.csv", 10),
    ("Smallcap 100", "ind_niftysmallcap100list.csv", 10),
]
SOURCES = ["https://www.niftyindices.com/IndexConstituent/", "https://archives.nseindia.com/content/indices/"]
MIN_TURNOVER_CR = 5      # skip stocks trading under ₹5 crore a day
MIN_TECH_SCORE = 3       # at least a "buy on dip" setup
MAX_RISK = 0.12          # entry-to-stop distance above 12% is too wide for a swing trade
SHORTLIST = 12           # best technical setups per index checked for fundamentals
PICKS = 5


def constituents(csv_name):
    errors = []
    for base in SOURCES:
        try:
            req = urllib.request.Request(base + csv_name, headers={"User-Agent": "Mozilla/5.0", "Accept": "text/csv,*/*"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                frame = pd.read_csv(io.StringIO(resp.read().decode("utf-8", "replace")))
            symbols = [s.strip() + ".NS" for s in frame["Symbol"].dropna()]
            if len(symbols) >= 40:
                return symbols, None
            errors.append(f"{base}: only {len(symbols)} rows")
        except Exception as e:
            errors.append(f"{base}: {e}")
    return [], "; ".join(errors)


def download(symbols):
    data = yf.download(symbols, period="2y", interval="1d", auto_adjust=False, group_by="ticker",
                       progress=False, threads=True)
    out = {}
    for s in symbols:
        try:
            frame = data[s][["Open", "High", "Low", "Close", "Volume"]].dropna()
        except Exception:
            continue
        if len(frame) < 210:
            continue
        age = (pd.Timestamp.now().normalize() - frame.index[-1].tz_localize(None).normalize()).days
        if age <= 6:
            out[s] = frame
    return out


def tech_score(frame):
    d = tc.analyse(frame)
    lv = tc.key_levels(d["df"], d["highs"], d["lows"])
    setup = tc.swing_setup(d["df"], pd.DataFrame(), lv, d["highs"], d["lows"])
    pats = tc.detect_patterns(d["df"], d["highs"], d["lows"])
    divs = tc.divergence(d["df"], d["highs"], d["lows"])
    cands = tc.candle_signals(d["df"])
    rec, conf, grade, reasons, nxt, score = tc.verdict(d, "n/a", pats, divs, cands, setup)
    last = d["df"].iloc[-1]
    turnover = float((d["df"]["Close"] * d["df"]["Volume"]).iloc[-20:].mean() / 1e7)
    mid = sum(setup["entry"]) / 2
    closes = d["df"]["Close"]
    ret6m = float(closes.iloc[-1] / closes.iloc[-126] - 1) if len(closes) > 126 else 0.0
    return {"score": score, "rec": rec, "close": float(last["Close"]), "turnover": turnover,
            "risk": (mid - setup["stop_cons"]) / mid, "ret6m": ret6m}


_FUND = {}


def fundamentals(symbol):
    """Score from Yahoo Finance fundamentals; None when the data is missing."""
    if symbol not in _FUND:
        _FUND[symbol] = _fundamentals(symbol)
        time.sleep(0.3)
    return _FUND[symbol]


def _fundamentals(symbol):
    try:
        info = yf.Ticker(symbol).info
    except Exception:
        return None
    roe, de = info.get("returnOnEquity"), info.get("debtToEquity")
    eg, rg = info.get("earningsGrowth"), info.get("revenueGrowth")
    pm, pe = info.get("profitMargins"), info.get("trailingPE")
    known = [v for v in (roe, de, eg, rg, pm) if v is not None]
    if len(known) < 3:
        return None
    score = 0
    if roe is not None:
        score += 2 if roe >= 0.15 else 1 if roe >= 0.10 else -1 if roe < 0.05 else 0
    if de is not None:  # Yahoo reports debt/equity in percent
        score += 1 if de < 50 else -1 if de > 150 else 0
    if eg is not None:
        score += 1 if eg >= 0.10 else -1 if eg < 0 else 0
    if rg is not None:
        score += 1 if rg >= 0.10 else -1 if rg < 0 else 0
    if pm is not None:
        score += 1 if pm >= 0.10 else -1 if pm < 0 else 0
    if pe is not None and (pe <= 0 or pe > 120):
        score -= 1
    name = info.get("longName") or info.get("shortName") or symbol
    return {"score": score, "roe": roe, "de": de, "eg": eg, "rg": rg, "pe": pe, "name": name}


def pct(v):
    return "n/a" if v is None else ">500%" if v > 5 else f"{100 * v:.0f}%"


def num(v, fmt="{:.0f}"):
    return "n/a" if v is None else fmt.format(v)


def setup_cells(r):
    s = r["setup"]
    mid = sum(s["entry"]) / 2
    return (f"₹{s['entry'][0]:.1f}–{s['entry'][1]:.1f} | ₹{s['stop_cons']:.1f} ({100 * (mid - s['stop_cons']) / mid:.0f}%) | "
            f"₹{s['targets'][0]:.1f}")


def perf_points(ret6m):
    return 2 if ret6m >= 0.20 else 1 if ret6m > 0 else -1 if ret6m < -0.10 else 0


def strongest(scored, n):
    """The n stocks with the best mix of trend, fundamentals and 6-month performance."""
    pool = sorted(scored, key=lambda x: -(x[1]["score"] + perf_points(x[1]["ret6m"])))[:max(40, 2 * n)]
    ranked = []
    for s, t in pool:
        if t["score"] < 1:
            continue
        f = fundamentals(s)
        if f is None or f["score"] < 2:
            continue
        ranked.append((t["score"] + f["score"] + perf_points(t["ret6m"]), s, f["name"]))
    ranked.sort(key=lambda x: -x[0])
    return ranked[:n]


def load_auto(path):
    """(generated date, [(group, symbol, name)]) from watchlist_auto.txt, or (None, [])."""
    if not os.path.exists(path):
        return None, []
    rows, made = [], None
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line.startswith("# generated "):
            made = dt.date.fromisoformat(line.split()[-1])
        elif line and not line.startswith("#"):
            group, sym, name = (line.split("|") + ["", ""])[:3]
            rows.append((group, sym, name or sym))
    return made, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--watchlist", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "watchlist.txt"))
    ap.add_argument("--refresh-watchlist", action="store_true")
    args = ap.parse_args()
    today = dt.date.today()
    lines = [f"# Mehitha daily stock scan, {today.isoformat()}", "",
             "Prices and fundamentals from Yahoo Finance (end of day). Not investment advice; check before you trade.", ""]
    notes = []

    # Score every stock in each index once.
    groups = []
    for title, csv_name, keep in INDICES:
        symbols, err = constituents(csv_name)
        if not symbols:
            groups.append((title, keep, None, f"Could not get the index list ({err[:200]})."))
            continue
        prices = download(symbols)
        if len(prices) < len(symbols) * 0.6:
            groups.append((title, keep, None, f"Price data came back for only {len(prices)} of {len(symbols)} stocks."))
            continue
        scored = []
        for s, frame in prices.items():
            try:
                t = tech_score(frame)
            except Exception:
                continue
            if t["turnover"] >= MIN_TURNOVER_CR:
                scored.append((s, t))
        groups.append((title, keep, scored, None))

    # Automatic watchlist: rebuilt on Mondays, when missing, or on request.
    auto_path = os.path.join(args.out, "watchlist_auto.txt")
    made, auto = load_auto(auto_path)
    if args.refresh_watchlist or made is None or today.weekday() == 0 or (today - made).days >= 7:
        fresh = []
        for title, keep, scored, err in groups:
            if scored is None:
                notes.append(f"{title}: {err} Kept last week's stocks for this group.")
                fresh += [r for r in auto if r[0] == title]
                continue
            best = strongest(scored, keep)
            if len(best) < keep:
                notes.append(f"{title}: only {len(best)} stocks passed the strength filters (wanted {keep}).")
            fresh += [(title, s, name) for _, s, name in best]
        auto = fresh
        made = today
        os.makedirs(args.out, exist_ok=True)
        with open(auto_path, "w", encoding="utf-8") as fh:
            fh.write(f"# Strongest stocks by trend, fundamentals and 6-month performance\n# generated {today.isoformat()}\n")
            fh.writelines(f"{g}|{s}|{n}\n" for g, s, n in auto)

    # Watchlist: full chart for each stock.
    mine = []
    if os.path.exists(args.watchlist):
        mine = [w.strip() for w in open(args.watchlist, encoding="utf-8") if w.strip() and not w.startswith("#")]
    watch = [("Your picks", w, None) for w in mine] + auto
    results = {}
    lines += ["## Watchlist", "",
              f"Your own stocks plus the strongest {', '.join(f'{k} {t}' for t, _, k in INDICES)} stocks "
              f"(list from {made.isoformat() if made else 'n/a'}, rebuilt every Monday).", ""]
    lines += ["| Group | Stock | Close | Verdict | Grade | Entry | Stop-loss (risk) | Target 1 | Chart |",
              "|---|---|---|---|---|---|---|---|---|"]
    for group, w, name in watch:
        try:
            symbol, found = tc.resolve_symbol(w)
            r = tc.run(symbol, name or found, args.out)
            results[symbol] = r
            lines.append(f"| {group} | {r['name']} | ₹{r['close']:.1f} | {r['rec']} | {r['grade']} | {setup_cells(r)} | "
                         f"{os.path.relpath(r['png'], args.out)} |")
        except BaseException as e:  # techchart raises SystemExit on bad or stale data
            lines.append(f"| {group} | {w} | – | NO DATA | – | – | – | – | {str(e)[:120]} |")
    lines += [""] + [f"- {n}" for n in notes] + ([""] if notes else [])

    # Today's buy setups per index.
    for title, _, scored, err in groups:
        lines += [f"## Top {PICKS} buy setups today: {title}", ""]
        if scored is None:
            lines += [f"{err} No picks today.", ""]
            continue
        ready = [(s, t) for s, t in scored if t["score"] >= MIN_TECH_SCORE and t["risk"] <= MAX_RISK]
        ready.sort(key=lambda x: -x[1]["score"])
        picks = []
        for s, t in ready[:SHORTLIST]:
            f = fundamentals(s)
            if f is None or f["score"] < 2:
                continue
            picks.append((t["score"] + f["score"], s, t, f))
        picks.sort(key=lambda x: -x[0])
        picks = picks[:PICKS]
        if not picks:
            lines += ["No stock passed both the technical and fundamental filters today.", ""]
            continue
        lines += ["| # | Stock | Close | Verdict | Entry | Stop-loss (risk) | Target 1 | ROE | Debt/Eq | Profit growth | Sales growth | P/E |",
                  "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for i, (_, s, t, f) in enumerate(picks, 1):
            try:
                r = results.get(s) or tc.run(s, f["name"], args.out)
                verdict_cell, cells = r["rec"], setup_cells(r)
            except BaseException:
                verdict_cell, cells = t["rec"], "– | – | –"
            lines.append(f"| {i} | {f['name']} ({s[:-3]}) | ₹{t['close']:.1f} | {verdict_cell} | {cells} | {pct(f['roe'])} | "
                         f"{num(None if f['de'] is None else f['de'] / 100, '{:.2f}')} | {pct(f['eg'])} | {pct(f['rg'])} | {num(f['pe'])} |")
        lines += ["", f"{len(scored)} liquid stocks scanned; {len(ready)} had a buy setup with risk under {100 * MAX_RISK:.0f}%.", ""]

    os.makedirs(os.path.join(args.out, "daily"), exist_ok=True)
    path = os.path.join(args.out, "daily", f"{today.isoformat()}.md")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(path)


if __name__ == "__main__":
    main()
