# AI Agent Guide: WebRTC Android SDK Test Execution

**This is the main guide for AI agents. Read this file completely before executing any tests.**

This guide provides detailed descriptions of the app screens and actions needed to execute tests using mobile-mcp tools.

## Important: Autonomous Operation

**You must work autonomously without asking the user for any input or confirmation.**

- All necessary information is in these files:
  - `AI_AGENT_GUIDE.md` - This file with screen descriptions and instructions
  - `TEST_SCENARIOS.md` - Test scenarios described in plain language
  - `test-settings.yaml` - **Test account credentials and settings** (username, password, token, caller name, caller number)
- Do NOT ask the user for device IDs, PINs, credentials, or any other information
- Do NOT ask for confirmation before executing steps
- Do NOT ask for permission to proceed
- Make all decisions based on the configuration files and device state
- Use mobile-mcp tools to discover connected devices automatically
- **When you need test account credentials** (username, password, token, caller name, caller number), read them from `test-settings.yaml`
- Work independently and report results when complete

## App Screen Descriptions

### Start Screen (Connection Screen)

This is the initial screen that appears when the app is launched. It is the main connection interface where users configure and establish a connection to the Telnyx service.

**Visual Elements:**
- **Telnyx Logo** (centered near top): Black logo with stylized 'T' icon and "telnyx" text, with a three-dot menu icon on the right
- **Instructional Text**: "Please confirm details below and click 'Connect' to make a call."
- **Socket Status**: Shows "Disconnected" with a small red circular icon indicating the current connection state
- **Session ID**: Displays "Session ID" followed by "-" (hyphen), indicating no active session
- **Profile Section**: 
  - Label: "Profile"
  - Value: currently selected profile or 'No profile' if any is selected
  - Action button: "Switch profile" (outlined button to the right)
- **Connect Button** (bottom center): Large, dark gray, rectangular button with rounded corners, labeled "Connect" in white text
- **Version Information** (below Connect button): "Production TelnyxSDK ..."

**Key Actions on This Screen:**
- **"Connect"**: Clicking the "Connect" button establishes a connection to the Telnyx service. After clicking, wait for the socket status to change from "Disconnected" (red) to "Connected" or for the appearance of "Client-ready" text/indicator.
- **"Switch profile"**: Allows changing the profile (not typically needed for basic tests)

**What "Connect" means:**
- Clicking "Connect" initiates the connection process to the Telnyx WebRTC service
- The app will attempt to establish a socket connection
- Upon successful connection, the socket status should change and you should see "Client-ready" indicator
- Only after seeing "Client-ready" can you proceed to make calls

**What "Log as user" means:**
- This refers to the connection/login process
- When you click "Connect", the app authenticates using credentials from the configuration
- No separate "login" screen - connection IS the login process
- After successful connection, the user is logged in and ready to make calls

**Identifying This Screen:**
- Look for the Telnyx logo at the top
- Look for the "Connect" button at the bottom
- Look for "Socket: Disconnected" status
- Look for the instruction text: "Please confirm details below and click 'Connect' to make a call."

---

### Existing Profiles Screen

This screen appears when you click the "Switch profile" button on the Start Screen. It allows you to select from existing profiles or add a new profile.

**Visual Elements:**
- **Title**: "Existing profiles" displayed at the top left of the main content area
- **Add Profile Button**: "+ Add new profile" button with rounded rectangular border (typically not needed for basic tests)
- **Profile List**: A vertical list of existing profile names displayed as plain text
- **Action Buttons** (bottom right):
  - **"Cancel"**: Button with white background and rounded border - dismisses the screen without making changes
  - **"Confirm"**: Button with solid black background and white text - confirms the selected profile

**Key Actions on This Screen:**
- **Select Profile**: Click on any profile name from the list
- **Confirm Selection**: After selecting a profile, click "Confirm" to apply the selection and return to the Start Screen
- **Cancel**: Click "Cancel" to dismiss without changing the profile
- **Add New Profile**: Click "+ Add new profile" to create a new profile (typically not needed for tests)

**What "Switch profile" means:**
- Changing the active profile that will be used for connection
- Different profiles may have different credentials or configurations
- After confirming a profile selection, you return to the Start Screen with the new profile displayed
- The selected profile will be used when you click "Connect"

**Identifying This Screen:**
- Look for the "Existing profiles" title at the top
- Look for the list of profile names
- Look for "Cancel" and "Confirm" buttons at the bottom right
- Look for the "+ Add new profile" button

---

### Profile Configuration Screen

