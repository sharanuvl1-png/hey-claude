"""Rules-based swing-trade technical analysis for NSE stocks.

Fetches real daily and hourly prices, computes EMA20/50/200, MACD, RSI,
swing structure, patterns and candlesticks, then writes an annotated chart
(PNG) and a report (Markdown) in the "Multi-Timeframe Technical Analysis"
format. Every number comes from the data; nothing is drawn freehand.

Usage: python techchart.py "PC Jeweller" [--out charts]
"""
import argparse
import datetime as dt
import os
import re
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.gridspec import GridSpec
from matplotlib.patches import Rectangle
import numpy as np
import pandas as pd
import yfinance as yf

GREEN, RED, BLUE, ORANGE, PURPLE, NAVY = "#1a9850", "#d73027", "#1f4fd1", "#f28e2b", "#7b3fbf", "#0b2a5b"
BARS_SHOWN = 170


# ---------------------------------------------------------------- data

def resolve_symbol(query):
    q = query.strip()
    if re.fullmatch(r"[A-Za-z0-9&\-]+\.(NS|BO)", q, re.I):
        return q.upper(), q.upper()
    try:
        quotes = yf.Search(q, max_results=10).quotes
    except Exception:
        quotes = []
    for exch in ("NSI", "BSE"):
        for item in quotes:
            if item.get("exchange") == exch:
                return item["symbol"], item.get("longname") or item.get("shortname") or item["symbol"]
    guess = re.sub(r"[^A-Za-z0-9&]", "", q).upper() + ".NS"
    return guess, q


def fetch(symbol):
    daily = yf.download(symbol, period="2y", interval="1d", auto_adjust=False, progress=False)
    hourly = yf.download(symbol, period="60d", interval="60m", auto_adjust=False, progress=False)
    for frame in (daily, hourly):
        if isinstance(frame.columns, pd.MultiIndex):
            frame.columns = frame.columns.get_level_values(0)
    daily = daily[["Open", "High", "Low", "Close", "Volume"]].dropna()
    if daily.empty:
        raise SystemExit(f"No price data found for {symbol}")
    return daily, to_h4(hourly)


def to_h4(hourly):
    h4 = pd.DataFrame()
    if not hourly.empty:
        hourly = hourly[["Open", "High", "Low", "Close", "Volume"]].dropna()
        idx = hourly.index.tz_convert("Asia/Kolkata") if hourly.index.tz is not None else hourly.index
        hourly.index = idx
        # Two 4H-style sessions per NSE day: 09:15-13:15 and 13:15-15:30.
        session = np.where(hourly.index.hour * 60 + hourly.index.minute < 13 * 60 + 15, 0, 1)
        key = [hourly.index.date, session]
        h4 = hourly.groupby(key).agg(
            Open=("Open", "first"), High=("High", "max"), Low=("Low", "min"),
            Close=("Close", "last"), Volume=("Volume", "sum"))
        h4.index = [pd.Timestamp(d) + pd.Timedelta(hours=9 if s == 0 else 13) for d, s in h4.index]
    return h4


# ---------------------------------------------------------- indicators

def add_indicators(df):
    df = df.copy()
    c = df["Close"]
    for n in (20, 50, 200):
        df[f"EMA{n}"] = c.ewm(span=n, adjust=False).mean()
    fast, slow = c.ewm(span=12, adjust=False).mean(), c.ewm(span=26, adjust=False).mean()
    df["MACD"] = fast - slow
    df["Signal"] = df["MACD"].ewm(span=9, adjust=False).mean()
    df["Hist"] = df["MACD"] - df["Signal"]
    delta = c.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / 14, adjust=False).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / 14, adjust=False).mean()
    df["RSI"] = 100 - 100 / (1 + gain / loss.replace(0, np.nan))
    tr = pd.concat([df["High"] - df["Low"], (df["High"] - c.shift()).abs(), (df["Low"] - c.shift()).abs()], axis=1).max(axis=1)
    df["ATR"] = tr.ewm(alpha=1 / 14, adjust=False).mean()
    df["VolAvg"] = df["Volume"].rolling(20).mean()
    return df


def pivots(df, k=5):
    """Swing highs/lows: bar is the extreme of k bars on each side."""
    highs, lows = [], []
    h, l = df["High"].values, df["Low"].values
    for i in range(k, len(df) - k):
        if h[i] == h[i - k:i + k + 1].max():
            highs.append(i)
        if l[i] == l[i - k:i + k + 1].min():
            lows.append(i)
    return highs, lows


# ------------------------------------------------------------- analysis

def trend_of(df, highs, lows):
    last = df.iloc[-1]
    hh = len(highs) >= 2 and df["High"].iloc[highs[-1]] > df["High"].iloc[highs[-2]]
    hl = len(lows) >= 2 and df["Low"].iloc[lows[-1]] > df["Low"].iloc[lows[-2]]
    lh = len(highs) >= 2 and not hh
    ll = len(lows) >= 2 and not hl
    above = sum(last["Close"] > last[f"EMA{n}"] for n in (20, 50, 200) if not np.isnan(last[f"EMA{n}"]))
    ema50_slope = df["EMA50"].iloc[-1] - df["EMA50"].iloc[-11]
    if hh and hl and above >= 2:
        return "Uptrend", "higher highs and higher lows"
    if lh and ll and above <= 1:
        return "Downtrend", "lower highs and lower lows"
    if above == 3 and ema50_slope > 0:
        return "Uptrend", "price above rising EMAs"
    if above == 0 and ema50_slope < 0:
        return "Downtrend", "price below falling EMAs"
    return "Sideways", "mixed swing structure"


