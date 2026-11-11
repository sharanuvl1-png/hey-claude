package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDigestTest {
    private val md = """
        # Mehitha daily stock scan, 2026-11-11

        ## Watchlist

        | Group | Stock | Close | Verdict | Grade | Entry | Stop-loss (risk) | Target 1 | Chart |
        |---|---|---|---|---|---|---|---|---|
        | Your picks | PCJEWELLER.NS | ₹15.8 | 🟡 BUY ON DIP | A | ₹13.7–15.5 | ₹12.3 (16%) | ₹18.0 | PCJEWELLER/2026-11-11.png |
        | Nifty 50 | ICICI Bank Limited | ₹1589.0 | 🟡 BUY ON DIP | A | ₹1564.9–1589.0 | ₹1353.4 (14%) | ₹1912.3 | ICICIBANK/2026-11-11.png |

        ## Top 5 buy setups today: Nifty 50

        | # | Stock | Close | Verdict | Entry | Stop-loss (risk) | Target 1 | ROE | Debt/Eq | Profit growth | Sales growth | P/E |
        |---|---|---|---|---|---|---|---|---|---|---|---|
        | 1 | Adani Ports and Special Economic Zone Limited (ADANIPORTS) | ₹1907.0 | 🟡 BUY ON DIP | ₹1800.3–1838.6 | ₹1674.6 (8%) | ₹1965.7 | 16% | 0.63 | 9% | 24% | 32 |

        ## Top 5 buy setups today: Midcap 150

        | # | Stock | Close | Verdict | Entry | Stop-loss (risk) | Target 1 | ROE | Debt/Eq | Profit growth | Sales growth | P/E |
        |---|---|---|---|---|---|---|---|---|---|---|---|
        | 1 | The Federal Bank Limited (FEDERALBNK) | ₹361.6 | 🟡 BUY ON DIP | ₹347.3–353.2 | ₹327.1 (7%) | ₹384.9 | 13% | n/a | 46% | 36% | 18 |

        ## Top 5 buy setups today: Smallcap 100

        | # | Stock | Close | Verdict | Entry | Stop-loss (risk) | Target 1 | ROE | Debt/Eq | Profit growth | Sales growth | P/E |
        |---|---|---|---|---|---|---|---|---|---|---|---|
    """.trimIndent()

    @Test fun summarisesYourPicksAndTopPickPerGroup() {
        val a = ScanDigest.alert(md, "2026-11-11")!!
        assertEquals("scan:2026-11-11", a.id)
        assertTrue(a.text, a.text.startsWith("PCJEWELLER buy on dip, entry ₹13.7–15.5, stop ₹12.3, target ₹18.0."))
        assertTrue(a.text, a.text.contains("Nifty 50: Adani Ports and Special Economic Zone buy on dip, entry ₹1800.3–1838.6, stop ₹1674.6, target ₹1965.7"))
        assertTrue(a.text, a.text.contains("Midcap: The Federal Bank buy on dip"))
        assertTrue(a.text, a.text.contains("Smallcap: no picks"))
        assertTrue(!a.text.contains("ICICI"))
    }

    @Test fun staleOrMissingScanGivesNothing() {
        assertNull(ScanDigest.alert(md, "2026-11-12"))
        assertNull(ScanDigest.alert("", "2026-11-11"))
    }
}
