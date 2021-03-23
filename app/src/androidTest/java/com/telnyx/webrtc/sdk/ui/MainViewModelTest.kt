package com.telnyx.webrtc.sdk.ui

import android.content.Context
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.testhelpers.BaseUITest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class MainViewModelTest : BaseUITest() {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    lateinit var userManager: UserManager

    /*private fun createInstance() = spyk(
        MainViewModel(
            userManager = userManager
        )
    )*/

    private fun createInstance() =
        MainViewModel(
            userManager = userManager
        )


    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun initConnectionWithoutExceptionThrown() {
        val viewModel = createInstance()
        assertDoesNotThrow {
            viewModel.initConnection(mockContext)
        }
    }
}