def ema_alignment(last, short_only=False):
    names = (20, 50) if short_only else (20, 50, 200)
    vals = [last[f"EMA{n}"] for n in names]
    if any(np.isnan(v) for v in vals):
        return "not enough history"
    if all(a > b for a, b in zip(vals, vals[1:])):
        stack = "bullish stack (" + " > ".join(f"EMA{n}" for n in names) + ")"
    elif all(a < b for a, b in zip(vals, vals[1:])):
        stack = "bearish stack (" + " < ".join(f"EMA{n}" for n in names) + ")"
    else:
        stack = "mixed / crossing"
    above = [f"EMA{n}" for n, v in zip(names, vals) if last["Close"] > v]
    pos = "price above " + ", ".join(above) if above else "price below all EMAs"
    return f"{stack}; {pos}"


def macd_text(df):
    m, s, h = df["MACD"].iloc[-1], df["Signal"].iloc[-1], df["Hist"].iloc[-1]
    side = "above" if m > s else "below"
    zero = "above zero" if m > 0 else "below zero"
    rising = h > df["Hist"].iloc[-2]
    cross = ""
    recent = np.sign(df["Hist"].iloc[-6:]).diff().fillna(0)
    if (recent > 0).any():
        cross = ", fresh bullish crossover"
    elif (recent < 0).any():
        cross = ", fresh bearish crossover"
    return f"MACD {m:.2f} {side} signal {s:.2f} ({zero}){cross}; histogram {'rising' if rising else 'falling'}"


def divergence(df, highs, lows, lookback=60):
    n = len(df)
    out = []
    pl = [i for i in lows if i >= n - lookback]
    ph = [i for i in highs if i >= n - lookback]
    if len(pl) >= 2:
        a, b = pl[-2], pl[-1]
        pa, pb, ra, rb = df["Low"].iloc[a], df["Low"].iloc[b], df["RSI"].iloc[a], df["RSI"].iloc[b]
        if pb < pa and rb > ra:
            out.append(("Regular bullish divergence", a, b, "low"))
        elif pb > pa and rb < ra:
            out.append(("Hidden bullish divergence", a, b, "low"))
    if len(ph) >= 2:
        a, b = ph[-2], ph[-1]
        pa, pb, ra, rb = df["High"].iloc[a], df["High"].iloc[b], df["RSI"].iloc[a], df["RSI"].iloc[b]
        if pb > pa and rb < ra:
            out.append(("Regular bearish divergence", a, b, "high"))
        elif pb < pa and rb > ra:
            out.append(("Hidden bearish divergence", a, b, "high"))
    return out


