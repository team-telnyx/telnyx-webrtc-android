package com.telnyx.webrtc.sdk.ui

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings.Global.*
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
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.rule.ActivityTestRule
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.testhelpers.BaseUITest
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

    /**
     * Clears everything in the SharedPreferences
     */
    private fun clearSharedPrefs() {
        val sharedPreferences: SharedPreferences = getInstrumentation().targetContext
            .getSharedPreferences("TelnyxSharedPreferences", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.clear()
        editor.commit()
    }

    @Before
    fun setUp() {
        context = getInstrumentation().targetContext.applicationContext
        clearSharedPrefs()
        Intents.init()
        setAnimations(false)
    }

    @After
    fun tearDown() {
        Intents.release()
        clearSharedPrefs()
        setAnimations(true)
    }

    private fun setAnimations(enabled: Boolean) {
        val value = if (enabled) "1.0" else "0.0"
        InstrumentationRegistry.getInstrumentation().uiAutomation.run {
            this.executeShellCommand("settings put global $WINDOW_ANIMATION_SCALE $value")
            this.executeShellCommand("settings put global $TRANSITION_ANIMATION_SCALE $value")
            this.executeShellCommand("settings put global $ANIMATOR_DURATION_SCALE $value")
        }
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
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_username)))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_password)))
            Thread.sleep(1500)
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(6500)
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
        onView(withId(R.id.sip_username_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_username)))
        Thread.sleep(1500)
        onView(withId(R.id.connect_button_id))
            .perform(closeSoftKeyboard())
            .perform(click())
        onView(withId(R.id.socket_text_value)).check(matches(withText(R.string.disconnected)))

    }

   /* @Test
    fun d_onCallSelfAndAnswerAndEndToEndTest() {
        assertDoesNotThrow {
            //Wait for call to reset after previous test otherwise will fail in pipeline
            Thread.sleep(7000)
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_username)))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_password)))
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
                .perform(scrollTo())
                .perform(click())
            Thread.sleep(2000)

            onView(withId(R.id.fragment_call_instance)).check(matches(isDisplayed()))
            Thread.sleep(2000)
        }
    }*/

    @Test
    fun e_onDisconnect() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_username)))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_password)))
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(6500)
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText("Disconnect"))
                .perform(click())
        }
    }

    /*@Test
    fun f_onSendInviteCanBeCancelled() {
        assertDoesNotThrow {
            Thread.sleep(7000)
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_username)))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_password)))
            Thread.sleep(6500)
            onView(withId(R.id.connect_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(2000)

            onView(withId(R.id.call_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())

            onView(withId(R.id.cancel_call_button_id))
                .check(matches(isDisplayed()))

            onView(withId(R.id.cancel_call_button_id))
                .perform(closeSoftKeyboard())
                .perform(click())
            Thread.sleep(2000)
        }
    }*/

    @Test
    fun g_onSendInvite() {
        assertDoesNotThrow {
            activityRule.launchActivity(null)
            onView(withId(R.id.sip_username_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_username)))
            Thread.sleep(1500)
            onView(withId(R.id.sip_password_id)).perform(setTextInTextView(context.resources.getString(R.string.mock_password)))
            Thread.sleep(6500)
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