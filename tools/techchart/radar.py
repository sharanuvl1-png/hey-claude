"""Market radar: the whole NSE in two steps, plus sector rotation, 52-week highs, big-money deals and an
event radar. Runs once each evening after NSE publishes its end-of-day files; the swing board shows it
the next time it refreshes.

  1. Whole-NSE scan. Step 1 is a quick filter over every EQ stock in NSE's own daily bhavcopies (last
     ~22 sessions): close above Rs 50, average traded value of at least Rs 5 crore a day, and a close
     within 3% of (or above) its 20-day high. Step 2 downloads a year of prices for the liquid stocks,
     ranks the near-breakout ones, and runs the full technical chart and verdict on the top 30.
  2. Sector rotation: Nifty 500 industries ranked by their stocks' median 1-month and 3-month return,
     with the 3 strongest stocks (above their 50-day average) in the top 2 sectors.
  3. 52-week highs: liquid stocks within 5% of their 52-week high on at least 1.5x normal volume.
  4. Big money: today's bulk and block deals (NSE files), and stocks whose delivery quantity is at least
     twice their normal daily volume with 60%+ delivery.
  5. Event radar: results, bonus issues and splits in the next 7 days (NSE's corporate calendars).

Every number comes from NSE's files or Yahoo Finance prices; anything that can't be fetched is left
out with a note, never guessed. Analysis only: nothing here places, changes or cancels an order.

Usage: python radar.py --out radar_out [--limit N]
"""
import argparse
import csv
import datetime as dt
import io
import json
import os
import shutil
import statistics as st
import sys
import time
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
      "Accept": "*/*", "Referer": "https://www.nseindia.com/"}
MIN_PRICE, MIN_VALUE_CR, NEAR_HIGH_PCT, TOP_N = 50.0, 5.0, 3.0, 30
NIFTY500_CSV = "ind_nifty500list.csv"


def get(url, timeout=25):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read()


def bhavcopy(d):
    """{SYM: {o,h,l,c,pc,vol,val}} for EQ stocks on day d from NSE's UDiFF bhavcopy, or None."""
    url = f"https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_{d:%Y%m%d}_F_0000.csv.zip"
    try:
        z = zipfile.ZipFile(io.BytesIO(get(url)))
        text = z.read(z.namelist()[0]).decode("utf-8", "replace")
    except Exception:
        return None
    out = {}
    for row in csv.DictReader(io.StringIO(text)):
        if row.get("SctySrs") != "EQ":
            continue
        try:
            out[row["TckrSymb"].strip()] = {"o": float(row["OpnPric"]), "h": float(row["HghPric"]), "l": float(row["LwPric"]),
                                            "c": float(row["ClsPric"]), "pc": float(row["PrvsClsgPric"]),
                                            "vol": float(row["TtlTradgVol"]), "val": float(row["TtlTrfVal"])}
        except (KeyError, ValueError):
            continue
    return out or None


def sessions(today, n=22, max_back=45):
    """The last n trading sessions NSE has published: [(date, bhav)], newest first."""
    out = []
    for back in range(max_back):
        d = today - dt.timedelta(days=back)
        if d.weekday() >= 5:
            continue
        b = bhavcopy(d)
        if b:
            out.append((d.isoformat(), b))
            if len(out) >= n:
                break
        time.sleep(0.3)
    return out


def quick_filter(days):
    """Step 1 over the whole NSE: liquid stocks, and the ones near or above their 20-day high."""
    latest_day, latest = days[0]
    liquid, near = {}, []
    for sym, t in latest.items():
        hist = [b[sym] for _, b in days if sym in b]
        if len(hist) < 15 or t["c"] < MIN_PRICE:
            continue
        avg_val = st.mean(x["val"] for x in hist[1:]) / 1e7   # Rs crore
        if avg_val < MIN_VALUE_CR:
            continue
        avg_vol = st.mean(x["vol"] for x in hist[1:]) or 1
        hi20 = max(x["h"] for x in hist[1:21])
        info = {"symbol": sym, "close": t["c"], "change_pct": round(100 * (t["c"] / t["pc"] - 1), 2) if t["pc"] else None,
                "value_cr": round(t["val"] / 1e7, 1), "avg_value_cr": round(avg_val, 1),
                "vol_ratio": round(t["vol"] / avg_vol, 2), "hi20": hi20, "gap_pct": round(100 * (hi20 - t["c"]) / hi20, 2)}
        liquid[sym] = info
        if info["gap_pct"] <= NEAR_HIGH_PCT:
            near.append(info)
    print(f"Step 1 ({latest_day}): {len(latest)} EQ stocks, {len(liquid)} liquid, {len(near)} near or above their 20-day high")
    return latest_day, liquid, near