def detect_patterns(df, highs, lows):
    n, close, atr = len(df), df["Close"].iloc[-1], df["ATR"].iloc[-1]
    found = []
    ph = [i for i in highs if i >= n - 120]
    pl = [i for i in lows if i >= n - 120]
    hi60 = df["High"].iloc[-61:-1].max()
    lo60 = df["Low"].iloc[-61:-1].min()

    # Double top / bottom: two swing extremes within 3% and at least 10 bars apart.
    if len(ph) >= 2:
        a, b = ph[-2], ph[-1]
        ha, hb = df["High"].iloc[a], df["High"].iloc[b]
        if b - a >= 10 and abs(ha - hb) / ha < 0.03:
            neck = df["Low"].iloc[a:b + 1].min()
            state = "confirmed (closed below neckline)" if close < neck else "forming"
            found.append({"name": "Double Top", "detail": f"tops near ₹{ha:.1f}/₹{hb:.1f}, neckline ₹{neck:.1f}, {state}",
                          "line": ("h", neck, a, n - 1, RED)})
    if len(pl) >= 2:
        a, b = pl[-2], pl[-1]
        la, lb = df["Low"].iloc[a], df["Low"].iloc[b]
        if b - a >= 10 and abs(la - lb) / la < 0.03:
            neck = df["High"].iloc[a:b + 1].max()
            state = "confirmed (closed above neckline)" if close > neck else "forming"
            found.append({"name": "Double Bottom", "detail": f"bottoms near ₹{la:.1f}/₹{lb:.1f}, neckline ₹{neck:.1f}, {state}",
                          "line": ("h", neck, a, n - 1, GREEN)})

    # Head & shoulders / inverse from the last three swing points.
    if len(ph) >= 3:
        a, b, c = ph[-3:]
        ha, hb, hc = (df["High"].iloc[i] for i in (a, b, c))
        if hb > ha * 1.03 and hb > hc * 1.03 and abs(ha - hc) / ha < 0.05:
            neck = min(df["Low"].iloc[a:b + 1].min(), df["Low"].iloc[b:c + 1].min())
            found.append({"name": "Head & Shoulders", "detail": f"head ₹{hb:.1f}, neckline ₹{neck:.1f}",
                          "line": ("h", neck, a, n - 1, RED)})
    if len(pl) >= 3:
        a, b, c = pl[-3:]
        la, lb, lc = (df["Low"].iloc[i] for i in (a, b, c))
        if lb < la * 0.97 and lb < lc * 0.97 and abs(la - lc) / la < 0.05:
            neck = max(df["High"].iloc[a:b + 1].max(), df["High"].iloc[b:c + 1].max())
            found.append({"name": "Inverse Head & Shoulders", "detail": f"head ₹{lb:.1f}, neckline ₹{neck:.1f}",
                          "line": ("h", neck, a, n - 1, GREEN)})

    # Triangles from the slope of the last swing highs and lows.
    th = [i for i in highs if i >= n - 60][-3:]
    tl = [i for i in lows if i >= n - 60][-3:]
    if len(th) >= 2 and len(tl) >= 2:
        sh = np.polyfit(th, df["High"].iloc[th], 1)
        sl = np.polyfit(tl, df["Low"].iloc[tl], 1)
        flat = 0.15 * atr / 5
        kind = None
        if abs(sh[0]) < flat and sl[0] > flat:
            kind = "Ascending Triangle"
        elif sh[0] < -flat and abs(sl[0]) < flat:
            kind = "Descending Triangle"
        elif sh[0] < -flat and sl[0] > flat:
            kind = "Symmetrical Triangle"
        if kind:
            start = min(th[0], tl[0])
            found.append({"name": kind, "detail": "converging swing highs and lows over the last ~60 sessions",
                          "trend": [(start, n - 1, sh), (start, n - 1, sl)]})

    # Bull / bear flag: strong pole then a shallow, tight counter-move.
    for pole in range(10, 26):
        seg = df.iloc[-pole - 8:-8] if len(df) > pole + 8 else None
        if seg is None or seg.empty:
            break
        move = seg["Close"].iloc[-1] / seg["Close"].iloc[0] - 1
        flag = df.iloc[-8:]
        rng = (flag["High"].max() - flag["Low"].min()) / flag["Close"].mean()
        if move > 0.15 and rng < 0.08 and flag["Close"].iloc[-1] > seg["Close"].iloc[0] + 0.5 * (seg["Close"].iloc[-1] - seg["Close"].iloc[0]):
            found.append({"name": "Bull Flag", "detail": f"{move*100:.0f}% pole then a tight {rng*100:.0f}% flag; trigger above ₹{flag['High'].max():.1f}",
                          "line": ("h", flag["High"].max(), n - 8, n - 1, NAVY)})
            break
        if move < -0.15 and rng < 0.08:
            found.append({"name": "Bear Flag", "detail": f"{move*100:.0f}% drop then a tight {rng*100:.0f}% flag; breakdown below ₹{flag['Low'].min():.1f}",
                          "line": ("h", flag["Low"].min(), n - 8, n - 1, NAVY)})
            break

    # Breakout / breakdown of the 60-session range on above-average volume.
    vol_ok = df["Volume"].iloc[-1] > 1.3 * df["VolAvg"].iloc[-1]
    if close > hi60:
        found.append({"name": "Breakout", "detail": f"closed above the 60-session high ₹{hi60:.1f}" + (" on strong volume" if vol_ok else " (volume not confirming yet)"),
                      "line": ("h", hi60, n - 61, n - 1, NAVY)})
    elif close < lo60:
        found.append({"name": "Breakdown", "detail": f"closed below the 60-session low ₹{lo60:.1f}" + (" on strong volume" if vol_ok else ""),
                      "line": ("h", lo60, n - 61, n - 1, RED)})
    return found


def candle_signals(df):
    o, h, l, c = (df[k].values for k in ("Open", "High", "Low", "Close"))
    out = []
    i = len(df) - 1
    for j in range(i - 2, i + 1):
        body, rng = abs(c[j] - o[j]), h[j] - l[j]
        if rng <= 0:
            continue
        upper, lower = h[j] - max(o[j], c[j]), min(o[j], c[j]) - l[j]
        date = df.index[j].strftime("%d %b")
        pb = abs(c[j - 1] - o[j - 1])
        if c[j] > o[j] and c[j - 1] < o[j - 1] and c[j] >= o[j - 1] and o[j] <= c[j - 1] and body > pb:
            out.append((j, "Bullish Engulfing", f"{date}: buyers fully reversed the prior red candle", "bull"))
        elif c[j] < o[j] and c[j - 1] > o[j - 1] and o[j] >= c[j - 1] and c[j] <= o[j - 1] and body > pb:
            out.append((j, "Bearish Engulfing", f"{date}: sellers fully reversed the prior green candle", "bear"))
        elif body <= 0.1 * rng:
            out.append((j, "Doji", f"{date}: indecision; wait for the next candle to confirm direction", "neutral"))
        elif lower >= 2 * body and upper <= 0.3 * rng:
            name = "Hammer" if c[j - 1] < c[max(j - 6, 0)] else "Pin Bar (bullish rejection)"
            out.append((j, name, f"{date}: long lower wick shows dip-buying near ₹{l[j]:.1f}", "bull"))
        elif upper >= 2 * body and lower <= 0.3 * rng:
            name = "Inverted Hammer" if c[j - 1] < c[max(j - 6, 0)] else "Pin Bar (bearish rejection)"
            out.append((j, name, f"{date}: long upper wick shows selling near ₹{h[j]:.1f}", "bear" if "bearish" in name else "bull"))
        elif h[j] < h[j - 1] and l[j] > l[j - 1]:
            out.append((j, "Inside Bar", f"{date}: compression inside the prior range ₹{l[j-1]:.1f}–₹{h[j-1]:.1f}", "neutral"))
        if j >= 2:
            b2 = abs(c[j - 1] - o[j - 1])
            r2 = h[j - 1] - l[j - 1]
            if c[j - 2] < o[j - 2] and r2 > 0 and b2 < 0.3 * abs(c[j - 2] - o[j - 2]) and c[j] > o[j] and c[j] > (o[j - 2] + c[j - 2]) / 2:
                out.append((j, "Morning Star", f"{date}: three-candle bullish reversal", "bull"))
            if c[j - 2] > o[j - 2] and r2 > 0 and b2 < 0.3 * abs(c[j - 2] - o[j - 2]) and c[j] < o[j] and c[j] < (o[j - 2] + c[j - 2]) / 2:
                out.append((j, "Evening Star", f"{date}: three-candle bearish reversal", "bear"))
    return out


