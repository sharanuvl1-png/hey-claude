# How the breakout rules were chosen

`backtest.py` (Actions → **Breakout rules backtest**) ran on 11 Nov 2026 over 96 of the 100 current Nifty 100 stocks, 14 Nov 2022 to 13 Aug 2026, Yahoo Finance daily prices. Entry at the breakout-day close, stop 1 ATR under the breakout level (max 8%), 0.2% costs. R = profit in units of the risk taken. Run log: https://github.com/sharanuvl1-png/hey-claude/actions/runs/43010088898

| Rule set | Alerts / month | Avg R, fixed 2R target | Avg R, exit on close under 10-day low | Profit factor (10-day low) |
|---|---|---|---|---|
| Loose: volume 1.2x + above EMA50 | 66.5 | +0.15 | +0.54 | 1.85 |
| Original: volume 1.5x, EMA50/200, RSI 50–75 | 32.8 | +0.14 | +0.44 | 1.69 |
| Previous (all filters incl. RSI 50–80, strong close) | 28.1 | +0.18 | +0.56 | 1.91 |
| **Chosen: previous − RSI − strong close + market uptrend** | **30.0** | **+0.25** | **+0.71** | **2.18** |

What the test showed:
- **The exit matters more than any entry filter.** A trailing exit (sell on a close under the 10-day low, or under the 20 EMA) earned about three times the fixed 2R target with the same entries.
- **Helps:** Nifty 50 above its 50-day EMA (market filter), beating the Nifty over 3 months, volume 1.5x the 50-day average (1.3x was worse; 2.0x was better per trade but cut alerts by ~40%).
- **Hurts or no help:** an RSI band (it removed good momentum breakouts), the strong-close filter, 20-day-high and 52-week-high levels instead of chart resistance, a tight-base filter (better in some years, worse in others).
- **Year by year** (20-EMA exit), the chosen set was positive in 4 of 5 years: 2022 +0.26, 2023 +1.52, 2024 −0.06, 2025 +0.29, 2026 +0.10. The previous set lost in 2024 (−0.12) and 2026 (−0.19).

Limits: 2023 was a strong bull year and carries much of the result; today's index list leaves out stocks that dropped out (slightly optimistic); intraday alerts enter earlier than the close. Past results don't guarantee future ones.
