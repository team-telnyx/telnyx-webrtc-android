package org.telnyx.webrtc.compose_app

import android.annotation.SuppressLint
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.telnyx.webrtc.common.TelnyxViewModel
import junit.framework.TestCase.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.telnyx.webrtc.compose_app.ui.screens.HomeScreen
import org.junit.runner.RunWith
import org.telnyx.webrtc.compose_app.ui.screens.CallScreenNav
import org.telnyx.webrtc.compose_app.ui.screens.LoginScreenNav

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()
    lateinit var navController: TestNavHostController

    @SuppressLint("ContextCastToActivity")
    @Before
    fun setupAppNavHost() {
        val fakeTelnyxViewModel = TelnyxViewModel()
        composeTestRule.setContent {
            navController = TestNavHostController(LocalContext.current).apply {
                setViewModelStore(composeTestRule.activity.viewModelStore)
                navigatorProvider.addNavigator(ComposeNavigator())
                setGraph(createTestNavGraph(this), null)
            }

            HomeScreen(navController = navController, fakeTelnyxViewModel)
        }
    }

    @Test
    fun connectAndMakeCallTest() {
        addSipCredentials()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.connect)).performClick()

        composeTestRule.waitUntil(15000) {
            composeTestRule.onNodeWithText(context.getString(R.string.disconnect)).isDisplayed()
        }

        composeTestRule.onNodeWithTag("callInput").assertIsDisplayed()
        composeTestRule.onNodeWithTag("callInput").performTextInput(BuildConfig.TEST_SIP_DEST_NUMBER)
        composeTestRule.onNodeWithTag("callInput").performImeAction()

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("call").performScrollTo().assertIsDisplayed().performClick()

        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(40000) {
            composeTestRule.onNodeWithTag("callActiveView").isDisplayed()
        }

        composeTestRule.onNodeWithTag("mute").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("mute").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("loudSpeaker").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("loudSpeaker").performScrollTo().performClick()


        composeTestRule.onNodeWithTag("endCall").performScrollTo().performClick()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.disconnect)).performClick()

    }

    private fun addSipCredentials() {
        assertNotNull(navController.graph)

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.missing_profile)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.switch_profile)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.switch_profile)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.add_new_profile)).assertIsDisplayed()

        composeTestRule.onNodeWithText(context.getString(R.string.add_new_profile)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.credential_login)).assertIsDisplayed()

        composeTestRule.onNodeWithTag("sipUsername").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sipUsername").performTextInput(BuildConfig.TEST_SIP_USERNAME)
        composeTestRule.onNodeWithTag("sipPassword").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sipPassword").performTextInput(BuildConfig.TEST_SIP_PASSWORD)
        composeTestRule.onNodeWithTag("callerIDName").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("callerIDName").performTextInput(BuildConfig.TEST_SIP_CALLER_NAME)
        composeTestRule.onNodeWithTag("callerIDNumber").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("callerIDNumber").performTextInput(BuildConfig.TEST_SIP_CALLER_NUMBER)
        composeTestRule.onNodeWithTag("callerIDNumber").performImeAction()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("credentialsForm").performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.save)).performScrollTo().performClick()

        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(40000) {
            composeTestRule.onNodeWithTag("profileList").isDisplayed()
        }

        composeTestRule.onNodeWithTag("profileList").onChildAt(0).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.confirm)).performClick()
    }

    private fun createTestNavGraph(navController: NavHostController): NavGraph {
        return navController.createGraph(startDestination = LoginScreenNav) {
            composable<LoginScreenNav> { }
            composable<CallScreenNav> { }
        }.apply {
            id = View.generateViewId()
        }
    }
}
