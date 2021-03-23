package com.telnyx.webrtc.sdk.ui

import android.content.Context
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.telnyx.webrtc.sdk.testhelpers.BaseUITest
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.*
import com.telnyx.webrtc.sdk.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class MainActivityTest : BaseUITest() {

    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java, false, false)

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun onLaunchWithoutExceptionThrown() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
        }
    }

    @Test
    fun onLaunchAndConnect() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            onView(withId(R.id.connect_button_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(500)
            onView(withId(R.id.socket_text_value)).check(matches(withText("Connected")));
        }
    }

    @Test
    fun onDisconnect() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            onView(withId(R.id.connect_button_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(500)

            openActionBarOverflowOrOptionsMenu(context);
            onView(withText("Disconnect"))
                .perform(ViewActions.click())


        }
    }
}