This screen appears when you click on a profile name in the Existing Profiles Screen or when adding/editing a profile. It allows you to configure login credentials, caller information, and other profile settings.

**Visual Elements:**
- **Title**: "Existing profiles" displayed at the top left
- **Add New Profile Button**: "+ Add new profile" button with rounded rectangular border
- **Current Profile Display**: Shows the name of the profile being configured (e.g., "production")
- **Login Method Selector**: A segmented control with two options:
  - **"Credential Login"**: Highlighted in green when selected - uses username and password
  - **"Token Login"**: White with black border when not selected - uses token-based authentication
- **Username Input Field**: Labeled "Username" with placeholder text "Enter username"
- **Password Input Field**: Labeled "Password" with placeholder text "Enter password" and an eye icon on the right for toggling password visibility
- **Caller Name Input Field**: Labeled "Caller name" with placeholder text "Enter caller name"
- **Caller Number Input Field**: Labeled "Caller number" with placeholder text "Enter caller number"
- **Force TURN Relay Toggle**: Text "Force TURN Relay" with a toggle switch (off position shows gray background with dark gray circle on the left)
- **Action Buttons** (bottom):
  - **"Cancel"**: Rounded rectangular button with white background and black border - discards changes and returns to previous screen
  - **"Save"**: Rounded rectangular button with black background and white text - saves the configuration and returns to previous screen

**Key Actions on This Screen:**
- **Select Login Method**: Click "Credential Login" or "Token Login" to switch between authentication methods (changes which input fields are displayed)
- **Enter Username**: Fill in the "Username" field using values from `test-settings.yaml` (account username) if "Credential Login" is selected
- **Enter Password**: Fill in the "Password" field using values from `test-settings.yaml` (account password) if "Credential Login" is selected
- **Enter Token**: Fill in the token field using values from `test-settings.yaml` (account token) if "Token Login" is selected
- **Enter Caller Name**: Fill in the "Caller name" field using values from `test-settings.yaml` (account caller_name)
- **Enter Caller Number**: Fill in the "Caller number" field using values from `test-settings.yaml` (account caller_number)
- **Toggle Force TURN Relay**: Click the toggle switch to enable or disable based on test requirements
- **Save Configuration**: Click "Save" to apply all entered settings and return to the previous screen
- **Cancel Changes**: Click "Cancel" to discard any changes and return to the previous screen without saving

**What this screen is used for:**
- Configuring profile credentials and settings before connecting
- Setting up username/password for credential-based login
- Setting up token for token-based login (when "Token Login" is selected)
- Configuring caller identification information (name and number)
- Enabling/disabling TURN relay settings

**Identifying This Screen:**
- Look for the "Existing profiles" title at the top
- Look for the "+ Add new profile" button
- Look for the "Credential Login" and "Token Login" segmented control
- Look for the "Username", "Password", "Caller name", and "Caller number" input fields
- Look for the "Force TURN Relay" toggle
- Look for the "Cancel" and "Save" buttons at the bottom

---

### Idle Screen

This screen appears after successfully connecting to the Telnyx service. It is the main interface for making calls when the client is ready and active.

**Visual Elements:**
- **Telnyx Logo** (centered near top): Black logo with stylized 'T' icon and "telnyx" text, with a three-dot menu icon on the right
- **Instructional Text**: "Enter a destination (phone number or SIP user) to initiate your call."
- **Status Indicators**:
  - **Socket**: Shows a green circle followed by "Client-ready" text
  - **Call State**: Shows a green circle followed by "Active" text
  - **Session ID**: Displays a long alphanumeric string (UUID format)
- **Destination Type Selector**: A segmented control with two tabs:
  - **"SIP address"**: Left tab, filled with dark teal-green color and white text when selected
  - **"Phone number"**: Right tab, white background with black text and thin black border when not selected
- **Input Field**: Rectangular input field with rounded corners, containing placeholder text "Enter SIP address" (when SIP address tab is selected) or "Enter phone number" (when Phone number tab is selected)
- **Call Button**: Large circular green/teal button with phone icon. Centered horizontally, vertically between SIP input and "Call History". **Accessibility label: `"make a call"`** (findable via `list_elements_on_screen`).
- **Call History Button**: Smaller, oval-shaped button with thin black border and black text labeled "Call History"
- **Disconnect Button** (bottom): Wide, dark gray or black rectangular button with rounded corners, labeled "Disconnect" in white text
- **Version Information** (below Disconnect button): "Production TelnyxSDK [v3.3.0] - App [v19]"

**Key Actions on This Screen:**
- **Make a Call**: 
  - Select destination type using the segmented control ("SIP address" or "Phone number")
  - Enter the destination in the input field
  - Click the **Call button** to initiate the call