def yahoo_year(symbols):
    """{SYM: daily frame} for a year, in batches."""
    import yfinance as yf
    frames = {}
    for i in range(0, len(symbols), 200):
        batch = [s + ".NS" for s in symbols[i:i + 200]]
        try:
            d = yf.download(batch, period="1y", interval="1d", auto_adjust=False, group_by="ticker", progress=False, threads=True)
        except Exception as e:
            print(f"yahoo batch {i}: {type(e).__name__}", file=sys.stderr)
            continue
        for s in batch:
            try:
                f = d[s][["Open", "High", "Low", "Close", "Volume"]].dropna()
            except Exception:
                continue
            if len(f) >= 60:
                frames[s[:-3]] = f
        time.sleep(1)
    return frames


def metrics(f):
    c, v = f["Close"].astype(float), f["Volume"].astype(float)
    last = float(c.iloc[-1])
    ema50 = float(c.ewm(span=50, adjust=False).mean().iloc[-1])
    hi52 = float(f["High"].astype(float).max())
    avg20 = float(v.iloc[-21:-1].mean()) or 1.0
    ret = lambda n: round(100 * (last / float(c.iloc[-1 - n]) - 1), 2) if len(c) > n else None  # noqa: E731
    return {"close": round(last, 2), "date": f.index[-1].date().isoformat(), "ema50": round(ema50, 2), "above50": last > ema50,
            "hi52": round(hi52, 2), "from_hi52_pct": round(100 * (hi52 - last) / hi52, 2), "vol_ratio": round(float(v.iloc[-1]) / avg20, 2),
            "r1m": ret(21), "r3m": ret(63), "r6m": ret(126)}


def nifty_return(n):
    import yfinance as yf
    try:
        c = yf.Ticker("^NSEI").history(period="1y", interval="1d")["Close"].dropna()
        return round(100 * (float(c.iloc[-1]) / float(c.iloc[-1 - n]) - 1), 2)
    except Exception:
        return None


def sectors(mets):
    """Nifty 500 industries ranked by their members' median 1- and 3-month returns."""
    import dashboard as db
    ind = db.industries(NIFTY500_CSV)
    if not ind:
        return None, "Nifty 500 industry list could not be fetched."
    groups = {}
    for sym, m in mets.items():
        if sym in ind and m["r1m"] is not None and m["r3m"] is not None:
            groups.setdefault(ind[sym], []).append((sym, m))
    rows = []
    for name, mem in groups.items():
        if len(mem) < 4:
            continue
        r1, r3 = st.median(m["r1m"] for _, m in mem), st.median(m["r3m"] for _, m in mem)
        breadth = round(100 * sum(m["above50"] for _, m in mem) / len(mem))
        rows.append({"sector": name, "stocks": len(mem), "r1m": round(r1, 2), "r3m": round(r3, 2), "above50_pct": breadth,
                     "score": round(0.5 * r1 + 0.5 * r3, 2), "_mem": mem})
    rows.sort(key=lambda r: -r["score"])
    for i, r in enumerate(rows):
        r["rank"] = i + 1
        mem = r.pop("_mem")
        if i < 2:
            best = sorted([x for x in mem if x[1]["above50"]], key=lambda x: -x[1]["r3m"])[:3]
            r["leaders"] = [{"symbol": s, "close": m["close"], "r1m": m["r1m"], "r3m": m["r3m"], "from_hi52_pct": m["from_hi52_pct"]} for s, m in best]
    return rows, None


def deals():
    """Today's bulk and block deals from NSE's files."""
    out, notes = [], []
    for kind in ("bulk", "block"):
        try:
            text = get(f"https://nsearchives.nseindia.com/content/equities/{kind}.csv").decode("utf-8", "replace")
            for r in csv.DictReader(io.StringIO(text)):
                r = {k.strip(): (v or "").strip() for k, v in r.items() if k}
                try:
                    qty = float(r["Quantity Traded"].replace(",", ""))
                    px = float(r["Trade Price / Wght. Avg. Price"].replace(",", ""))
                except (KeyError, ValueError):
                    continue
                out.append({"kind": kind, "date": r.get("Date"), "symbol": r.get("Symbol"), "name": r.get("Security Name"),
                            "client": r.get("Client Name"), "side": r.get("Buy/Sell"), "qty": qty, "price": px,
                            "value_cr": round(qty * px / 1e7, 2)})
        except Exception as e:
            notes.append(f"{kind} deals: {type(e).__name__}")
    out.sort(key=lambda d: -d["value_cr"])
    return out, notes