def cluster(levels, tol):
    levels = sorted(levels)
    groups = []
    for v in levels:
        if groups and v - groups[-1][-1] <= tol:
            groups[-1].append(v)
        else:
            groups.append([v])
    return [(min(g), max(g), len(g)) for g in groups]


def key_levels(df, highs, lows):
    close, atr = df["Close"].iloc[-1], df["ATR"].iloc[-1]
    recent_l = [df["Low"].iloc[i] for i in lows if i >= len(df) - 250]
    recent_h = [df["High"].iloc[i] for i in highs if i >= len(df) - 250]
    pad = 0.25 * atr
    sup = [(a - pad, b + pad, k) for a, b, k in cluster(recent_l, 0.6 * atr) if b < close]
    res = [(a - pad, b + pad, k) for a, b, k in cluster(recent_h, 0.6 * atr) if a > close]

    imm = max(sup, key=lambda z: z[1]) if sup else (close - 1.5 * atr, close - 0.5 * atr, 0)
    deeper = [z for z in sup if z[1] < imm[0]]
    near = [z for z in deeper if z[1] >= close * 0.75] or deeper[-1:]
    strong = max(near, key=lambda z: (z[2], z[1])) if near else (imm[0] - 1.5 * atr, imm[0] - 0.5 * atr, 0)
    hi52 = df["High"].iloc[-250:].max()
    resistance = min(res, key=lambda z: z[0]) if res else (max(close, hi52) + 0.5 * atr, max(close, hi52) + 1.5 * atr, 0)
    breakout = resistance[1]
    further = sorted(z for z in res if z[0] > resistance[1])
    return {"strong": strong, "immediate": imm, "resistance": resistance, "breakout": breakout,
            "further": further, "hi52": hi52}


def swing_setup(df, h4, lv, highs, lows):
    close, atr = df["Close"].iloc[-1], df["ATR"].iloc[-1]
    imm = lv["immediate"]
    entry_lo = max(imm[0], close - 2 * atr)
    entry_hi = min(close, max(imm[1], df["EMA20"].iloc[-1]))
    if entry_hi <= entry_lo:
        entry_lo, entry_hi = close - 0.75 * atr, close
    alt = (lv["breakout"], lv["breakout"] + 0.5 * atr)
    last_low = df["Low"].iloc[lows[-1]] if lows else df["Low"].iloc[-20:].min()
    stop_cons = min(last_low - 0.25 * atr, entry_lo - 0.5 * atr)
    stop_aggr = None
    if not h4.empty:
        hp, lp = pivots(h4, k=3)
        if lp:
            stop_aggr = h4["Low"].iloc[lp[-1]] - 0.1 * h4["ATR"].iloc[-1]
    if stop_aggr is None or stop_aggr >= entry_lo:
        stop_aggr = entry_lo - 0.5 * atr
    stop_aggr = max(stop_aggr, stop_cons)
    entry_mid = (entry_lo + entry_hi) / 2
    risk = max(entry_mid - stop_cons, atr)
    resist = sorted([lv["resistance"][0]] + [z[0] for z in lv["further"]])
    targets = []
    for mult in (1.5, 2.5, 3.5):
        ideal = entry_mid + mult * risk
        floor = (targets[-1] if targets else entry_hi) + 0.5 * risk
        near = [r for r in resist if r >= floor and abs(r - ideal) <= 0.5 * risk]
        targets.append(round(near[0] if near else max(ideal, floor), 1))
    rr = (targets[0] - entry_mid) / risk
    return {"entry": (entry_lo, entry_hi), "alt": alt, "stop_cons": stop_cons, "stop_aggr": stop_aggr,
            "targets": targets, "risk": risk, "rr1": rr}


