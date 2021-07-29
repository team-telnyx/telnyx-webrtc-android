#!/bin/bash
while getopts u:p:t: flag
do
    case "${flag}" in
        u) user=${OPTARG};;
        p) password=${OPTARG};;
    esac
done
echo "User: $user";
echo "password: $password";

sed -i '' 's/<SIP_USER>/'"$user"'/g' app/src/androidTest/java/com/telnyx/webrtc/sdk/testhelpers/TestConstants.kt
sed -i '' 's/<SIP_PASSWORD>/'"$password"'/g' app/src/androidTest/java/com/telnyx/webrtc/sdk/testhelpers/TestConstants.kt

sed -i '' 's/<SIP_USER>/'"$user"'/g' app/src/main/java/com/telnyx/webrtc/sdk/AppConstants.kt
sed -i '' 's/<SIP_PASSWORD>/'"$password"'/g' app/src/main/java/com/telnyx/webrtc/sdk/AppConstants.kt

sed -i '' 's/<SIP_USER>/'"$user"'/g' telnyx_rtc/src/test/java/com/telnyx/webrtc/sdk/testhelpers/TestConstants.kt
sed -i '' 's/<SIP_PASSWORD>/'"$password"'/g' telnyx_rtc/src/test/java/com/telnyx/webrtc/sdk/testhelpers/TestConstants.kt

