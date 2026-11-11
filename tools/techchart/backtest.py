"""Backtest of the breakout rules on past Nifty 100 prices, to tune them so they are
neither too strict (almost no alerts) nor too loose (many failed breakouts).

For every day in the test window and every stock, it records each breakout candidate
with its features (volume, trend, RSI, 52-week high distance, close quality, strength
vs Nifty, base depth, market trend), then scores each rule set on the same trades:

  entry  = close on the breakout day (the "broke out at the close" alert)
  stop   = 1 ATR under the breakout level, never more than 8% under the entry
  exits  = four ways, each scored separately:
           2R  : +2R target, the stop, or the close after 20 sessions
           3R  : +3R target, the stop, or the close after 30 sessions
           EMA : the stop, then exit on the first close under the 20-day EMA (max 60 sessions)
           10L : the stop, then exit on the first close under the prior 10-day low (max 60 sessions)
           (if a target and the stop fall on the same day, the stop is assumed)
  costs  = 0.2% of the entry per round trip

Uses today's Nifty 100 list (stocks that dropped out are missing, so results lean
slightly optimistic). Prices: Yahoo Finance daily, auto-adjusted for splits/bonuses.

    python backtest.py [--years 2] [--out backtest.md]
"""
import argparse
import datetime as dt
import os
import sys
from concurrent.futures import ProcessPoolExecutor

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import breakouts as bo  # noqa: E402

MAX_STOP, COST = 0.08, 0.002
LEVELS = ["resistance", "high20", "high52w"]
EXITS = ["2R", "3R", "EMA", "10L"]


def outcome(frame, ema20, t, level, atr, kind):
    """(R multiple, sessions held) of a trade entered at the close of bar t; None if still open."""
    entry = float(frame["Close"].iloc[t])
    stop = max(level - atr, entry * (1 - MAX_STOP))
    if stop >= entry:
        stop = entry - atr
    risk = entry - stop
    cost_r = COST * entry / risk
    lo, hi, cl = frame["Low"].values, frame["High"].values, frame["Close"].values
    target = entry + {"2R": 2, "3R": 3}.get(kind, 1e9) * risk
    hold = {"2R": 20, "3R": 30}.get(kind, 60)
    end = t + hold
    if end >= len(frame):
        return None
    for j in range(t + 1, end + 1):
        if lo[j] <= stop:
            return -1 - cost_r, j - t
        if hi[j] >= target:
            return (target - entry) / risk - cost_r, j - t
        if kind == "EMA" and cl[j] < ema20[j]:
            return (cl[j] - entry) / risk - cost_r, j - t
        if kind == "10L" and cl[j] < lo[j - 10:j].min():
            return (cl[j] - entry) / risk - cost_r, j - t
    return (cl[end] - entry) / risk - cost_r, hold


def scan_stock(args):
    sym, frame, nifty, start = args
    rows = []
    c, v = frame["Close"].values, frame["Volume"].values
    vavg = pd.Series(v).rolling(50).mean().shift(1).values
    ema20 = frame["Close"].ewm(span=20, adjust=False).mean().values
    for t in range(max(start, 260), len(frame)):
        # Cheap pre-filter: an up day on at least average volume (every rule set needs both).
        if not (c[t] > c[t - 1] and vavg[t] > 0 and v[t] >= vavg[t]):
            continue
        sub = frame.iloc[:t + 1]
        day = sub.index[-1]
        market = bo.market_of(nifty.loc[:day])
        for lv in LEVELS:
            rules = {"level": lv, "volume_multiple": 0, "volume_avg_days": 50, "require_above_ema": [],
                     "rsi_min": 0, "rsi_max": 100, "min_turnover_cr": 0, "stop_atr_below_level": 1.0, "base_days": 15}
            try:
                r = bo.check(sym, sub, rules, 1.0, market)
            except Exception:
                continue
            if not r or not r["crossed"]:
                continue
            res = {k: outcome(frame, ema20, t, r["level"], r["atr"], k) for k in EXITS}
            if any(x is None for x in res.values()):
                continue
            rows.append({"sym": sym, "date": day, "level_type": lv,
                         **{f"R_{k}": res[k][0] for k in EXITS}, **{f"days_{k}": res[k][1] for k in EXITS},
                         "vol": r["vol_ratio"], "rsi": r["rsi"], "a20": r["above"][20], "a50": r["above"][50],
                         "a200": r["above"][200], "e200up": r["ema200_rising"], "hi52": r["from_hi52_pct"],
                         "closepos": r["close_pos"], "rs": r["rs"] if r["rs"] is not None else np.nan,
                         "base": r["base_depth"], "ext": r["ext_pct"], "mkt_up": bool(market and market["up"]),
                         "turnover": r["turnover"]})
    return rows