def verdict(d, h4t, pats, divs, cands, setup):
    score = 0
    reasons = []
    t = d["trend"][0]
    score += {"Uptrend": 3, "Sideways": 0, "Downtrend": -3}[t]
    if t != "Sideways":
        reasons.append(f"Daily {t.lower()} ({d['trend'][1]})")
    last = d["df"].iloc[-1]
    if last["Close"] > last["EMA200"]:
        score += 1
    else:
        score -= 1
    if "bullish stack" in d["ema"]:
        score += 1
        reasons.append("EMAs stacked bullishly")
    elif "bearish stack" in d["ema"]:
        score -= 1
    if last["MACD"] > last["Signal"]:
        score += 1
    else:
        score -= 1
    rsi = last["RSI"]
    if 50 <= rsi <= 70:
        score += 1
    elif rsi > 75:
        score -= 1
        reasons.append(f"RSI {rsi:.0f} is stretched; chasing here is risky")
    elif rsi < 40:
        score -= 1
    if h4t == "Uptrend":
        score += 1
    elif h4t == "Downtrend":
        score -= 1
    for p in pats:
        if p["name"] in ("Bull Flag", "Double Bottom", "Inverse Head & Shoulders", "Ascending Triangle", "Breakout"):
            score += 1
            reasons.append(f"{p['name']}: {p['detail']}")
        if p["name"] in ("Bear Flag", "Double Top", "Head & Shoulders", "Descending Triangle", "Breakdown"):
            score -= 1
            reasons.append(f"{p['name']}: {p['detail']}")
    for name, *_ in divs:
        score += 1 if "bullish" in name else -1
    for _, name, _, side in cands:
        score += {"bull": 0.5, "bear": -0.5}.get(side, 0)
    close = last["Close"]
    e_lo, e_hi = setup["entry"]
    if score >= 6 and close <= e_hi * 1.01 and rsi < 72:
        rec, nxt = "🟢 BUY NOW", "Buy now within the entry zone, with the stop-loss in place"
    elif score >= 3:
        if close > lv_break_close(setup):
            rec, nxt = "🟡 BUY ON DIP", f"Wait for a pullback to ₹{e_lo:.1f}–₹{e_hi:.1f}"
        else:
            rec, nxt = "🟡 BUY ON DIP", f"Wait for a pullback to ₹{e_lo:.1f}–₹{e_hi:.1f}, or a breakout above ₹{setup['alt'][0]:.1f}"
    elif score >= 0:
        rec, nxt = "🟠 WAIT", f"Wait for a breakout above ₹{setup['alt'][0]:.1f}"
    else:
        rec, nxt = "🔴 AVOID", "Avoid until the trend changes (reclaim EMA50 and a higher low)"
    conf = int(np.clip(round(5 + score / 2), 1, 10))
    grade = "A+" if score >= 8 and setup["rr1"] >= 2 else "A" if score >= 6 else "B" if score >= 3 else "C"
    if not reasons:
        reasons.append("No strong edge from trend, momentum or patterns")
    return rec, conf, grade, reasons[:2], nxt, score


def lv_break_close(setup):
    return setup["entry"][1] * 1.03


# ---------------------------------------------------------------- chart

def candles(ax, df, x):
    up = df["Close"] >= df["Open"]
    w = 0.6
    for xi, (_, r), u in zip(x, df.iterrows(), up):
        col = GREEN if u else RED
        ax.vlines(xi, r["Low"], r["High"], color=col, linewidth=0.8)
        lo, hi = sorted((r["Open"], r["Close"]))
        ax.add_patch(Rectangle((xi - w / 2, lo), w, max(hi - lo, 1e-9), color=col))


