# Twilio Voice Android + FCM – Setup Notes and Troubleshooting

This document captures what was configured, code changes made, and the main issues encountered while enabling incoming and outgoing calls using Twilio Voice SDK with Firebase Cloud Messaging (FCM).

## What we set up

- Firebase project and `google-services.json` added to Android app
- Twilio Serverless project deployed in `Server/`
  - Functions: `access-token`, `incoming`, `place-call`, `make-call`, `hello-world`
  - Assets: `index.html`, `test-call.html`
- TwiML Application created and linked to `incoming` function URL
- Push Credential (FCM HTTP v1) created in Twilio Console and wired into server tokens
- Android app updated to fetch access tokens from server dynamically and register for FCM

## Versions pinned (compatibility)

- Gradle Wrapper: 7.6.1 (`gradle/wrapper/gradle-wrapper.properties`)
- Android Gradle Plugin: 7.4.2 (`build.gradle` project-level)
- Java: 11 (`build.gradle` project-level)
- Twilio Voice SDK: `com.twilio:voice-android:6.5.+`
- Firebase BoM: `31.2.0`

## Android changes (key files)

- `app/src/main/java/com/twilio/voice/quickstart/VoiceApplication.java`
  - Refactored to not depend on a hardcoded token at startup
  - New `updateAccessToken(String)` method binds `VoiceService` once token is available

- `app/src/standard/.../VoiceActivity.java` and `app/src/connection_service/.../VoiceActivity.java`
  - Removed hardcoded access token
  - Added OkHttp token fetch from `https://<service>.twil.io/access-token`
  - After fetch, call `VoiceApplication.updateAccessToken(...)`
  - Then fetch FCM token and call `VoiceService.registerFCMToken(...)`
  - Added logs across FCM/register/incoming call flow (search for "🔥")
  - On app start, prompt user to enter Caller ID (identity); token is fetched with `?identity=<value>`

- `app/src/main/java/com/twilio/voice/quickstart/IncomingCallService.java`
  - Added detailed logs for FCM message receipt and call invite handling

- `app/src/main/java/com/twilio/voice/quickstart/VoiceService.java`
  - Added logs in registration and callbacks to aid debugging

## Serverless (Twilio Functions) changes

- `Server/functions/access-token.js`
  - Uses `context.APP_SID` and `context.PUSH_CREDENTIAL_SID`
  - Accepts optional query param `identity` (defaults to `alice`)
  - Returns a JWT including Voice grant and push credential SID

- `Server/functions/incoming.js`
  - Dials the client when `event.To` starts with `client:`
  - Added logging for payloads

- `Server/functions/place-call.js`
  - `from` (callerId) set to `client:bob` when calling `client:alice` (client-to-client test)

- `Server/assets/test-call.html`
  - Helper page to trigger API/Functions calls: direct call, via `place-call`, and to check token

## Environment (.env in Server/)

Set in `Server/.env` (kept locally):

- `ACCOUNT_SID=AC...`
- `AUTH_TOKEN=...`
- `API_KEY_SID=SK...`
- `API_SECRET=...`
- `APP_SID=AP...` (TwiML App SID)
- `PUSH_CREDENTIAL_SID=CR...` (FCM v1 credential)

After changes, redeploy serverless:

```
cd Server
twilio serverless:deploy
```

## Typical flows

1) App start
- Fetch access token from `/access-token?identity=<callerId>`
- Update `VoiceApplication` with token
- Get FCM token and call `Voice.register(accessToken, FCM, fcmToken)`

2) Incoming call (client→client)
- Trigger via `test-call.html` or `place-call` to `client:alice`
- Twilio hits `/incoming` → returns `Dial <Client>alice`
- Twilio sends push via FCM to the device registered for `alice`
- App receives push → `IncomingCallService` → `VoiceService` shows incoming UI

## Common issues and fixes

- 52109 – GCM/FCM unauthorized
  - Cause: Wrong or legacy FCM credential, malformed private key, wrong project, or missing permission
  - Fix:
    - Create FCM HTTP v1 credential in Twilio Console
    - Paste private key as multi-line block (no `\n`), BEGIN/END lines included
    - Enable "Firebase Cloud Messaging API" in Google Cloud
    - Ensure service account has `fcm.messages.create`
    - Update `.env` `PUSH_CREDENTIAL_SID` and redeploy

- 52112 – FCM related failure
  - Often API not enabled or permission missing; same checklist as 52109

- TwiML App URL pointed to wrong function
  - Ensure TwiML Application Voice URL → `https://<service>.twil.io/incoming`

- Identity mismatch (`'user'` vs `'alice'`)
  - Ensure server token uses the same identity as the app; we now pass `?identity=...`

- Java/AGP/Gradle incompatibilities
  - Voice SDK 6.9 requires Java 17; we pinned Voice SDK 6.5.+ and kept Java 11 with AGP 7.4.2 / Gradle 7.6.1

- Firebase BoM dexing errors
  - Pin BoM to `31.2.0`

- Extra module compile failure
  - Excluded `exampleCustomAudioDevice` from `settings.gradle`

## How to run (Android)

1. Open project in Android Studio
2. Select build flavor (standard or connection_service)
3. Run on device/emulator
4. On first launch, enter Caller ID (e.g., `alice`)
5. Watch Logcat for:
   - `🔥 [FCM] ...`
   - `🔥 [VOICE_SERVICE] *** FCM REGISTRATION SUCCESSFUL! ***`
   - Incoming call logs upon triggering a call

## How to test incoming

- Web: open `https://<service>.twil.io/test-call.html`
  - Test Direct Call to `client:alice`
  - Or call via `/place-call` with from=`client:bob`

- cURL example:
```
curl -X POST \
  https://api.twilio.com/2010-04-01/Accounts/AC.../Calls.json \
  --data-urlencode To=client:alice \
  --data-urlencode From=client:bob \
  --data-urlencode Url=https://<service>.twil.io/incoming \
  -u AC...:AUTH_TOKEN
```

## Quick troubleshooting checklist

- App log shows “FCM REGISTRATION SUCCESSFUL”
- TwiML App Voice URL → `/incoming`
- `access-token` includes the expected `identity` and `push_credential_sid`
- Google Cloud: FCM API enabled, service account has `fcm.messages.create`
- Push Credential in Twilio Console uses multi-line private key (HTTP v1)

## Notes

- Outgoing calls from app work regardless of push, but callee needs push for incoming
- Trial accounts: PSTN “To” numbers must be verified; otherwise, buy a Twilio number