- **View Call History**: Click the "Call History" button to view previous calls
- **Disconnect**: Click the "Disconnect" button to disconnect from the Telnyx service

**What "Make a call" means:**
- After entering a destination (SIP address or phone number), clicking the Call button initiates a call to that destination
- The app will attempt to establish a call connection
- The call state will change to reflect the call status (ringing, connected, etc.)

**Identifying This Screen:**
- Look for "Client-ready" status with green circle
- Look for "Active" call state with green circle
- Look for the instructional text: "Enter a destination (phone number or SIP user) to initiate your call."
- Look for the **large circular Call button with bright teal-green background and black phone icon**
- Look for the "SIP address" / "Phone number" segmented control
- Look for the "Disconnect" button at the bottom

---

### Incoming Screen

This screen appears when an incoming call is received. It displays call action buttons to answer or reject the incoming call.

**Visual Elements:**
- **Telnyx Logo** (centered near top): Black logo with stylized 'T' icon and "telnyx" text, with a three-dot menu icon on the right
- **Instructional Text**: "Enter a destination (phone number or SIP user) to initiate your call."
- **Status Indicators**:
  - **Socket**: Shows a green circle followed by "Client-ready" text
  - **Call State**: Shows a dark gray circle followed by "New" text (indicating new incoming call)
  - **Session ID**: Displays a long alphanumeric string (UUID format)
- **Call Action Buttons** (center of screen): Two large circular buttons side by side:
  - **Answer Button**: **Large circular button with teal-green/green background** and a **white phone receiver icon pointing upwards**. This is the button to **answer/accept the incoming call**. Positioned on the right side. **Accessibility label: `"answer call"`** (findable via `list_elements_on_screen`).
  - **Reject Button**: **Large circular button with red background** and a **white phone receiver icon pointing downwards** (hang up icon). This is the button to **reject/decline the incoming call**. Positioned on the left side. **Accessibility label: `"reject call"`** (findable via `list_elements_on_screen`).
- **Disconnect Button** (bottom): Wide, dark gray or black rectangular button with rounded corners, labeled "Disconnect" in white text
- **Version Information** (below Disconnect button): "Production TelnyxSDK [v3.3.0] - App [v19]"

**Key Actions on This Screen:**
- **Answer Call**: Click the **green circular button** (right side) with white phone icon pointing upwards to answer/accept the incoming call
- **Reject Call**: Click the **red circular button** (left side) with white hang up icon pointing downwards to reject/decline the incoming call
- **Disconnect**: Click the "Disconnect" button to disconnect from the Telnyx service (typically not needed during an incoming call)

**What "Answer call" means:**
- Clicking the green circular button accepts the incoming call
- The call will be connected and you'll be able to communicate with the caller
- The screen will transition to show the active call state

**What "Reject call" means:**
- Clicking the red circular button rejects/declines the incoming call
- The call will be terminated and the caller will receive a busy or rejected signal
- The screen will return to the Idle Screen

**Identifying This Screen:**
- Look for "Client-ready" status with green circle
- Look for "New" call state with dark gray circle (indicating incoming call)
- Look for the **two large circular buttons in the center**:
  - **Red circular button on the left** (reject call)
  - **Green/teal circular button on the right** (answer call)
- Look for the instructional text: "Enter a destination (phone number or SIP user) to initiate your call."
- Look for the "Disconnect" button at the bottom

---

### Active Screen (During Call)

This screen appears when a call is active and connected. It provides call control options and displays call status information.

**Visual Elements:**
- **Telnyx Logo** (centered near top): Black logo with stylized 'T' icon and "telnyx" text
- **Microphone Button** (top right): Small green circular button with microphone icon - toggles mute/unmute
- **Menu Icon** (top right): Three vertical dots indicating menu options
- **Instructional Text**: "Enter a destination (phone number or SIP user) to initiate your call."
- **Status Indicators**:
  - **Socket**: Shows a green circle followed by "Client-ready" text
  - **Call State**: Shows a green circle followed by "Active" text (indicating active call)
  - **Session ID**: Displays a long alphanumeric string (UUID format)
- **Input Field**: May show the destination that was called (e.g., "radek2")
- **Call Quality Indicator**: Shows "Call quality: Unknown" with a grey dot, and a "View all call metrics" button
- **Call Control Buttons** (arranged in a row): Four circular, light beige buttons with black icons:
  - **Microphone icon**: Mute/unmute button
  - **Crossed-out speaker icon**: Speaker mute button
  - **Two vertical lines icon**: Pause/hold button
  - **Nine-dot grid icon**: Dialpad button
