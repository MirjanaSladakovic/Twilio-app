# Twilio Voice Android + FCM – Setup Notes and Troubleshooting

This document captures what was configured, code changes made, and the main issues encountered while enabling incoming and outgoing calls using Twilio Voice SDK with Firebase Cloud Messaging (FCM).

## What I set up

- Firebase project and `google-services.json` added to Android app
- Twilio Serverless project deployed in `Server/`
- TwiML Application created and linked to `incoming` function URL
- Push Credential (FCM HTTP v1) created in Twilio Console and wired into server tokens
- Android app updated to fetch access tokens from server dynamically and register for FCM
- Android app updated to enable user entering his identity on the first app use and that identity beeing used to fetch token

## Environment (.env in Server/)

Set in `Server/.env` (kept locally):

- `ACCOUNT_SID=AC...`
- `AUTH_TOKEN=...`
- `API_KEY_SID=SK...`
- `API_SECRET=...`
- `APP_SID=AP...` (TwiML App SID)
- `PUSH_CREDENTIAL_SID=CR...` (FCM v1 credential)

I generated and added the nessasairy credentials in the `Server/.env` file

## Typical flows

1) App start
- Fetch access token from `/access-token?identity=<callerId>`
- Update `VoiceApplication` with token
- Get FCM token and call `Voice.register(accessToken, FCM, fcmToken)`

2) Incoming call
- Register mobile application with FCM through Voice.register() method and receive FCM token
- Hit the endpoint for place-ing a call to my application with my identity (https://quickstart-6052-dev.twil.io/place-call?to='identity')
- The application will be brought to the foreground and you will see an alert dialog. When answering you will hear a congratulaotry message.

3) Client to client call
- For testing client-to-client calls you would need to install the app on two different devices and enter different identities on each. This will result in fetching different access tokens for the devices with different identities.
- Run both applications and try to place a call in the first application entering the identity of the other.
- The other application should have an incoming call from the caller with the identity of the first application.

4) PSTN calls
- Run the application and hit the button for placing a call
- Enter a PSTN number and hit "call"

## Issues I encountered

- After setup (steps 1-7) application was reciving valid token and registering successfully with fcm. The issue was not reciving incoming calls to the application, which I was unable to resolve. Possible issues causing this:
  - Identity mismatch
  - TwiML App misconfigured
  - Push Credential (FCM v1) problem
  - Wrong Twilio account/region mixup...
    I'm hoping to resolve this, but it's the curret state of the app.