def render(path, name, symbol, d, h4t, lv, setup, pats, divs, cands, rec, conf, grade):
    full = d["df"]
    off = max(len(full) - BARS_SHOWN, 0)
    df = full.iloc[off:]
    x = np.arange(len(df))
    last = df.iloc[-1]
    close = last["Close"]

    fig = plt.figure(figsize=(16, 11), dpi=110)
    gs = GridSpec(5, 4, height_ratios=[5.2, 1.4, 1.2, 0.35, 1.9], hspace=0.08, wspace=0.08, figure=fig)
    ax = fig.add_subplot(gs[0, :])
    axm = fig.add_subplot(gs[1, :], sharex=ax)
    axr = fig.add_subplot(gs[2, :], sharex=ax)

    candles(ax, df, x)
    for n, col in ((20, BLUE), (50, ORANGE), (200, RED)):
        ax.plot(x, df[f"EMA{n}"], color=col, linewidth=1.3, label=f"EMA {n} ({last[f'EMA{n}']:.2f})")
    xr = len(df) + 14
    ax.set_xlim(-2, xr)

    def band(z, col, label):
        ax.axhspan(z[0], z[1], color=col, alpha=0.15)
        ax.text(len(df) * 0.40, (z[0] + z[1]) / 2, f"{label}  ₹{z[0]:.1f} – ₹{z[1]:.1f}", color=col,
                fontsize=9, fontweight="bold", va="center", ha="center",
                bbox=dict(boxstyle="round", fc="white", ec=col, alpha=0.85))

    band(lv["strong"], GREEN, "Strong Support")
    band(lv["immediate"], "#2c7fb8", "Immediate Support")
    band(lv["resistance"], RED, "Resistance")
    ax.hlines(lv["breakout"], len(df) - 60, xr, color="black", linestyle="--", linewidth=1)
    ax.text(len(df) - 61, lv["breakout"], f"Breakout > ₹{lv['breakout']:.1f} ", fontsize=9, va="center", ha="right", fontweight="bold")
    for i, t in enumerate(setup["targets"], 1):
        ax.hlines(t, len(df) - 25, xr, color=BLUE, linestyle=":", linewidth=1)
        ax.text(xr - 0.5, t, f"T{i}: ₹{t:.1f}", color=BLUE, fontsize=9, fontweight="bold", ha="right", va="bottom")
    ax.hlines(setup["stop_cons"], len(df) - 25, xr, color=RED, linewidth=1.2)
    ax.text(xr - 0.5, setup["stop_cons"], f"Stop-Loss ₹{setup['stop_cons']:.1f}", color=RED, fontsize=9, fontweight="bold", ha="right", va="top")
    e_lo, e_hi = setup["entry"]
    ax.add_patch(Rectangle((len(df) + 1, e_lo), 8, e_hi - e_lo, color=GREEN, alpha=0.25))
    ax.annotate(f"Entry ₹{e_lo:.1f}–₹{e_hi:.1f}", xy=(len(df) - 0.5, (e_lo + e_hi) / 2), xytext=(len(df) + 2, e_lo - 1.2 * last["ATR"]),
                color=GREEN, fontsize=9, fontweight="bold", arrowprops=dict(arrowstyle="->", color=GREEN, lw=1.8))
    ax.axhline(close, color=GREEN if close >= df["Open"].iloc[-1] else RED, linestyle=":", linewidth=0.8)
    ax.text(xr + 0.3, close, f"₹{close:.2f}", fontsize=9, color="white", va="center",
            bbox=dict(boxstyle="square", fc=GREEN if close >= df["Open"].iloc[-1] else RED))

    for p in pats:
        if "line" in p:
            _, lvl, a, b, col = p["line"]
            a, b = max(a - off, 0), b - off
            ax.hlines(lvl, a, b, color=col, linewidth=1.4, linestyle="-.")
            ax.text(a, lvl, f" {p['name']}", color=col, fontsize=8, va="bottom")
        for a, b, coef in p.get("trend", []):
            xs = np.array([a, b])
            ax.plot(xs - off, np.polyval(coef, xs), color="black", linewidth=1.2)
    for j, cname, _, side in cands:
        jj = j - off
        if 0 <= jj < len(df):
            y = df["Low"].iloc[jj] - 0.6 * last["ATR"] if side != "bear" else df["High"].iloc[jj] + 0.6 * last["ATR"]
            ax.annotate(cname, (jj, y), fontsize=7.5, ha="center", color={"bull": GREEN, "bear": RED}.get(side, "dimgray"),
                        rotation=90, va="top" if side != "bear" else "bottom")

    lo = min(df["Low"].min(), lv["strong"][0], setup["stop_cons"]) * 0.97
    hi = max(df["High"].max(), setup["targets"][-1], lv["resistance"][1]) * 1.03
    ax.set_ylim(lo, hi)
    ax.legend(loc="upper left", fontsize=9, frameon=False)
    ax.yaxis.tick_right()
    ax.grid(alpha=0.2)
    ax.text(0, 1.075, f"{name} ({symbol.replace('.NS', '')})  |  Daily  |  {df.index[-1].strftime('%d %b %Y')}",
            transform=ax.transAxes, fontsize=15, fontweight="bold", color=NAVY, va="bottom")
    rec = re.sub(r"[^\x00-\x7F₹]+", "", rec).strip()
    bias_col = GREEN if "BUY" in rec else ("#b8860b" if "WAIT" in rec else RED)
    ax.text(0, 1.015, f"Trend: {d['trend'][0]} (Daily) / {h4t} (4H)    Verdict: {rec}  ·  Confidence {conf}/10  ·  Grade {grade}",
            transform=ax.transAxes, ha="left", va="bottom", fontsize=10.5, color=bias_col, fontweight="bold")

    cols = [GREEN if v >= 0 else RED for v in df["Hist"]]
    axm.bar(x, df["Hist"], color=cols, alpha=0.6, width=0.8)
    axm.plot(x, df["MACD"], color=BLUE, linewidth=1, label=f"MACD {last['MACD']:.2f}")
    axm.plot(x, df["Signal"], color=RED, linewidth=1, label=f"Signal {last['Signal']:.2f}")
    axm.axhline(0, color="gray", linewidth=0.6)
    axm.legend(loc="upper left", fontsize=8, frameon=False, ncol=2)
    axm.set_ylabel("MACD\n(12,26,9)", fontsize=8)
    axm.yaxis.tick_right()
    axr.plot(x, df["RSI"], color=PURPLE, linewidth=1)
    for lvl in (30, 50, 70):
        axr.axhline(lvl, color="gray", linestyle="--", linewidth=0.6)
    axr.set_ylim(10, 90)
    axr.set_ylabel(f"RSI 14\n{last['RSI']:.1f}", fontsize=8)
    axr.yaxis.tick_right()
    for dname, a, b, kind in divs:
        a, b = a - off, b - off
        if a >= 0:
            col = GREEN if "bullish" in dname else RED
            axr.plot([a, b], [df["RSI"].iloc[a], df["RSI"].iloc[b]], color=col, linewidth=2)
            src = "Low" if kind == "low" else "High"
            ax.plot([a, b], [df[src].iloc[a], df[src].iloc[b]], color=col, linewidth=2)
            axr.text(b, df["RSI"].iloc[b], " " + dname, fontsize=7.5, color=col)
    months = [i for i in range(1, len(df)) if df.index[i].month != df.index[i - 1].month]
    axr.set_xticks(months)
    axr.set_xticklabels([df.index[i].strftime("%b %y") for i in months], fontsize=8)
    plt.setp(ax.get_xticklabels(), visible=False)
    plt.setp(axm.get_xticklabels(), visible=False)

    tables = [
        ("KEY LEVELS (DAILY)", [("Strong Support", rng(lv["strong"])), ("Immediate Support", rng(lv["immediate"])),
                                ("Resistance", rng(lv["resistance"])), ("Breakout Level", f"Above ₹{lv['breakout']:.1f}")]),
        ("SWING TRADE SETUP", [("Entry (Primary)", rng(setup["entry"])), ("Entry (Breakout retest)", rng(setup["alt"])),
                               ("Targets", " / ".join(f"₹{t:.0f}" for t in setup["targets"])),
                               ("Stop-Loss (Cons./Aggr.)", f"₹{setup['stop_cons']:.1f} / ₹{setup['stop_aggr']:.1f}")]),
        ("PATTERN & STRUCTURE", [(p["name"], "") for p in pats[:3]] or [("No strong pattern", "trading within range")]),
        ("CANDLESTICK & MOMENTUM", [(c[1], "") for c in cands[-2:]] + [(f"RSI {last['RSI']:.1f}", ""), ("MACD " + ("above" if last["MACD"] > last["Signal"] else "below") + " signal", "")]),
    ]
    for k, (title, rows) in enumerate(tables):
        t = fig.add_subplot(gs[4, k])
        t.axis("off")
        t.add_patch(Rectangle((0, 0.82), 1, 0.18, transform=t.transAxes, color=NAVY if k != 1 else GREEN))
        t.text(0.5, 0.91, title, color="white", fontweight="bold", ha="center", va="center", transform=t.transAxes, fontsize=10)
        for r, (a, b) in enumerate(rows[:4]):
            yy = 0.68 - r * 0.18
            t.text(0.03, yy, ("✓ " if not b else "") + a, transform=t.transAxes, fontsize=9, va="center")
            t.text(0.97, yy, b, transform=t.transAxes, fontsize=9, va="center", ha="right")
        t.add_patch(Rectangle((0, 0), 1, 1, transform=t.transAxes, fill=False, ec="#999999"))
    fig.text(0.5, 0.005, "Rules-based analysis from end-of-day NSE data (Yahoo Finance). Educational only, not investment advice.",
             ha="center", fontsize=8, color="gray")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def rng(z):
    return f"₹{z[0]:.1f} – ₹{z[1]:.1f}"