def delivery(day, liquid, days):
    """Stocks whose delivered quantity is at least twice their normal daily volume, with 60%+ delivery."""
    d = dt.date.fromisoformat(day)
    try:
        text = get(f"https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_{d:%d%m%Y}.csv").decode("utf-8", "replace")
    except Exception as e:
        return [], f"delivery file: {type(e).__name__}"
    out = []
    for r in csv.DictReader(io.StringIO(text)):
        r = {k.strip(): (v or "").strip() for k, v in r.items() if k}
        sym = r.get("SYMBOL")
        if r.get("SERIES") != "EQ" or sym not in liquid:
            continue
        try:
            dq, dp, close = float(r["DELIV_QTY"]), float(r["DELIV_PER"]), float(r["CLOSE_PRICE"])
        except (KeyError, ValueError):
            continue
        vols = [b[sym]["vol"] for _, b in days[1:] if sym in b]
        avg = st.mean(vols) if vols else 0
        if avg and dq >= 2 * avg and dp >= 60:
            out.append({"symbol": sym, "close": close, "deliv_pct": dp, "deliv_x": round(dq / avg, 1),
                        "change_pct": liquid[sym]["change_pct"], "value_cr": round(dq * close / 1e7, 1)})
    out.sort(key=lambda x: -x["deliv_x"])
    return out[:25], None


def nse_session():
    import http.cookiejar
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    op.addheaders = list(UA.items())
    op.open("https://www.nseindia.com/", timeout=20).read()
    return op


