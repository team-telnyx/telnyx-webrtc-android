package com.telnyx.webrtc.sdk.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.schibsted.spain.barista.interaction.BaristaClickInteractions.clickOn
import com.telnyx.webrtc.sdk.MOCK_CALLER_NUMBER
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.testhelpers.BaseUITest
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.runner.RunWith


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
            clickOn(R.id.connect_button_id)

            Thread.sleep(1500)
            onView(withId(R.id.socket_text_value)).check(matches(withText(R.string.connected)));
        }
    }

    @Test
    fun onDisconnect() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            clickOn(R.id.connect_button_id)

            Thread.sleep(1500)

            openActionBarOverflowOrOptionsMenu(context);
            onView(withText("Disconnect"))
                .perform(ViewActions.click())
        }
    }

    @Test
    fun onSendInvite() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            clickOn(R.id.connect_button_id)
            Thread.sleep(2000)

            onView(withId(R.id.call_button_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(2000)
        }
    }

    @Test
    fun onCallSelfAndAnswerAndEndToEndTest() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            clickOn(R.id.connect_button_id)
            Thread.sleep(2000)

            onView(withId(R.id.call_input_id))
                .perform(setTextInTextView(MOCK_CALLER_NUMBER))

            onView(withId(R.id.call_button_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(4000)

            onView(withId(R.id.answer_call_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(2000)

            onView(withId(R.id.fragment_call_instance)).check(matches(isDisplayed()))

            onView(withId(R.id.mute_button_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(2000)

            onView(withId(R.id.loud_speaker_button_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(2000)

            onView(withId(R.id.end_call_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(2000)
        }
    }

    @Test
    fun onCallSelfAndRejectTest() {
        //Wait for call to reset after previous test otherwise will fail in pipeline
        Thread.sleep(5000)
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            clickOn(R.id.connect_button_id)

            Thread.sleep(2000)

            onView(withId(R.id.call_input_id))
                .perform(setTextInTextView(MOCK_CALLER_NUMBER))

            onView(withId(R.id.call_button_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
            Thread.sleep(4000)

            onView(withId(R.id.reject_call_id))
                .perform(ViewActions.closeSoftKeyboard())
                .perform(ViewActions.click())
        }
    }

    private fun setTextInTextView(value: String?): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): org.hamcrest.Matcher<View> {
                return allOf(isDisplayed(), isAssignableFrom(TextView::class.java))
            }

            override fun perform(uiController: UiController, view: View) {
                (view as TextView).text = value
            }

            override fun getDescription(): String {
                return "replace text"
            }
        }
    }

}