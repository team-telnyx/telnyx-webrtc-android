package com.telnyx.webrtc.sdk.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.testhelpers.BaseUITest
import com.telnyx.webrtc.sdk.testhelpers.MOCK_CALLER_NUMBER
import com.telnyx.webrtc.sdk.testhelpers.MOCK_PASSWORD
import com.telnyx.webrtc.sdk.testhelpers.MOCK_USERNAME
import org.hamcrest.Matchers.allOf
import org.junit.*
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters


@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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
    fun a_onLaunchAndConnect() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(MOCK_USERNAME))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(MOCK_PASSWORD))
            Thread.sleep(1500)
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(1500)
            onView(withId(R.id.socket_text_value)).check(matches(withText(R.string.connected)))
        }
    }

    @Test
    fun b_testEmptyLoginDoesNotLaunchAndConnect() {
        activityRule.launchActivity(null)
        onView(withId(R.id.sip_username_id)).perform(clearText())
        onView(withId(R.id.connect_button_id))
            .perform(closeSoftKeyboard())
            .perform(click())
        Thread.sleep(1500)
        onView(withId(R.id.socket_text_value)).check(matches(withText(R.string.disconnected)))
    }

    @Test
    fun c_testEmptyPwdDoesNotLaunchAndConnect() {
        activityRule.launchActivity(null)
        onView(withId(R.id.sip_password_id)).perform(clearText())
        onView(withId(R.id.sip_username_id)).perform(setTextInTextView(MOCK_USERNAME))
        Thread.sleep(1500)
        onView(withId(R.id.connect_button_id))
            .perform(closeSoftKeyboard())
            .perform(click())
        onView(withId(R.id.socket_text_value)).check(matches(withText(R.string.disconnected)))

    }

    @Test
    fun d_onCallSelfAndAnswerAndEndToEndTest() {
        assertDoesNotThrow {
            //Wait for call to reset after previous test otherwise will fail in pipeline
            Thread.sleep(7000)
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(MOCK_USERNAME))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(MOCK_PASSWORD))
            Thread.sleep(1500)
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(3000)

            onView(withId(R.id.call_input_id))
                .perform(setTextInTextView(MOCK_CALLER_NUMBER))
            Thread.sleep(2000)

            onView(withId(R.id.call_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(2000)

            onView(withId(R.id.answer_call_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(2000)

            onView(withId(R.id.fragment_call_instance)).check(matches(isDisplayed()))
            Thread.sleep(2000)
        }
    }

    @Test
    fun e_onDisconnect() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(MOCK_USERNAME))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(MOCK_PASSWORD))
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(1500)
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText("Disconnect"))
                .perform(click())
        }
    }

    @Test
    fun f_onSendInviteCanBeCancelled() {
        assertDoesNotThrow {
            Thread.sleep(7000)
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(MOCK_USERNAME))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(MOCK_PASSWORD))
            Thread.sleep(1500)
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(3000)

            onView(withId(R.id.call_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())

            onView(withId(R.id.cancel_call_button_id))
                .check(matches(isDisplayed()))

            onView(withId(R.id.cancel_call_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
        }
    }

    @Test
    fun g_onSendInvite() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(MOCK_USERNAME))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(MOCK_PASSWORD))
            Thread.sleep(1500)
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(2000)
            onView(withId(R.id.call_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(2000)
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