def events(today, focus):
    """Results, bonus issues and splits in the next 7 days, from NSE's corporate calendars."""
    end = today + dt.timedelta(days=7)
    out, notes = [], []
    try:
        op = nse_session()
    except Exception as e:
        return [], [f"NSE website: {type(e).__name__}"]
    f, t = today.strftime("%d-%m-%Y"), end.strftime("%d-%m-%Y")
    try:
        js = json.loads(op.open(f"https://www.nseindia.com/api/event-calendar?index=equities&from_date={f}&to_date={t}", timeout=20).read())
        for e in js if isinstance(js, list) else js.get("data", []):
            purpose = (e.get("purpose") or "")
            if "result" in purpose.lower():
                out.append({"symbol": e.get("symbol"), "date": e.get("date"), "kind": "results", "detail": purpose[:80]})
    except Exception as e:
        notes.append(f"results calendar: {type(e).__name__}")
    try:
        js = json.loads(op.open(f"https://www.nseindia.com/api/corporates-corporateActions?index=equities&from_date={f}&to_date={t}", timeout=20).read())
        for e in js if isinstance(js, list) else js.get("data", []):
            sub = (e.get("subject") or "")
            low = sub.lower()
            kind = "bonus" if "bonus" in low else "split" if "split" in low or "sub-division" in low or "subdivision" in low else None
            if kind:
                out.append({"symbol": e.get("symbol"), "date": e.get("exDate"), "kind": kind, "detail": sub[:80]})
    except Exception as e:
        notes.append(f"corporate actions: {type(e).__name__}")
    for e in out:
        try:
            e["date"] = dt.datetime.strptime(e["date"], "%d-%b-%Y").date().isoformat()
        except (TypeError, ValueError):
            pass
        e["on_board"] = e["symbol"] in focus
    out.sort(key=lambda e: (not e["on_board"], e["date"] or "", e["symbol"] or ""))
    return out[:80], notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="radar_out")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--focus", default="", help="symbols to mark in the event radar (comma separated file or list)")
    args = ap.parse_args()
    now = dt.datetime.now(IST)
    out = args.out
    shutil.rmtree(out, ignore_errors=True)
    for sub in ("charts", "reports"):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
    notes = []
    data = {"generated_at": now.isoformat(timespec="minutes"), "source": "NSE end-of-day files and Yahoo Finance daily prices",
            "rules": {"min_price": MIN_PRICE, "min_value_cr": MIN_VALUE_CR, "near_high_pct": NEAR_HIGH_PCT, "top_n": TOP_N}}

    days = sessions(now.date())
    if not days:
        data.update(error="NSE's daily files could not be fetched, so the radar has nothing new.", notes=notes)
        json.dump(data, open(os.path.join(out, "radar.json"), "w"), separators=(",", ":"))
        print(data["error"])
        return
    day, liquid, near = quick_filter(days)
    data.update(as_of=day, universe={"eq": len(days[0][1]), "liquid": len(liquid), "near_high": len(near)})

    syms = sorted(liquid)
    if args.limit:
        syms = sorted(near, key=lambda x: x["gap_pct"])[:args.limit]
        syms = [x["symbol"] for x in syms]
    frames = yahoo_year(syms)
    mets = {}
    for s, f in frames.items():
        try:
            mets[s] = metrics(f)
        except Exception:
            pass
    print(f"Step 2: a year of prices for {len(mets)} of {len(syms)} liquid stocks")

    # 1. Whole-NSE top 30: near the 20-day high, in an uptrend, ranked by strength and volume.
    n1m = nifty_return(21)
    cands = []
    for x in near:
        m = mets.get(x["symbol"])
        if not m or not m["above50"]:
            continue
        rs = (m["r1m"] or 0) - (n1m or 0)
        cands.append((rs + 2 * min(x["vol_ratio"], 4) - x["gap_pct"], x, m))
    cands.sort(key=lambda t: -t[0])
    import techchart as tc
    import dashboard as db
    work = os.path.join(out, "_work")
    os.makedirs(work, exist_ok=True)
    top = []
    for _, x, m in cands[:TOP_N + 10]:
        if len(top) >= TOP_N:
            break
        try:
            r = tc.run(x["symbol"] + ".NS", x["symbol"], work)
            row = db.summarise(r)
            shutil.copy(r["png"], os.path.join(out, row["chart"]))
            shutil.copy(r["md"], os.path.join(out, row["report"]))
            row.update(group="radar", chart="radar/" + row["chart"], report="radar/" + row["report"],
                       radar={"gap_pct": x["gap_pct"], "vol_ratio": x["vol_ratio"], "avg_value_cr": x["avg_value_cr"],
                              "r1m": m["r1m"], "r3m": m["r3m"], "from_hi52_pct": m["from_hi52_pct"], "rs_1m": None if n1m is None else round((m["r1m"] or 0) - n1m, 2)})
            top.append(row)
            print(f"[radar {len(top)}/{TOP_N}] {row['symbol']}: {row['close']} {row['verdict']}")
        except SystemExit as e:
            notes.append(f"{x['symbol']}: {e}")
        except Exception as e:
            notes.append(f"{x['symbol']}: {type(e).__name__}")
        time.sleep(0.2)
    shutil.rmtree(work, ignore_errors=True)
    order = {"BUY NOW": 0, "BUY ON DIP": 1, "WAIT": 2, "AVOID": 3}
    top.sort(key=lambda r: (order[r["verdict"]], -r["confidence"]))
    data["top"] = top

    # 2. Sector rotation.
    rows, err = sectors(mets)
    data["sectors"] = rows
    if err:
        notes.append(err)

    # 3. 52-week highs on strong volume.
    highs = [dict(symbol=s, **{k: m[k] for k in ("close", "date", "hi52", "from_hi52_pct", "vol_ratio", "r3m")})
             for s, m in mets.items() if m["from_hi52_pct"] <= 5 and m["vol_ratio"] >= 1.5]
    highs.sort(key=lambda h: (h["from_hi52_pct"], -h["vol_ratio"]))
    data["highs"] = highs[:40]

    # 4. Big money.
    dl, dnotes = deals()
    dv, dnote = delivery(day, liquid, days)
    data["bigmoney"] = {"deals": dl[:40], "delivery": dv}
    notes += dnotes + ([dnote] if dnote else [])

    # 5. Event radar.
    focus = set(t["symbol"] for t in top) | set(h["symbol"] for h in highs)
    for p in args.focus.split(","):
        p = p.strip()
        if p and os.path.exists(p):
            focus |= {ln.strip().upper() for ln in open(p) if ln.strip()}
        elif p:
            focus.add(p.upper())
    ev, enotes = events(now.date(), focus)
    data["events"] = ev
    notes += enotes

    data["notes"] = notes
    with open(os.path.join(out, "radar.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"Radar {day}: top {len(top)}, sectors {len(rows or [])}, 52w highs {len(highs)}, deals {len(dl)}, "
          f"delivery {len(dv)}, events {len(ev)}; notes {notes}")


if __name__ == "__main__":
    main()