# name -> (level type, filter on the candidate table)
def rule_sets():
    cur = lambda d: ((d.vol >= 1.5) & d.a50 & d.a200 & d.e200up & d.rsi.between(50, 80) & (d.hi52 <= 25)
                     & (d.closepos >= 0.5) & (d.rs >= 0) & (d.turnover >= 5))
    simple = lambda d: (d.vol >= 1.5) & d.a50 & d.a200 & d.e200up & (d.hi52 <= 25) & (d.rs >= 0) & (d.turnover >= 5)
    loose = lambda d: (d.vol >= 1.2) & d.a50 & (d.turnover >= 5)
    return {
        "Loose: volume 1.2x + above EMA50": ("resistance", loose),
        "Original: vol 1.5x, EMA50/200, RSI 50-75": ("resistance", lambda d: (d.vol >= 1.5) & d.a50 & d.a200 & d.rsi.between(50, 75) & (d.turnover >= 5)),
        "CURRENT (all filters)": ("resistance", cur),
        "Current, volume 1.3x": ("resistance", lambda d: cur(d.assign(vol=d.vol * 1.5 / 1.3))),
        "Current, volume 2.0x": ("resistance", lambda d: cur(d) & (d.vol >= 2.0)),
        "Current, no RSI filter": ("resistance", lambda d: cur(d.assign(rsi=60))),
        "Current, no strong-close filter": ("resistance", lambda d: cur(d.assign(closepos=1))),
        "Current, no beat-Nifty filter": ("resistance", lambda d: cur(d.assign(rs=1))),
        "Current, no EMA200-rising filter": ("resistance", lambda d: cur(d.assign(e200up=True))),
        "Current + not extended (<4%)": ("resistance", lambda d: cur(d) & (d.ext <= 4)),
        "Current + tight base (<12%)": ("resistance", lambda d: cur(d) & (d.base <= 12)),
        "Current + market uptrend": ("resistance", lambda d: cur(d) & d.mkt_up),
        "Current + market + base<12%": ("resistance", lambda d: cur(d) & d.mkt_up & (d.base <= 12)),
        "Simple (no RSI/close) + market": ("resistance", lambda d: simple(d) & d.mkt_up),
        "Simple + market + base<12%": ("resistance", lambda d: simple(d) & d.mkt_up & (d.base <= 12)),
        "Simple + market + base<15%": ("resistance", lambda d: simple(d) & d.mkt_up & (d.base <= 15)),
        "Loose + market uptrend": ("resistance", lambda d: loose(d) & d.mkt_up),
        "Current + market, 20-day high": ("high20", lambda d: cur(d) & d.mkt_up),
        "Current + market, 52-week high": ("high52w", lambda d: cur(d) & d.mkt_up),
    }


