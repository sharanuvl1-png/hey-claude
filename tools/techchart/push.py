"""Phone alerts for the swing board (sent through the ntfy app when the NTFY_TOPIC secret is set).

Built once per scan by dashboard.py; every alert has an id and goes out only once (ids are kept in
history.json). Nothing here places, changes or cancels an order.

  * Buy now: any tab (Nifty 100, Midcap, Smallcap), only when the swing call is Buy now AND the S/R rule
    says buy or breakout AND the market mood is up with India VIX below 20. At most 3 a day, one per
    Buy now streak.
  * Breakout: a Nifty 100 or Midcap stock closes above its 20-day high on 1.5x normal volume (after-close
    scan only). At most 3 a day.
  * Holdings and watched stocks: broke below support, hit the stop, reached target 1, entered the Buy on
    dip entry zone, turned Buy now, results due within 3 days.
  * Morning digest (the pre-open scan): market mood, VIX, overnight markets, holdings at risk, top setups.
  * Quiet hours: nothing is built between 9 PM and 7 AM IST, so it goes out with the next scan instead.
"""
import datetime as dt
import json
import urllib.request

import history as hi

DAILY_CAP = 3
WATCH_SUFFIX = "-watch"


def quiet(now):
    return now.hour >= 21 or now.hour < 7


def watch_from_phone(topic, h, now):
    """Stocks the app on the phone asked to watch (posted to <topic>-watch, which nobody subscribes to).
    The latest list is kept in history.json, because ntfy only keeps messages for about 12 hours."""
    try:
        url = f"https://ntfy.sh/{topic}{WATCH_SUFFIX}/json?poll=1&since=24h"
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "swing-board"}), timeout=15) as r:
            lines = r.read().decode("utf-8", "replace").splitlines()
        for ln in reversed(lines):
            m = json.loads(ln)
            if m.get("event") != "message":
                continue
            syms = json.loads(m.get("message") or "{}").get("syms") or []
            syms = sorted({str(s).upper().strip() for s in syms if str(s).strip().replace("-", "").replace("&", "").isalnum()})[:40]
            h["watch"] = {"syms": syms, "at": dt.datetime.fromtimestamp(m.get("time", 0), dt.timezone.utc).isoformat(timespec="minutes")}
            break
    except Exception as e:
        print(f"watchlist sync: {type(e).__name__}")
    return set((h.get("watch") or {}).get("syms") or [])


def _streak_start(h, sym, verdict):
    """First day of the stock's current run of the same verdict, from the saved calls."""
    start = None
    for d in sorted(h["calls"], reverse=True):
        c = h["calls"][d].get(sym)
        if not c or c["v"] != verdict:
            break
        start = d
    return start


def _inr(v):
    return f"₹{v:,.2f}"