# --------------------------------------------------------------- report

def report(name, symbol, d, h4, lv, setup, pats, divs, cands, rec, conf, grade, reasons, nxt, chart_file):
    df, last = d["df"], d["df"].iloc[-1]
    hi = [f"₹{df['High'].iloc[i]:.1f}" for i in d["highs"][-3:]]
    lo = [f"₹{df['Low'].iloc[i]:.1f}" for i in d["lows"][-3:]]
    div_txt = "; ".join(x[0] for x in divs) or "no divergence on the last two swings"
    lines = [f"📌 **{name} ({symbol.replace('.NS', '')}) – Multi-Timeframe Technical Analysis (Daily + 4H Combined)**",
             f"_Data up to {df.index[-1].strftime('%d %b %Y')} · Close ₹{last['Close']:.2f}_", "",
             "**📍 Daily Timeframe Trend**",
             f"- Trend: **{d['trend'][0]}** ({d['trend'][1]})",
             f"- Recent swing highs: {', '.join(hi) or 'n/a'} · swing lows: {', '.join(lo) or 'n/a'}",
             f"- EMAs: EMA20 ₹{last['EMA20']:.1f}, EMA50 ₹{last['EMA50']:.1f}, EMA200 ₹{last['EMA200']:.1f}; {d['ema']}",
             f"- {macd_text(df)}",
             f"- RSI(14) {last['RSI']:.1f}; {div_txt}", ""]
    lines.append("**📍 4-Hour Timeframe Trend**")
    if h4["df"] is None:
        lines.append("- 4H data unavailable for this stock.")
    else:
        h, hl = h4["df"], h4["df"].iloc[-1]
        lp = [f"₹{h['Low'].iloc[i]:.1f}" for i in h4["lows"][-2:]]
        lines += [f"- Structure: **{h4['trend'][0]}** ({h4['trend'][1]})",
                  f"- Pullback/support area: recent 4H swing lows {', '.join(lp) or 'n/a'}; EMA20 ₹{hl['EMA20']:.1f}",
                  f"- EMA20/50: {ema_alignment(hl, short_only=True)}",
                  f"- {macd_text(h)}",
                  f"- RSI(14) {hl['RSI']:.1f}; " + ("; ".join(x[0] for x in h4["divs"]) or "no divergence")]
    lines += ["", "**📍 Pattern Detection (Both Timeframes)**"]
    allp = [("Daily", p) for p in pats] + [("4H", p) for p in h4.get("pats", [])]
    lines += [f"- {tf}: **{p['name']}** – {p['detail']}" for tf, p in allp] or ["- No strong pattern detected. Trading within range."]
    lines += ["", "**📍 Candlestick Patterns (last 3 daily candles)**"]
    lines += [f"- **{c[1]}** – {c[2]}" for c in cands] or ["- No notable candlestick signal in the last 3 sessions."]
    lines += ["", "**📍 Price Action Key Levels (Daily Structure)**", "",
              "| Zone | Price Level (₹) | Context |", "|------|-----------------|---------|",
              f"| Strong Support | {rng(lv['strong'])} | Cluster of earlier swing lows (demand) |",
              f"| Immediate Support | {rng(lv['immediate'])} | Nearest swing-low / wick area |",
              f"| Resistance | {rng(lv['resistance'])} | Nearest swing-high supply |",
              f"| Breakout Level | Above ₹{lv['breakout']:.1f} | Swing continuation trigger (52-week high ₹{lv['hi52']:.1f}) |", "",
              "**📍 Swing Trade Setup**", "",
              "🟢 **Entry Zone**",
              f"- Primary: {rng(setup['entry'])} (daily support / EMA20 area; enter on a bullish 4H candle)",
              f"- Alternate: {rng(setup['alt'])} (breakout retest)", "",
              "🎯 **Profit Targets**"] + [f"- T{i}: ₹{t:.1f}" for i, t in enumerate(setup["targets"], 1)] + [
              "", "🛑 **Stop-Loss**",
              f"- Conservative: below daily swing low (₹{setup['stop_cons']:.1f})",
              f"- Aggressive: below 4H structure (₹{setup['stop_aggr']:.1f})",
              f"- Risk per share ≈ ₹{setup['risk']:.1f}; reward:risk to T1 ≈ {setup['rr1']:.1f}", "",
              "**📌 Strategy Notes**"]
    notes = [f"Higher timeframe: daily trend is {d['trend'][0].lower()}; only take longs while price holds above ₹{lv['strong'][0]:.1f}.",
             f"Entry timing: wait for a bullish 4H candle (engulfing/hammer) inside {rng(setup['entry'])} rather than buying mid-range.",
             f"EMA stacking: {d['ema']}.",
             f"Breakout rule: a daily close above ₹{lv['breakout']:.1f} with volume above its 20-day average (≈{last['VolAvg']:,.0f}) opens T2/T3.",
             f"Momentum: RSI {last['RSI']:.0f} – " + ("room to run" if last["RSI"] < 65 else "elevated, prefer dips") + f"; {div_txt}.",
             "Exit discipline: move the stop to entry once T1 is hit; book partial profits at each target."]
    lines += [f"- {n}" for n in notes]
    lines += ["", "**STEP 2 — Marked Up Chart**", f"![chart]({chart_file})", "",
              "**✅ Trade Validation Checklist**",
              f"- Trend + momentum aligned: {'Yes' if d['trend'][0] == 'Uptrend' and last['MACD'] > last['Signal'] else 'No'} · Price at entry zone: {'Yes' if setup['entry'][0] <= last['Close'] <= setup['entry'][1] else 'No'}",
              f"- Stop defined (₹{setup['stop_cons']:.1f}) · Reward:risk ≥ 1.5 to T1: {'Yes' if setup['rr1'] >= 1.5 else 'No'}", "",
              "## 📌 Final Verdict", "",
              f"Recommendation: **{rec}**", "", f"Confidence: **{conf}/10**", "", f"Trade Quality: **{grade}**", "",
              "Reason:"] + [f"• {r}" for r in reasons] + ["", "Next Action:", f"• {nxt}", "",
              "_Rules-based analysis from end-of-day data. Educational only, not investment advice. Never place orders without your own confirmation._"]
    return "\n".join(lines)