- **End Call Button**: **Large, prominent red circular button** with a **white phone receiver icon** (hang up icon). This is the primary button to **end/terminate the active call**. Positioned at the bottom center of the screen, it is the most prominent call control element. **Accessibility label: `"end call"`** (findable via `list_elements_on_screen`).

**Key Actions on This Screen:**
- **End Call**: Click the **large red circular button** with white phone receiver icon to end/terminate the active call
- **Mute/Unmute**: Click the microphone icon button (top right green button or in the call control row) to mute or unmute your microphone
- **Speaker Mute**: Click the crossed-out speaker icon to mute/unmute speaker audio
- **Hold/Pause**: Click the two vertical lines icon to pause or hold the call
- **Dialpad**: Click the nine-dot grid icon to open the dialpad
- **View Call Metrics**: Click "View all call metrics" to see detailed call quality information

**What "End call" means:**
- Clicking the large red circular button terminates the active call
- The call connection will be closed
- The screen will return to the Idle Screen
- Both parties will be disconnected

**Identifying This Screen:**
- Look for "Active" call state with green circle (indicating active call)
- Look for "Client-ready" status with green circle
- Look for the **large red circular button at the bottom center** with white phone receiver icon (end call button)
- Look for the row of call control buttons (microphone, speaker, hold, dialpad)
- Look for the green microphone button at the top right
- Look for call quality indicator showing "Unknown" or other quality status

---

## How to Perform Actions

### Account Creation

To create an account (configure a profile), follow these steps:

1. **Navigate to Profile Configuration Screen**: 
   - From the Start Screen, click "Switch profile"
   - On the Existing Profiles Screen, click "+ Add new profile" or click on an existing profile to edit it
   - You should now be on the Profile Configuration Screen

2. **Fill in the form fields using data from `test-settings.yaml`**:
   - **Select Login Method**: 
     - If the account uses credential login (`login_method: "credential"`), ensure "Credential Login" is selected
     - If the account uses token login (`login_method: "token"`), click "Token Login" to select it
   
   - **For Credential Login accounts**:
     - **Username field**: 
       - First, verify the focus is on the "Username" field (check that the field is highlighted or the cursor is in it)
       - If not focused, click on the "Username" field to set focus
       - Enter the username from `test-settings.yaml` (e.g., `test_accounts.account_1.username`)
     - **Password field**:
       - Click on the "Password" field to set focus
       - Verify focus is on the Password field before typing
       - Enter the password from `test-settings.yaml` (e.g., `test_accounts.account_1.password`)
   
   - **For Token Login accounts**:
     - **Token field**:
       - Click on the token input field to set focus
       - Verify focus is on the token field before typing
       - Enter the token from `test-settings.yaml` (e.g., `test_accounts.account_token.token`)
   
   - **Caller Name field**:
     - Click on the "Caller name" field to set focus
     - Verify focus is on the Caller name field before typing
     - Enter the caller name from `test-settings.yaml` (e.g., `test_accounts.account_1.caller_name`)
   
   - **Caller Number field**:
     - Click on the "Caller number" field to set focus
     - Verify focus is on the Caller number field before typing
     - Enter the caller number from `test-settings.yaml` (e.g., `test_accounts.account_1.caller_number`)

3. **Important**: Before editing each field, always check if the focus is in the proper field. Use mobile-mcp tools to verify the focused element before entering text.

4. **Save the configuration**:
   - After all fields are filled correctly, click the "Save" button
   - Wait for the screen to return to the previous screen (Existing Profiles Screen or Start Screen)
   - Verify that the profile has been saved successfully

**Key Points**:
- Always verify field focus before entering text to avoid typing in the wrong field
- Use mobile-mcp tools to check which field currently has focus
- Read all credential values from `test-settings.yaml` - do not ask the user for any information
- Wait for the UI to update after each action before proceeding to the next step

---

## Files You Need to Read

When executing tests, you need to read these files:

1. **`AI_AGENT_GUIDE.md`** (this file):
   - Screen descriptions and how to identify them
   - Actions available on each screen
   - How to interact with UI elements

2. **`TEST_SCENARIOS.md`**:
   - Test scenarios described in plain language
   - What steps to execute for each test
   - Expected outcomes

3. **`test-settings.yaml`** (IMPORTANT for credentials):
   - Test account credentials and settings
   - For Credential Login accounts: `username`, `password`, `caller_name`, `caller_number`
   - For Token Login accounts: `token`, `caller_name`, `caller_number`
   - Multiple test accounts (account_1, account_2, etc.)
   - Default account to use
   - Profile configuration settings