def build(h, now, board, held, watch, context, results):
    """board: {group: [rows]} for n100 / mid / small / pf / watch. Returns the alerts that are new."""
    if quiet(now):
        print("Quiet hours: no alerts built this scan.")
        return []
    today = now.date().isoformat()
    after_close = now.hour * 60 + now.minute >= 15 * 60 + 30 or now.weekday() >= 5
    pre_open = now.weekday() < 5 and now.hour * 60 + now.minute < 9 * 60 + 15
    reg = (context or {}).get("regime") or {}
    vix = (context or {}).get("vix") or {}
    mood_ok = reg.get("mood") == "up" and vix.get("level") != "high"
    by_sym = {}
    for g in ("pf", "watch", "small", "mid", "n100"):
        for r in board.get(g, []):
            by_sym[r["symbol"]] = r
    sent = set(h.get("sent_alerts") or [])
    alerts = []

    def add(aid, title, text, cap_key=None):
        if aid not in sent and all(a["id"] != aid for a in alerts):
            alerts.append({"id": aid, "title": title, "text": text, "cap": cap_key})

    def sr(r):
        try:
            return hi.sr_signal(r)[0] if (r.get("levels") or {}).get("support") else None
        except Exception:
            return None

    # Buy now from every tab, only when both rules agree and the market is in good shape.
    if mood_ok:
        cands = [r for g in ("n100", "mid", "small") for r in board.get(g, [])
                 if r["verdict"] == "BUY NOW" and sr(r) in ("buy", "bo")]
        cands.sort(key=lambda r: (-r["confidence"], -r.get("score", 0)))
        for r in cands:
            start = _streak_start(h, r["symbol"], "BUY NOW") or today
            add(f"buy:{r['symbol']}:{start}", f"{r['symbol']}: Buy now, both rules agree",
                f"{r['symbol']} at {_inr(r['close'])}. Entry {_inr(r['entry'][0])}–{_inr(r['entry'][1])}, stop {_inr(r['stop'])}, "
                f"target {_inr(r['targets'][0])}. Market trending up, VIX {vix.get('close', '?')}. Check the chart first; the decision is yours.",
                "buy")

    # Breakouts on volume, judged on the closing price.
    if after_close:
        bos = [r for g in ("n100", "mid") for r in board.get(g, []) if sr(r) == "bo" and (r.get("volume_ratio") or 0) >= hi.SR_VOL
               and len(r.get("closes") or []) > 21 and r["close"] > max(r["closes"][-21:-1])]
        bos.sort(key=lambda r: -(r.get("volume_ratio") or 0))
        for r in bos:
            add(f"bo:{r['symbol']}:{r['date']}", f"{r['symbol']} broke out on volume",
                f"{r['symbol']} closed at {_inr(r['close'])}, above its 20-day high, on {r['volume_ratio']:.1f}x normal volume."
                + ("" if mood_ok else " Market mood isn't strong, so breakouts fail more often."), "bo")

    # Holdings and watched stocks.
    for sym in sorted(set(held) | set(watch)):
        r = by_sym.get(sym)
        if not r:
            continue
        who = "you hold it" if sym in held else "on your watchlist"
        lv = r.get("levels") or {}
        lo = (lv.get("support") or [None])[0]
        if lo and r["close"] < lo:
            add(f"sup:{sym}:{round(lo, 2)}", f"{sym} broke below support",
                f"{sym} ({who}) is at {_inr(r['close'])}, below its support zone starting {_inr(lo)}. Swing call: {r['verdict'].title()}.")
        elif r["close"] <= r["stop"]:
            add(f"stop:{sym}:{round(r['stop'], 2)}", f"{sym} at its stop-loss",
                f"{sym} ({who}) is at {_inr(r['close'])}, at or below the swing stop of {_inr(r['stop'])}.")
        if sym in held and r["close"] >= r["targets"][0]:
            add(f"t1:{sym}:{round(r['targets'][0], 2)}", f"{sym} reached target 1",
                f"{sym} (you hold it) is at {_inr(r['close'])}, at or above target 1 ({_inr(r['targets'][0])}). Many traders book part of the profit here.")
        if r["verdict"] == "BUY ON DIP" and r["entry"][0] <= r["close"] <= r["entry"][1]:
            add(f"entry:{sym}:{r['date']}", f"{sym} is in its buy zone",
                f"{sym} ({who}) is at {_inr(r['close'])}, inside the Buy on dip entry zone {_inr(r['entry'][0])}–{_inr(r['entry'][1])}. Stop {_inr(r['stop'])}.")
        if sym in watch and r["verdict"] == "BUY NOW":
            start = _streak_start(h, sym, "BUY NOW") or today
            add(f"wbuy:{sym}:{start}", f"{sym} turned Buy now", f"{sym} ({who}) is a Buy now call at {_inr(r['close'])}, stop {_inr(r['stop'])}, target {_inr(r['targets'][0])}.")
        rd = results.get(sym)
        if rd and 0 <= (dt.date.fromisoformat(rd) - now.date()).days <= 3:
            add(f"res:{sym}:{rd}", f"{sym} results on {dt.date.fromisoformat(rd):%d %b}",
                f"{sym} ({who}) reports results on {dt.date.fromisoformat(rd):%a %d %b} (Yahoo's date, may be an estimate). Prices can jump either way.")

    # Morning digest, once a day before the open.
    if pre_open:
        bits = []
        if reg:
            bits.append(f"Market {reg['mood']} (Nifty {reg['close']:,.0f} on {reg['date']})")
        if vix:
            bits.append(f"VIX {vix['close']:.1f} ({vix['chg_pct']:+.1f}%)")
        glob = [g for g in (context or {}).get("global", []) if g["symbol"] in ("^GSPC", "^IXIC", "^N225")]
        if glob:
            bits.append("Overnight " + ", ".join(f"{g['name']} {g['chg_pct']:+.1f}%" for g in glob))
        risk = [s for s in sorted(held) if s in by_sym and ((by_sym[s].get("levels") or {}).get("support") or [0])[0] > by_sym[s]["close"]]
        bits.append(f"Holdings below support: {', '.join(risk[:6])}" if risk else "No holding below support")
        top = [r for g in ("n100", "mid", "small") for r in board.get(g, []) if r["verdict"] == "BUY NOW" and sr(r) in ("buy", "bo")]
        top.sort(key=lambda r: -r["confidence"])
        bits.append("Top setups: " + (", ".join(r["symbol"] for r in top[:3]) if top else "none where both rules agree"))
        add(f"digest:{today}", "Good morning: today's swing board", ". ".join(bits) + ".")

    # Daily caps: at most 3 Buy now and 3 breakout pushes a day.
    day = h.setdefault("alert_days", {})
    for k in list(day):
        if k < (now.date() - dt.timedelta(days=10)).isoformat():
            del day[k]
    counts = day.setdefault(today, {})
    out = []
    for a in alerts:
        k = a.pop("cap")
        if k:
            if counts.get(k, 0) >= DAILY_CAP:
                continue
            counts[k] = counts.get(k, 0) + 1
        out.append(a)
    ids = [a["id"] for a in out]
    # Many of the same kind in one scan (say 6 holdings below support on a bad day): one push listing them.
    NAMES = {"sup": "broke below support", "stop": "hit the stop-loss", "t1": "reached target 1", "entry": "entered the buy zone",
             "res": "report results within 3 days", "wbuy": "turned Buy now"}
    merged = []
    for k, label in NAMES.items():
        same = [a for a in out if a["id"].split(":")[0] == k]
        if len(same) > 3:
            syms = [a["id"].split(":")[1] for a in same]
            merged.append({"id": f"{k}:group:{today}", "title": f"{len(same)} of your stocks {label}",
                           "text": ", ".join(syms) + ". Open the app's My portfolio tab for the details."})
            out = [a for a in out if a not in same]
    out = merged + out
    h["sent_alerts"] = (list(h.get("sent_alerts") or []) + ids)[-800:]
    print(f"Alerts: {len(out)} new ({', '.join(a['id'] for a in out) or 'none'})")
    return out
