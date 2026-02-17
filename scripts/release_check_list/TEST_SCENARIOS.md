# Test Scenarios

This file describes test scenarios in plain language. The AI agent should read this file to understand what tests to execute and how to execute them.

# Logged in with Token Tests

## Token Login Test
**Description**: Start the app and login as account_3 user. If profile doesn't exists, add it.

## Make call to SIP Connection
**Description**: Log as account_3 and account_1. Make outbound call from account_3 to account_1 using SIP user's name.
**Passed**: When account_3 was able to make a call, account_1 was able to answer the call and end it after 5 seconds. After the call both sides are in IDLE state.

## Make call to associated number
**Description**: Log as account_3 and account_1. Make outbound call from account_3 to account_1 using associated number.
**Passed**: When account_3 was able to make a call, account_1 was able to answer the call and end it after 5 seconds. After the call both sides are in IDLE state.

## Make call to PSTN number
**Description**: Log as account_3. Make outbound call from account_3 to PSTN number.
**Passed**: When account_3 was able to make a call, call was established, then end the call by account_3. After the call account_3 is in IDLE state.

# Logged in with SIP Connection

## SIP Login Test
**Description**: Start the app and login as account_2 user. If profile doesn't exists, add it.

## Make call to SIP Connection
**Description**: Log as account_1 and account_2. Make outbound call from account_1 to account_2 using SIP user's name.
**Passed**: When account_1 was able to make a call, account_2 was able to answer the call and end it after 5 seconds. After the call both sides are in IDLE state.

## Make call to associated number
**Description**: Log as account_1 and account_2. Make outbound call from account_2 to account_1 using associated number.
**Passed**: When account_2 was able to make a call, account_1 was able to answer the call and end it after 5 seconds. After the call both sides are in IDLE state.

## Make call to PSTN number
**Description**: Log as account_2. Make outbound call from account_2 to PSTN number.
**Passed**: When account_2 was able to make a call, call was established, then end the call by account_2. After the call account_2 is in IDLE state.

# Logged in with genCred

## genCred Login Test
**Description**: Start the app and login as account_4 user. If profile doesn't exists, add it.

## Receive call from SIP Connection
**Description**: Log as account_1 and account_4. Make outbound call from account_1 to account_4 using account_4's username.
**Passed**: When account_1 was able to make a call, account_4 was able to answer the call and end it after 5 seconds. After the call both sides are in IDLE state.

## Make call to SIP Connection
**Description**: Log as account_1 and account_4. Make outbound call from account_4 to account_1 using SIP user's name.
**Passed**: When account_4 was able to make a call, account_1 was able to answer the call and end it after 5 seconds. After the call both sides are in IDLE state.

## Make call to associated number
**Description**: Log as account_1 and account_4. Make outbound call from account_4 to account_1 using associated number.
**Passed**: When account_4 was able to make a call, account_1 was able to answer the call and end it after 5 seconds. After the call both sides are in IDLE state.

## Make call to PSTN number
**Description**: Log as account_4. Make outbound call from account_4 to PSTN number.
**Passed**: When account_4 was able to make a call, call was established, then end the call by account_4. After the call account_2 is in IDLE state.