def stats(trades, months, k):
    R = trades[f"R_{k}"]
    wins, losses = R[R > 0].sum(), -R[R <= 0].sum()
    return {"n": len(R), "per_month": len(R) / months, "win": 100 * (R > 0).mean(), "avg_R": R.mean(),
            "se": R.std() / np.sqrt(len(R)) if len(R) > 1 else np.nan,
            "pf": wins / losses if losses > 0 else np.inf, "days": trades[f"days_{k}"].mean()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--years", type=float, default=2)
    ap.add_argument("--out", default="backtest.md")
    args = ap.parse_args()
    import yfinance as yf
    from screener import constituents
    symbols, err = constituents("ind_nifty100list.csv")
    if not symbols:
        raise SystemExit(f"Could not get the Nifty 100 list: {err}")
    data = yf.download(symbols + [bo.NIFTY], period=f"{int(args.years) + 2}y", interval="1d", auto_adjust=True,
                       group_by="ticker", progress=False, threads=True)
    nifty = data[bo.NIFTY][["Open", "High", "Low", "Close", "Volume"]].dropna()
    nifty.index = pd.DatetimeIndex(nifty.index).tz_localize(None)
    cutoff = nifty.index[-1] - pd.Timedelta(days=int(365 * args.years))
    jobs = []
    for s in symbols:
        try:
            f = data[s][["Open", "High", "Low", "Close", "Volume"]].dropna()
        except Exception:
            continue
        f = f[f["Volume"] > 0]
        f.index = pd.DatetimeIndex(f.index).tz_localize(None)
        if len(f) < 300:
            continue
        jobs.append((s, f, nifty, int(np.searchsorted(f.index, cutoff))))
    with ProcessPoolExecutor() as ex:
        rows = [r for part in ex.map(scan_stock, jobs) for r in part]
    cand = pd.DataFrame(rows)
    first, last = cand["date"].min(), cand["date"].max()
    months = (last - first).days / 30.4
    lines = [f"# Breakout rules backtest, {dt.date.today().isoformat()}", "",
             f"{len(jobs)} of {len(symbols)} Nifty 100 stocks, {first:%d %b %Y} to {last:%d %b %Y} "
             f"(Yahoo Finance daily prices). Entry at the breakout-day close, stop 1 ATR under the level "
             f"(max 8%), 0.2% costs. Avg R ± its standard error; a difference smaller than about two "
             f"standard errors is likely noise.", ""]
    sets = rule_sets()
    for k in EXITS:
        lines += [f"## Exit: {k}", "", "| Rule set | Signals | Per month | Win % | Avg R | Profit factor | Avg days |",
                  "|---|---|---|---|---|---|---|"]
        for name, (lv, rule) in sets.items():
            d = cand[cand.level_type == lv]
            d = d[rule(d)]
            if d.empty:
                lines.append(f"| {name} | 0 | 0 | – | – | – | – |")
                continue
            st = stats(d, months, k)
            lines.append(f"| {name} | {st['n']} | {st['per_month']:.1f} | {st['win']:.0f} | {st['avg_R']:+.2f} ± {st['se']:.2f} | "
                         f"{st['pf']:.2f} | {st['days']:.1f} |")
        lines.append("")
    # Year by year, so one lucky period can't pick the rules.
    years = sorted(cand["date"].dt.year.unique())
    lines += ["## Avg R by year (EMA exit)", "", "| Rule set | " + " | ".join(map(str, years)) + " |",
              "|---|" + "---|" * len(years)]
    for name, (lv, rule) in sets.items():
        d = cand[cand.level_type == lv]
        d = d[rule(d)]
        cells = []
        for y in years:
            g = d[d["date"].dt.year == y]["R_EMA"]
            cells.append(f"{g.mean():+.2f} ({len(g)})" if len(g) else "–")
        lines.append(f"| {name} | " + " | ".join(cells) + " |")
    text = "\n".join(lines) + "\n"
    open(args.out, "w", encoding="utf-8").write(text)
    cand.to_csv(os.path.splitext(args.out)[0] + "_candidates.csv", index=False)
    print(text)


if __name__ == "__main__":
    main()
