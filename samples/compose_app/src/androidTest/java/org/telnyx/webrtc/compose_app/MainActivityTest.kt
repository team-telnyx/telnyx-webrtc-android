package org.telnyx.webrtc.compose_app

import android.annotation.SuppressLint
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
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
import com.telnyx.webrtc.common.TelnyxViewModel
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.telnyx.webrtc.compose_app.ui.screens.HomeScreen
import org.telnyx.webrtc.compose_app.ui.screens.CallScreenNav
import org.telnyx.webrtc.compose_app.ui.screens.LoginScreenNav

class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()
    lateinit var navController: TestNavHostController

    @SuppressLint("ContextCastToActivity")
    @Before
    fun setupAppNavHost() {
        val fakeTelnyxViewModel = TelnyxViewModel()
        composeTestRule.setContent {
            val context = LocalContext.current
            navController = remember(context) {
                TestNavHostController(context).apply {
                    setViewModelStore(composeTestRule.activity.viewModelStore)
                    navigatorProvider.addNavigator(ComposeNavigator())
                    setGraph(createTestNavGraph(this), null)
                }
            }

            HomeScreen(navController = navController, fakeTelnyxViewModel)
        }

        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule
                .onAllNodesWithTag("homeScreenRoot")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun connectAndMakeCallTest() {
        addSipCredentials()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("connectDisconnectButton").performClick()

        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(15000) {
            composeTestRule.onNodeWithTag("connectDisconnectButton").isDisplayed()
        }

        composeTestRule.waitUntil(15000) {
            composeTestRule.onNodeWithTag("callInput").isDisplayed()
        }

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
        composeTestRule.onNodeWithTag("connectDisconnectButton").performClick()

    }

    private fun addSipCredentials() {
        assertNotNull(navController.graph)

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("profileName").assertIsDisplayed()
        composeTestRule.onNodeWithTag("switchProfileButton").assertIsDisplayed()
        composeTestRule.onNodeWithTag("switchProfileButton").performClick()

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("addNewProfileButton").assertIsDisplayed()
        composeTestRule.onNodeWithTag("addNewProfileButton").performClick()

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("credentialLoginToggleButton").assertIsDisplayed()

        composeTestRule.onNodeWithTag("sipUsername").performTextInput(BuildConfig.TEST_SIP_USERNAME)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sipPassword").performTextInput(BuildConfig.TEST_SIP_PASSWORD)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("callerIDName").performTextInput(BuildConfig.TEST_SIP_CALLER_NAME)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("callerIDNumber").performTextInput(BuildConfig.TEST_SIP_CALLER_NUMBER)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("callerIDNumber").performImeAction()

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("saveButton").performScrollTo().performClick()

        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(40000) {
            composeTestRule.onNodeWithTag("profileList").isDisplayed()
        }

        composeTestRule.onNodeWithTag("profileList").onChildAt(0).performClick()
        composeTestRule.onNodeWithTag("positiveButton").performClick()
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
