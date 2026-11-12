"""Rough time-to-target estimate from real daily prices (Yahoo Finance).

For each "SYMBOL:target" it prints the latest close, ATR(14), and, over the
last N years, how many trading days the stock took to first rise by the same
percentage from any starting day (median / 25th / 75th percentile), and how
often it got there at all within 250 trading days.
"""
import sys
import numpy as np
import pandas as pd
import yfinance as yf

YEARS = 10
HORIZON = 250


def first_passage(close, pct):
    c = close.values
    days = []
    hit = 0
    starts = range(0, len(c) - HORIZON)
    for i in starts:
        goal = c[i] * (1 + pct)
        w = np.nonzero(c[i + 1:i + 1 + HORIZON] >= goal)[0]
        if len(w):
            hit += 1
            days.append(w[0] + 1)
    n = len(starts)
    return days, hit, n


for arg in sys.argv[1:]:
    sym, targets = arg.split(":", 1)
    df = yf.download(sym, period=f"{YEARS}y", interval="1d", auto_adjust=True, progress=False)
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)
    df = df.dropna()
    h, l, c = df["High"], df["Low"], df["Close"]
    tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
    atr = tr.ewm(alpha=1 / 14, adjust=False).mean()
    last = c.iloc[-1]
    ret = c.pct_change().dropna()
    jumps = ret[ret.abs() > 0.25]
    print(f"### {sym}  data {df.index[0].date()} to {df.index[-1].date()} ({len(df)} days)")
    print(f"close {last:.2f}  ATR14 {atr.iloc[-1]:.2f} ({atr.iloc[-1]/last*100:.2f}%)  "
          f"median abs daily move 1y {ret[-250:].abs().median()*100:.2f}%  "
          f"avg 20-day move 1y {c.pct_change(20)[-250:].abs().mean()*100:.2f}%")
    if len(jumps):
        print("WARNING big one-day jumps (possible split/bonus not adjusted):", {str(k.date()): round(v * 100, 1) for k, v in jumps.items()})
    for t in targets.split(","):
        t = float(t)
        pct = t / last - 1
        days, hit, n = first_passage(c, pct)
        line = f"target {t} = +{pct*100:.1f}% from close; ATRs away {(t-last)/atr.iloc[-1]:.1f}; "
        if days:
            q = np.percentile(days, [25, 50, 75])
            line += f"history: reached within {HORIZON}d in {hit}/{n} starts ({hit/n*100:.0f}%); days p25 {q[0]:.0f} median {q[1]:.0f} p75 {q[2]:.0f}"
        else:
            line += f"history: never reached within {HORIZON}d in {n} starts"
        print(line)
    print()
