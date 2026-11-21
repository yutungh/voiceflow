package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * "Install when the user opens the app" counts openings of the whole app: moving between
 * its pages is the same opening, leaving it and coming back is a new one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AppOpeningTest {
    private static long opening() {
        return ReflectionHelpers.getStaticField(SettingsActivity.class, "opening");
    }

    @Test public void pagesShareAnOpeningAndLeavingStartsANewOne() {
        Intent home = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class);
        Intent styles = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PAGE_STYLES);
        ActivityController<SettingsActivity> first = Robolectric.buildActivity(SettingsActivity.class, home).setup();
        long opened = opening();

        // Home -> Styles: the new page starts before Home stops.
        first.pause();
        ActivityController<SettingsActivity> page = Robolectric.buildActivity(SettingsActivity.class, styles).setup();
        first.stop();
        assertEquals("a page is the same opening", opened, opening());

        // Back to Home.
        page.pause();
        first.restart().resume();
        page.stop().destroy();
        assertEquals("coming back from a page is the same opening", opened, opening());

        // Home button, then open the app again.
        first.pause().stop();
        first.restart().resume();
        assertNotEquals("leaving and coming back is a new opening", opened, opening());
        first.pause().stop().destroy();
    }
}
