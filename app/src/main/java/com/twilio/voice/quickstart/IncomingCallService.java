package com.twilio.voice.quickstart;

import static com.twilio.voice.quickstart.Constants.ACTION_INCOMING_CALL;
import static com.twilio.voice.quickstart.Constants.INCOMING_CALL_INVITE;
import static com.twilio.voice.quickstart.Constants.ACTION_CANCEL_CALL;
import static com.twilio.voice.quickstart.Constants.CANCELLED_CALL_INVITE;
import static java.lang.String.format;

import android.content.Intent;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.os.Parcelable;
import android.util.Pair;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;
import com.twilio.voice.Voice;

public class IncomingCallService extends FirebaseMessagingService implements MessageListener {
    private static final Logger log = new Logger(IncomingCallService.class);

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        log.debug("🔥 [INCOMING] FCM Message Received!");
        log.debug(format(
                "🔥 [INCOMING] Firebase message details:\n\tmessage data: %s\n\tfrom: %s\n\tmessage id: %s",
                remoteMessage.getData(),
                remoteMessage.getFrom(),
                remoteMessage.getMessageId()));

        // Check if message contains a data payload.
        if (remoteMessage.getData().isEmpty()) {
            log.error("🔥 [INCOMING] ERROR: Firebase message has no data payload!");
            return;
        }

        log.debug("🔥 [INCOMING] Processing message with Voice SDK...");
        boolean isHandled = Voice.handleMessage(this, remoteMessage.getData(), this);
        if (!isHandled) {
            log.error(format("🔥 [INCOMING] ERROR: Message was not a valid Twilio Voice SDK payload: %s", remoteMessage.getData()));
        } else {
            log.debug("🔥 [INCOMING] SUCCESS: Message was handled by Voice SDK");
        }
    }

    @CallSuper
    @Override
    public void onNewToken(@NonNull String token) {
        log.debug("🔥 [INCOMING] FCM Token Updated: " + token.substring(0, Math.min(50, token.length())) + "...");
    }

    @Override
    public void onCallInvite(@NonNull CallInvite callInvite) {
        log.debug("🔥 [INCOMING] *** CALL INVITE RECEIVED! ***");
        log.debug(format("🔥 [INCOMING] Call details:\n\tFrom: %s\n\tTo: %s\n\tCall SID: %s", 
                callInvite.getFrom(),
                callInvite.getTo(),
                callInvite.getCallSid()));
        
        startVoiceService(
                ACTION_INCOMING_CALL,
                new Pair<>(INCOMING_CALL_INVITE, callInvite));
        
        log.debug("🔥 [INCOMING] Started VoiceService with ACTION_INCOMING_CALL");
    }

    @Override
    public void onCancelledCallInvite(@NonNull CancelledCallInvite cancelledCallInvite,
                                      @Nullable CallException callException) {
        log.debug("🔥 [INCOMING] Call Cancelled - Call SID: " + cancelledCallInvite.getCallSid());
        if (callException != null) {
            log.error("🔥 [INCOMING] Call cancellation exception: " + callException.getMessage());
        }
        
        startVoiceService(
                ACTION_CANCEL_CALL,
                new Pair<>(CANCELLED_CALL_INVITE, cancelledCallInvite));
    }

    @SafeVarargs
    private void startVoiceService(@NonNull final String action,
                                   @NonNull final Pair<String, Object>...data) {
        final Intent intent = new Intent(this, VoiceService.class);
        intent.setAction(action);
        for (Pair<String, Object> pair: data) {
            if (pair.second instanceof String) {
                intent.putExtra(pair.first, (String)pair.second);
            } else if (pair.second instanceof Parcelable) {
                intent.putExtra(pair.first, (Parcelable)pair.second);
            }
        }
        startService(intent);
    }
}
