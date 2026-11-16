"""The swing board's memory: each trading day's calls, kept on the swing-history branch.

It gives the board two things a single scan can't:
  * what changed since the last scan (a call moving up or down, a new breakout)
  * a track record: how each new call did 5, 10 and 20 trading days later, next to the Nifty 50

Every number is computed from downloaded closing prices. A call's result is only filled in once
enough trading days have passed; until then it is shown as open. Nothing is estimated.

File layout (history.json):
  {"last_scan": {"generated_at": ..., "verdicts": {SYM: verdict}, "breakouts": [SYM, ...]},
   "calls": {"YYYY-MM-DD": {SYM: {"v": verdict, "c": close, "sl": stop, "t1": target1, "g": group,
                                  "r": {"5": %, "10": %, "20": %, "n5": %, "n10": %, "n20": %, "out": ...}}}}}
"""
import datetime as dt
import json
import os

HORIZONS = (5, 10, 20)
KEEP_DAYS = 400          # calendar days of calls kept
VERDICTS = ("BUY NOW", "BUY ON DIP", "WAIT", "AVOID")


def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            h = json.load(fh)
        h.setdefault("calls", {})
        return h
    except (OSError, ValueError):
        return {"calls": {}, "last_scan": None}


def save(h, path):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(h, fh, ensure_ascii=False, separators=(",", ":"))


def changes(prev_scan, rows):
    """Calls that moved since the previous scan, and breakouts that are new since then."""
    if not prev_scan:
        return {"since": None, "moves": [], "new_breakouts": []}
    before = prev_scan.get("verdicts") or {}
    old_bo = set(prev_scan.get("breakouts") or [])
    rank = {v: i for i, v in enumerate(VERDICTS)}
    moves = []
    for r in rows:
        was = before.get(r["symbol"])
        if was and was != r["verdict"] and was in rank:
            moves.append({"symbol": r["symbol"], "group": r["group"], "from": was, "to": r["verdict"],
                          "dir": "up" if rank[r["verdict"]] < rank[was] else "down"})
    moves.sort(key=lambda m: (m["dir"] != "up", rank[m["to"]], m["symbol"]))
    new_bo = [r["symbol"] for r in rows if (r.get("breakout") or {}).get("status") == "breakout" and r["symbol"] not in old_bo]
    return {"since": prev_scan.get("generated_at"), "moves": moves, "new_breakouts": new_bo}


def record(h, rows, generated_at):
    """Store this scan's calls (the last scan of a day replaces earlier ones that day) and remember it as the last scan."""
    for r in rows:
        day = h["calls"].setdefault(r["date"], {})
        old = day.get(r["symbol"]) or {}
        day[r["symbol"]] = {"v": r["verdict"], "c": r["close"], "sl": r["stop"], "t1": r["targets"][0], "g": r["group"],
                            **({"r": old["r"]} if old.get("r") and old.get("v") == r["verdict"] else {})}
    cutoff = (dt.date.today() - dt.timedelta(days=KEEP_DAYS)).isoformat()
    for d in [d for d in h["calls"] if d < cutoff]:
        del h["calls"][d]
    h["last_scan"] = {"generated_at": generated_at, "verdicts": {r["symbol"]: r["verdict"] for r in rows},
                      "breakouts": [r["symbol"] for r in rows if (r.get("breakout") or {}).get("status") == "breakout"]}


def _new_calls(h):
    """(date, symbol, call) for each call that is new: the stock's verdict differs from its previous recorded day."""
    last, out = {}, []
    for d in sorted(h["calls"]):
        for sym, c in h["calls"][d].items():
            if last.get(sym) != c["v"]:
                out.append((d, sym, c))
            last[sym] = c["v"]
    return out


def _forward(series, day, n):
    """Close n trading days after `day`, from a {date: close} map with sorted dates; None if not there yet."""
    dates = series["dates"]
    if day not in series["idx"]:
        return None
    i = series["idx"][day] + n
    return series["closes"][i] if i < len(dates) else None


def _outcome(series, day, stop, t1, horizon=20):
    """Using closing prices: 'target' if a close reached T1 before any close at or below the stop, 'stopped' for the reverse."""
    if day not in series["idx"]:
        return None
    i0 = series["idx"][day]
    for c in series["closes"][i0 + 1:i0 + 1 + horizon]:
        if c <= stop:
            return "stopped"
        if c >= t1:
            return "target"
    return "neither" if i0 + horizon < len(series["closes"]) else None


def _series(dates, closes):
    return {"dates": dates, "closes": closes, "idx": {d: i for i, d in enumerate(dates)}}


def track_record(h, prices, nifty):
    """Fill in results for new calls whose horizons have passed, then summarise.

    prices: {SYM: (dates, closes)} for the stocks on this scan; nifty: (dates, closes) for the Nifty 50.
    Returns the summary written into data.json."""
    series = {s: _series(*v) for s, v in prices.items()}
    nser = _series(*nifty) if nifty else None
    calls = _new_calls(h)
    for d, sym, c in calls:
        s = series.get(sym)
        if not s:
            continue
        r = dict(c.get("r") or {})
        for n in HORIZONS:
            if str(n) not in r:
                fwd = _forward(s, d, n)
                if fwd is not None:
                    r[str(n)] = round(100 * (fwd / c["c"] - 1), 2)
                    if nser:
                        a, b = _forward(nser, d, 0), _forward(nser, d, n)
                        if a and b:
                            r["n" + str(n)] = round(100 * (b / a - 1), 2)
        if "out" not in r:
            o = _outcome(s, d, c["sl"], c["t1"])
            if o:
                r["out"] = o
        if r:
            c["r"] = r

    summary = {"since": min(h["calls"]) if h["calls"] else None, "days": len(h["calls"]), "by_verdict": {}, "recent": []}
    for v in VERDICTS:
        mine = [c for _, _, c in calls if c["v"] == v]
        row = {"calls": len(mine)}
        for n in HORIZONS:
            done = [c["r"][str(n)] for c in mine if str(n) in (c.get("r") or {})]
            vs = [c["r"][str(n)] - c["r"]["n" + str(n)] for c in mine if str(n) in (c.get("r") or {}) and "n" + str(n) in c["r"]]
            row[str(n)] = None if not done else {"n": len(done), "avg": round(sum(done) / len(done), 2),
                                                 "win": round(100 * sum(x > 0 for x in done) / len(done)),
                                                 "vs_nifty": round(sum(vs) / len(vs), 2) if vs else None}
        outs = [c["r"]["out"] for c in mine if "out" in (c.get("r") or {})]
        row["outcomes"] = None if not outs else {k: outs.count(k) for k in ("target", "stopped", "neither")}
        summary["by_verdict"][v] = row
    for d, sym, c in sorted(calls, key=lambda x: x[0], reverse=True):
        if c["v"] == "BUY NOW" and len(summary["recent"]) < 40:
            s = series.get(sym)
            now = s["closes"][-1] if s else None
            summary["recent"].append({"date": d, "symbol": sym, "group": c["g"], "close": c["c"], "stop": c["sl"], "t1": c["t1"],
                                      "now": now, "since_pct": None if now is None else round(100 * (now / c["c"] - 1), 2),
                                      "r": c.get("r") or {}})
    return summary
