"""Stock alerts for the phone, written after the daily scan as ALERTS.json.

Compares yesterday's scan with today's for the watchlist (target reached, stop-loss hit,
moved into the buy zone) and adds results dates and fresh news from Yahoo Finance.

    python alerts.py PREVIOUS_SCAN.md TODAY_SCAN.md [--no-network] > ALERTS.json
"""
import datetime as dt
import hashlib
import json
import re
import sys

NUM = re.compile(r"([0-9][0-9,]*(?:\.[0-9]+)?)")
MAX_NEWS = 6


def num(text):
    m = NUM.search(text or "")
    return float(m.group(1).replace(",", "")) if m else None


def watchlist(md):
    """{symbol: row} from the Watchlist table of a scan."""
    section = md.split("## Watchlist", 1)[-1].split("\n## ", 1)[0] if "## Watchlist" in md else ""
    rows = {}
    for line in section.splitlines():
        if not line.startswith("|") or line.startswith("|---") or line.startswith("| Group"):
            continue
        c = [x.strip() for x in line.strip().strip("|").split("|")]
        if len(c) < 9:
            continue
        sym = c[8].split("/")[0] or c[1]
        rows[sym] = {
            "group": c[0], "name": c[1], "close": num(c[2]),
            "verdict": re.sub(r"[^A-Za-z ]", "", c[3]).strip(),
            "stop": num(c[6]), "target": num(c[7]),
        }
    return rows


def scan_date(md):
    m = re.search(r"scan, (\d{4}-\d{2}-\d{2})", md)
    return m.group(1) if m else dt.date.today().isoformat()


def short(name):
    return re.sub(r"\s+(Limited|Ltd\.?)$", "", name)


def price_alerts(prev, today, day):
    out = []
    for sym, now in today.items():
        before = prev.get(sym)
        if not before or now["close"] is None:
            continue
        name = short(now["name"])
        if before["target"] and now["close"] >= before["target"]:
            out.append({"id": f"target:{sym}:{day}", "title": f"{name} reached its target",
                        "text": f"{name} closed at ₹{now['close']:,.1f}, at or above the target of ₹{before['target']:,.1f}."})
        if before["stop"] and now["close"] <= before["stop"]:
            out.append({"id": f"stop:{sym}:{day}", "title": f"{name} hit its stop-loss",
                        "text": f"{name} closed at ₹{now['close']:,.1f}, at or below the stop-loss of ₹{before['stop']:,.1f}."})
        if now["verdict"] == "BUY NOW" and before["verdict"] != "BUY NOW":
            out.append({"id": f"buyzone:{sym}:{day}", "title": f"{name} is in the buy zone",
                        "text": f"{name} moved from {before['verdict'].lower()} to buy now at ₹{now['close']:,.1f}."})
    return out


def results_and_news(today, day):
    import yfinance as yf
    out, news = [], []
    start = dt.date.fromisoformat(day)
    for sym, row in today.items():
        name = short(row["name"])
        ticker = yf.Ticker(sym if "." in sym else sym + ".NS")
        try:
            cal = ticker.calendar or {}
            dates = cal.get("Earnings Date") or [] if isinstance(cal, dict) else []
            for d in dates:
                d = d.date() if hasattr(d, "date") and callable(d.date) else d
                if start <= d <= start + dt.timedelta(days=2):
                    when = "tomorrow" if d == start + dt.timedelta(days=1) else ("today" if d == start else d.strftime("%-d %b"))
                    out.append({"id": f"results:{sym}:{d}", "title": f"{name} results {when}",
                                "text": f"{name} reports quarterly results {when}" + (f" ({d.strftime('%-d %b')})." if when in ("today", "tomorrow") else ".")})
                    break
        except Exception as e:  # one stock's missing data must not stop the rest
            print(f"calendar {sym}: {e}", file=sys.stderr)
        if row["group"] != "Your picks" and row["verdict"] != "BUY NOW":
            continue
        try:
            for item in (ticker.news or [])[:5]:
                content = item.get("content", item)
                title = content.get("title")
                when = content.get("pubDate") or content.get("providerPublishTime")
                if isinstance(when, (int, float)):
                    when = dt.datetime.fromtimestamp(when, dt.timezone.utc)
                elif isinstance(when, str):
                    when = dt.datetime.fromisoformat(when.replace("Z", "+00:00"))
                if title and when and dt.datetime.now(dt.timezone.utc) - when < dt.timedelta(hours=24):
                    news.append((when, {"id": f"news:{sym}:{hashlib.md5(title.encode()).hexdigest()[:10]}", "title": f"News: {name}", "text": title}))
                    break
        except Exception as e:
            print(f"news {sym}: {e}", file=sys.stderr)
    news.sort(key=lambda x: x[0], reverse=True)
    return out + [n for _, n in news[:MAX_NEWS]]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    prev_md = open(args[0]).read() if len(args) > 0 else ""
    today_md = open(args[1]).read()
    day = scan_date(today_md)
    today = watchlist(today_md)
    alerts = price_alerts(watchlist(prev_md), today, day) if scan_date(prev_md) != day else []
    if "--no-network" not in sys.argv:
        alerts += results_and_news(today, day)
    json.dump({"date": day, "alerts": alerts}, sys.stdout, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
