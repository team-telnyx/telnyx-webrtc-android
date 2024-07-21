import com.telnyx.webrtc.sdk.MOCK_PASSWORD
import com.telnyx.webrtc.sdk.MOCK_USERNAME
import kotlin.test.Test
import kotlin.test.assertEquals

class CredentialTest {

    @Test
    fun testCredential() {
        assertEquals(MOCK_USERNAME, "<SIP_USER>")
        assertEquals(MOCK_PASSWORD, "<SIP_PASSWORD>")
    }

}