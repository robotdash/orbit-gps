package com.robotdash.orbitgps

import androidx.test.core.app.ApplicationProvider
import com.robotdash.orbitgps.search.Categories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Oct 21: the main screen's place shortcuts are the user's choice of 3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShortcutsTest {
    private val s get() = Settings(ApplicationProvider.getApplicationContext())

    private val home = Settings.HOME; private val work = Settings.WORK; private val fav = Settings.FAVOURITES

    @Test fun startsLikeBefore() {
        assertEquals(listOf(home, work, fav, "Gas", "Food", "Coffee"), s.shortcutSlots())
        s.shortcutSlots().filter { !it.startsWith("@") }.forEach { l -> assertTrue(l, Categories.all.any { it.label == l }) }
    }

    @Test fun dropReplacesASlotAndKeepsSix() {
        s.pinShortcut(5, "Rest areas")
        assertEquals(listOf(home, work, fav, "Gas", "Food", "Rest areas"), s.shortcutSlots())
        s.pinShortcut(1, "Free camping")          // don't work: Work out, Free camping in
        assertEquals(listOf(home, "Free camping", fav, "Gas", "Food", "Rest areas"), s.shortcutSlots())
    }

    @Test fun droppingOneThatIsAlreadyThereSwaps() {
        s.pinShortcut(0, "Coffee")
        assertEquals(listOf("Coffee", work, fav, "Gas", "Food", home), s.shortcutSlots())
    }

    @Test fun threeFromVersion28MoveAfterHomeWorkFavourites() {
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("shortcuts", "Gas|Rest areas|Coffee").commit()
        assertEquals(listOf(home, work, fav, "Gas", "Rest areas", "Coffee"), s.shortcutSlots())
    }
}