# ----------------------------------------------------------------- main

def analyse(frame):
    df = add_indicators(frame)
    highs, lows = pivots(df)
    return {"df": df, "highs": highs, "lows": lows, "trend": trend_of(df, highs, lows), "ema": ema_alignment(df.iloc[-1])}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stock")
    ap.add_argument("--out", default="charts")
    args = ap.parse_args()

    symbol, name = resolve_symbol(args.stock)
    daily, h4raw = fetch(symbol)
    d = analyse(daily)
    h4 = {"df": None, "pats": [], "divs": []}
    h4t = "n/a"
    if len(h4raw) > 40:
        hdf = add_indicators(h4raw)
        hh, hl = pivots(hdf, k=3)
        h4 = {"df": hdf, "highs": hh, "lows": hl, "trend": trend_of(hdf, hh, hl),
              "pats": [p for p in detect_patterns(hdf, hh, hl) if p["name"] != "Breakout"],
              "divs": divergence(hdf, hh, hl, lookback=40)}
        h4t = h4["trend"][0]
    lv = key_levels(d["df"], d["highs"], d["lows"])
    setup = swing_setup(d["df"], h4["df"] if h4["df"] is not None else pd.DataFrame(), lv, d["highs"], d["lows"])
    pats = detect_patterns(d["df"], d["highs"], d["lows"])
    divs = divergence(d["df"], d["highs"], d["lows"])
    cands = candle_signals(d["df"])
    rec, conf, grade, reasons, nxt, _ = verdict(d, h4t, pats, divs, cands, setup)

    stamp = d["df"].index[-1].strftime("%Y-%m-%d")
    base = symbol.replace(".NS", "").replace(".BO", "")
    os.makedirs(os.path.join(args.out, base), exist_ok=True)
    png = os.path.join(args.out, base, f"{stamp}.png")
    md = os.path.join(args.out, base, f"{stamp}.md")
    render(png, name, symbol, d, h4t, lv, setup, pats, divs, cands, rec, conf, grade)
    with open(md, "w", encoding="utf-8") as f:
        f.write(report(name, symbol, d, h4, lv, setup, pats, divs, cands, rec, conf, grade, reasons, nxt, f"{stamp}.png"))
    print(f"symbol={symbol}\npng={png}\nmd={md}")


if __name__ == "__main__":
    sys.exit(main())