**When you need credentials** (username, password, token, caller name, caller number) for filling in forms or configuring profiles, read them from `test-settings.yaml` under the appropriate account (e.g., `test_accounts.account_1.username`).

---

## Button Labels for list_elements_on_screen

The following buttons have accessibility labels (content descriptions) that can be found using the `list_elements_on_screen` mobile-mcp command. AI agents should search for these labels to reliably locate and click buttons.

### Idle Screen Buttons
| Button | Label | Element Type |
|--------|-------|--------------|
| Call Button (green) | `"make a call"` | `android.view.View` |

### Incoming Screen Buttons
| Button | Label | Element Type |
|--------|-------|--------------|
| Answer Button (green) | `"answer call"` | `android.view.View` |
| Reject Button (red) | `"reject call"` | `android.view.View` |

### Active Screen Buttons
| Button | Label | Element Type |
|--------|-------|--------------|
| End Call Button (red) | `"end call"` | `android.view.View` |

### How to Use These Labels

1. Call `list_elements_on_screen` to get all elements
2. Search for the element with matching `label` field
3. Calculate center coordinates: `x + width/2`, `y + height/2`
4. Click at the center coordinates

**Example:**
```
Element from list_elements_on_screen:
{"type":"android.view.View", "text":"", "label":"make a call", "coordinates":{"x":510,"y":1654,"width":63,"height":63}}

Center coordinates: x = 510 + 31 = 541, y = 1654 + 31 = 1685
Click at: (541, 1685)
```

---

## Tips for AI Agents

1. **Work autonomously**: Never ask the user for input - use configuration files and device discovery
2. **Be descriptive**: Explain what you're doing at each step
3. **Wait appropriately**: Don't rush - give UI time to update
4. **Verify states**: Check screen state before actions (e.g., don't try to unlock if already unlocked)
5. **Handle edge cases**: Device might be in unexpected state - handle it automatically
6. **Use mobile-mcp tools**: Leverage mobile-mcp's reliable device interaction capabilities
7. **Identify screens using list_elements_on_screen**: Use the mobile-mcp `list_elements_on_screen` command to get all UI elements currently visible on the screen, then compare the results with the screen descriptions in this guide to verify you're on the correct screen before taking actions
8. **Make decisions**: If something is unclear, make the best decision based on available information rather than asking
9. **Identify screens correctly**: Use the screen descriptions above and compare with `list_elements_on_screen` results to verify you're on the correct screen before taking actions
10. **Dismiss keyboard**: If the mobile keyboard is blocking UI elements, check if it's visible by taking a screenshot using `mobile_take_screenshot`. Visually inspect the screenshot to see if the full keyboard is displayed at the bottom of the screen. If the keyboard is visible, dismiss it using: `adb -s <device_id> shell input keyevent 4` (sends BACK key press). **Note**: The BACK key will navigate back if the keyboard is already closed, so always verify keyboard visibility via screenshot first before dismissing. Do NOT use ADB commands like `dumpsys input_method` to check keyboard state as they may report incorrect results.

---

## Test Results Reporting

When reporting test execution results, follow this format:

### Format

For each test scenario executed, report:
- **Test name**: The name of the test from `TEST_SCENARIOS.md`
- **Result**: `PASS` or `FAIL`
- **Failure details** (only if result is `FAIL`): Very short description of which step failed and why

### Examples

**Example 1 - Passed test:**
```
Login Test: PASS
```

**Example 2 - Failed test:**
```
Make outbound call to SIP Connection: FAIL
- Failed at step 5: Could not find Call button on Idle Screen. Screen showed "Disconnected" status instead of "Client-ready".
```

**Example 3 - Multiple tests:**
```
Login Test: PASS

Make outbound call to SIP Connection: FAIL
- Failed at step 6: Answer button not found on Incoming Screen. Call state showed "Active" instead of "New".
```

### Guidelines

- **Keep it concise**: Only include failure details when a test fails
- **Be specific**: Mention the step number and what went wrong
- **Include context**: If relevant, mention what screen/state was observed vs what was expected
- **No verbose output for passed tests**: Just show the test name and "PASS"

### Complete Report Format

```
=== Test Execution Results ===

Test 1: [Test Name]
Result: PASS

Test 2: [Test Name]
Result: FAIL
- Failed at step [X]: [Brief description of what went wrong]

Test 3: [Test Name]
Result: PASS

=== Summary ===
Total: 3
Passed: 2
Failed: 1
```

---

*More screen descriptions will be added as needed. This guide will be expanded with additional screens and actions.*
