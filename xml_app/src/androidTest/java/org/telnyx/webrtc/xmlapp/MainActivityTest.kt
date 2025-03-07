package org.telnyx.webrtc.xmlapp

import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.telnyx.webrtc.xml_app.MainActivity

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val idlingResource = ElapsedTimeIdlingResource(10000)

    @Test
    fun addSIPCredentialsAndConnectTest() {
        onView(withId(R.id.usernameTextField))
            .check(doesNotExist())

        onView(withId(R.id.switchProfile))
            .perform(click())

        onView(withId(R.id.addNewProfile))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.usernameTextField))
            .check(matches(isDisplayed()))
            .perform(typeText(BuildConfig.TEST_SIP_USERNAME))

        onView(withId(R.id.passwordTextField))
            .check(matches(isDisplayed()))
            .perform(typeText(BuildConfig.TEST_SIP_PASSWORD))

        onView(withId(R.id.callerIdNameTextField))
            .check(matches(isDisplayed()))
            .perform(typeText(BuildConfig.TEST_SIP_CALLER_NAME))

        onView(withId(R.id.callerIdNumberTextField))
            .check(matches(isDisplayed()))
            .perform(typeText(BuildConfig.TEST_SIP_CALLER_NUMBER), closeSoftKeyboard())

        onView(withId(R.id.confirmButton))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.allProfiles))
            .perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))

        onView(withId(R.id.connect))
            .check(matches(isDisplayed()))
            .perform(click())

        IdlingRegistry.getInstance().register(idlingResource)

        onView(withId(R.id.disconnect))
            .check(matches(isDisplayed()))
            .perform(click())

        IdlingRegistry.getInstance().unregister(idlingResource)

    }

    @Test
    fun makeCallTest() {

        onView(withId(R.id.switchProfile))
            .perform(click())

        onView(withId(R.id.allProfiles))
            .perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))

        onView(withId(R.id.connect))
            .check(matches(isDisplayed()))
            .perform(click())

        IdlingRegistry.getInstance().register(idlingResource)

        onView(withId(R.id.callInput))
            .check(matches(isDisplayed()))
            .perform(clearText(), typeText(BuildConfig.TEST_SIP_DEST_NUMBER), closeSoftKeyboard())

        IdlingRegistry.getInstance().unregister(idlingResource)

        onView(withId(R.id.call))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.callActiveView))
            .perform(waitUntilVisible(30000))

        onView(withId(R.id.mute))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.mute))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.loudSpeaker))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.loudSpeaker))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.hold))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.hold))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.dialpad))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.dialpadOutput))
            .perform(waitUntilVisible(30000))

        onView(withId(R.id.close))
            .perform(click())

        onView(withId(R.id.endCall))
            .check(matches(isDisplayed()))
            .perform(click())

        onView(withId(R.id.disconnect))
            .perform(click())
    }
}
