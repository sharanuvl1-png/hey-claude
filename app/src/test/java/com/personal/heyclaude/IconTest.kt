package com.personal.heyclaude

import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IconTest {
    /** The app icon is an adaptive icon with a background, a sparkle and a themed (monochrome) layer. */
    @Test fun appIconIsAdaptive() {
        val app = RuntimeEnvironment.getApplication()
        val icon = app.getDrawable(R.drawable.ic_mehitha)
        assertTrue("icon is ${icon?.javaClass}", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)
        assertTrue(app.applicationInfo.icon == R.drawable.ic_mehitha)
    }
}
