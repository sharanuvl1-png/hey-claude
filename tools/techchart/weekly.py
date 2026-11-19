"""Sunday weekly report pushed to the ntfy app: how the automatic paper trades and the board's calls did.
Reads the live board's data.json (real scan results only). Nothing here places or changes an order."""
import datetime as dt
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import breakouts as bo  # noqa: E402

URL = "https://sharanuvl1-png.github.io/hey-claude/data.json"


def report(data, today):
    week_ago = (today - dt.timedelta(days=7)).isoformat()
    T = (data.get("track_record") or {}).get("auto_paper") or []
    closed = [t for t in T if t.get("exit") is not None and t["exit_date"] > week_ago]
    open_ = [t for t in T if t.get("exit") is None]
    pct = lambda t, px: 100 * (px / t["entry"] - 1)  # noqa: E731
    lines = []
    if closed:
        wins = [t for t in closed if t["exit"] > t["entry"]]
        avg = sum(pct(t, t["exit"]) for t in closed) / len(closed)
        best = max(closed, key=lambda t: pct(t, t["exit"]))
        lines.append(f"Auto paper trades closed this week: {len(closed)}, {len(wins)} won ({round(100 * len(wins) / len(closed))}%), "
                     f"average {avg:+.1f}% per trade. Best: {best['sym']} {pct(best, best['exit']):+.1f}%.")
    else:
        lines.append("No automatic paper trade closed this week.")
    live = [t for t in open_ if t.get("now")]
    if live:
        avg = sum(pct(t, t["now"]) for t in live) / len(live)
        lines.append(f"Still open: {len(open_)}, average {avg:+.1f}% so far.")
    bv = ((data.get("track_record") or {}).get("by_verdict") or {}).get("BUY NOW") or {}
    d = bv.get("10") or bv.get("5")
    if d:
        lines.append(f"Buy now calls: {d['win']}% up after {10 if bv.get('10') else 5} days (avg {d['avg']:+.1f}%, {d['n']} scored).")
    sig = (data.get("sr_track_record") or {}).get("by_signal") or {}
    names = {"bo": "S/R breakout", "buy": "S/R buy zone"}
    best = [(names[k], v.get("10") or v.get("5")) for k, v in sig.items() if k in names and (v.get("10") or v.get("5"))]
    if best:
        k, v = max(best, key=lambda x: x[1]["avg"])
        lines.append(f"Best signal so far: {k}, {v['win']}% up, avg {v['avg']:+.1f}% ({v['n']} scored).")
    ctx = data.get("context") or {}
    if (ctx.get("regime") or {}).get("mood"):
        lines.append(f"Market mood going into the week: {ctx['regime']['mood']}.")
    lines.append(f"Board as of {data.get('generated_at', '?')[:16].replace('T', ' ')}. Practice only; decisions are yours.")
    return " ".join(lines)


def main():
    topic = os.environ.get("NTFY_TOPIC")
    with urllib.request.urlopen(urllib.request.Request(URL, headers={"User-Agent": "swing-board"}), timeout=30) as r:
        data = json.load(r)
    text = report(data, dt.datetime.now(bo.IST).date())
    print(text)
    if topic:
        bo.ntfy(topic, [{"title": "Your swing week in review", "text": text, "link": "https://sharanuvl1-png.github.io/hey-claude/"}])


if __name__ == "__main__":
    main()
