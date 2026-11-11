"""Backtest of the breakout rules on past Nifty 100 prices, to tune them so they are
neither too strict (almost no alerts) nor too loose (many failed breakouts).

For every day in the test window and every stock, it records each breakout candidate
with its features (volume, trend, RSI, 52-week high distance, close quality, strength
vs Nifty, base depth, market trend), then scores each rule set on the same trades:

  entry  = close on the breakout day (the "broke out at the close" alert)
  stop   = 1 ATR under the breakout level, never more than 8% under the entry
  exit   = +2R target, the stop, or the close after 20 sessions, whichever comes first
           (if target and stop fall on the same day, the stop is assumed)
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

HOLD, TARGET_R, MAX_STOP, COST = 20, 2.0, 0.08, 0.002
LEVELS = ["resistance", "high20", "high52w"]


def outcome(frame, t, level, atr):
    """R multiple of a trade entered at the close of bar t."""
    entry = float(frame["Close"].iloc[t])
    stop = max(level - atr, entry * (1 - MAX_STOP))
    if stop >= entry:
        stop = entry - atr
    risk = entry - stop
    target = entry + TARGET_R * risk
    cost_r = COST * entry / risk
    end = min(t + HOLD, len(frame) - 1)
    if end <= t:
        return None
    for j in range(t + 1, end + 1):
        if frame["Low"].iloc[j] <= stop:
            return -1 - cost_r, j - t
        if frame["High"].iloc[j] >= target:
            return TARGET_R - cost_r, j - t
    if end - t < HOLD:
        return None  # still open at the end of the data
    return (float(frame["Close"].iloc[end]) - entry) / risk - cost_r, HOLD


def scan_stock(args):
    sym, frame, nifty, start = args
    rows = []
    c, v = frame["Close"].values, frame["Volume"].values
    vavg = pd.Series(v).rolling(50).mean().shift(1).values
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
            res = outcome(frame, t, r["level"], r["atr"])
            if res is None:
                continue
            rows.append({"sym": sym, "date": day, "level_type": lv, "R": res[0], "days": res[1],
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
    return {
        "Loose: crossing + volume 1.2x + above EMA50": ("resistance", lambda d: (d.vol >= 1.2) & d.a50 & (d.turnover >= 5)),
        "Original: vol 1.5x, EMA50/200, RSI 50-75": ("resistance", lambda d: (d.vol >= 1.5) & d.a50 & d.a200 & d.rsi.between(50, 75) & (d.turnover >= 5)),
        "CURRENT (all filters)": ("resistance", cur),
        "Current, volume 1.3x": ("resistance", lambda d: cur(d.assign(vol=d.vol * 1.5 / 1.3))),
        "Current, volume 2.0x": ("resistance", lambda d: cur(d) & (d.vol >= 2.0)),
        "Current, no RSI filter": ("resistance", lambda d: cur(d.assign(rsi=60))),
        "Current, no 52-week-high filter": ("resistance", lambda d: cur(d.assign(hi52=0))),
        "Current, no strong-close filter": ("resistance", lambda d: cur(d.assign(closepos=1))),
        "Current, no beat-Nifty filter": ("resistance", lambda d: cur(d.assign(rs=1))),
        "Current, no EMA200-rising filter": ("resistance", lambda d: cur(d.assign(e200up=True))),
        "Current + tight base (<12%)": ("resistance", lambda d: cur(d) & (d.base <= 12)),
        "Current + market uptrend": ("resistance", lambda d: cur(d) & d.mkt_up),
        "Current + not extended (<4%)": ("resistance", lambda d: cur(d) & (d.ext <= 4)),
        "Current rules on 20-day high": ("high20", cur),
        "Current rules on 52-week high": ("high52w", cur),
    }


def stats(trades, months):
    if trades.empty:
        return {"n": 0}
    R = trades["R"]
    wins, losses = R[R > 0].sum(), -R[R <= 0].sum()
    return {"n": len(R), "per_month": len(R) / months, "win": 100 * (R > 0).mean(), "avg_R": R.mean(),
            "pf": wins / losses if losses > 0 else np.inf, "hit_2R": 100 * (R >= TARGET_R - 0.5).mean(),
            "days": trades["days"].mean()}


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
             f"(max 8%), exit at +2R, the stop or after {HOLD} sessions, 0.2% costs.", "",
             "| Rule set | Signals | Per month | Win % | Avg R | Profit factor | Reached 2R % | Avg days |",
             "|---|---|---|---|---|---|---|---|"]
    for name, (lv, rule) in rule_sets().items():
        d = cand[cand.level_type == lv]
        st = stats(d[rule(d)], months)
        if st["n"] == 0:
            lines.append(f"| {name} | 0 | 0 | – | – | – | – | – |")
            continue
        lines.append(f"| {name} | {st['n']} | {st['per_month']:.1f} | {st['win']:.0f} | {st['avg_R']:+.2f} | "
                     f"{st['pf']:.2f} | {st['hit_2R']:.0f} | {st['days']:.1f} |")
    # Year-by-year check that the current rules aren't one lucky period.
    lines += ["", "## Current rules by half-year", "", "| Period | Signals | Win % | Avg R |", "|---|---|---|---|"]
    lv, rule = rule_sets()["CURRENT (all filters)"]
    d = cand[cand.level_type == lv]
    d = d[rule(d)]
    for period, g in d.groupby(d["date"].dt.year.astype(str) + "-H" + ((d["date"].dt.month > 6) + 1).astype(str)):
        lines.append(f"| {period} | {len(g)} | {100 * (g.R > 0).mean():.0f} | {g.R.mean():+.2f} |")
    text = "\n".join(lines) + "\n"
    open(args.out, "w", encoding="utf-8").write(text)
    cand.to_csv(os.path.splitext(args.out)[0] + "_candidates.csv", index=False)
    print(text)


if __name__ == "__main__":
    main()
