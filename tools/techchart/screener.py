"""Daily swing scan: the user's watchlist plus the top 5 technically and
fundamentally strong stocks from the Nifty 50, Nifty Midcap 100 and Nifty
Smallcap 100.

Every number comes from downloaded data. When an index list, prices or
fundamentals cannot be fetched, the summary says so instead of guessing.

Usage: python screener.py [--out out] [--watchlist watchlist.txt]
"""
import argparse
import datetime as dt
import io
import os
import sys
import urllib.request

import pandas as pd
import yfinance as yf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import techchart as tc  # noqa: E402

INDICES = [
    ("Nifty 50", "ind_nifty50list.csv"),
    ("Midcap (Nifty Midcap 100)", "ind_niftymidcap100list.csv"),
    ("Smallcap (Nifty Smallcap 100)", "ind_niftysmallcap100list.csv"),
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
    return {"score": score, "rec": rec, "close": float(last["Close"]), "turnover": turnover,
            "risk": (mid - setup["stop_cons"]) / mid}


def fundamentals(symbol):
    """Score from Yahoo Finance fundamentals; None when the data is missing."""
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--watchlist", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "watchlist.txt"))
    args = ap.parse_args()
    today = dt.date.today().isoformat()
    lines = [f"# Mehitha daily stock scan, {today}", "",
             "Prices and fundamentals from Yahoo Finance (end of day). Not investment advice; check before you trade.", ""]

    # Watchlist: full chart for each stock.
    watch = []
    if os.path.exists(args.watchlist):
        watch = [w.strip() for w in open(args.watchlist, encoding="utf-8") if w.strip() and not w.startswith("#")]
    lines += ["## Your watchlist", ""]
    if not watch:
        lines += ["Watchlist is empty. Tell Mehitha which stocks to track.", ""]
    else:
        lines += ["| Stock | Close | Verdict | Grade | Entry | Stop-loss (risk) | Target 1 | Chart |", "|---|---|---|---|---|---|---|---|"]
        for w in watch:
            try:
                symbol, name = tc.resolve_symbol(w)
                r = tc.run(symbol, name, args.out)
                lines.append(f"| {name} | ₹{r['close']:.1f} | {r['rec']} | {r['grade']} | {setup_cells(r)} | "
                             f"{os.path.relpath(r['png'], args.out)} |")
            except BaseException as e:  # techchart raises SystemExit on bad or stale data
                lines.append(f"| {w} | – | NO DATA | – | – | – | – | {str(e)[:120]} |")
        lines.append("")

    # Index picks.
    for title, csv_name in INDICES:
        lines += [f"## Top {PICKS}: {title}", ""]
        symbols, err = constituents(csv_name)
        if not symbols:
            lines += [f"Could not get the index list ({err[:200]}). No picks today.", ""]
            continue
        prices = download(symbols)
        if len(prices) < len(symbols) * 0.6:
            lines += [f"Price data came back for only {len(prices)} of {len(symbols)} stocks. No picks today.", ""]
            continue
        scored = []
        for s, frame in prices.items():
            try:
                t = tech_score(frame)
            except Exception:
                continue
            if t["turnover"] >= MIN_TURNOVER_CR and t["score"] >= MIN_TECH_SCORE and t["risk"] <= MAX_RISK:
                scored.append((s, t))
        scored.sort(key=lambda x: -x[1]["score"])
        picks = []
        for s, t in scored[:SHORTLIST]:
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
                r = tc.run(s, f["name"], args.out)
                verdict_cell, cells = r["rec"], setup_cells(r)
            except BaseException:
                verdict_cell, cells = t["rec"], "– | – | –"
            lines.append(f"| {i} | {f['name']} ({s[:-3]}) | ₹{t['close']:.1f} | {verdict_cell} | {cells} | {pct(f['roe'])} | "
                         f"{num(None if f['de'] is None else f['de'] / 100, '{:.2f}')} | {pct(f['eg'])} | {pct(f['rg'])} | {num(f['pe'])} |")
        lines += ["", f"Scanned {len(prices)} stocks; {len(scored)} had a buy setup with risk under {100 * MAX_RISK:.0f}% and ₹{MIN_TURNOVER_CR} cr+ daily turnover.", ""]

    os.makedirs(os.path.join(args.out, "daily"), exist_ok=True)
    path = os.path.join(args.out, "daily", f"{today}.md")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(path)


if __name__ == "__main__":
    main()
