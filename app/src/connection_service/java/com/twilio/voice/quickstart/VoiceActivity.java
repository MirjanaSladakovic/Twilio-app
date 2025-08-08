package com.twilio.voice.quickstart;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.twilio.voice.quickstart.VoiceApplication.voiceService;
import static java.lang.String.format;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.media.AudioManager;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.messaging.FirebaseMessaging;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.RegistrationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceActivity extends AppCompatActivity implements VoiceService.Observer  {
    private static String accessToken = null;
    private static final String ACCESS_TOKEN_URL = "https://quickstart-6052-dev.twil.io/access-token";
    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final Logger log = new Logger(VoiceActivity.class);
    private static final int PERMISSIONS_ALL = 100;

    private final List<VoiceConnectionService.AudioDevices> audioDevices = new ArrayList<>();
    private final BroadcastReceiver wiredHeadsetReceiver = wiredHeadsetReceiver();
    private final BroadcastReceiver bluetoothReceiver = bluetoothReceiver();
    private int savedVolumeControlStream;
    private MenuItem audioDeviceMenuItem;

    private CoordinatorLayout coordinatorLayout;
    private FloatingActionButton callActionFab;
    private FloatingActionButton hangupActionFab;
    private FloatingActionButton holdActionFab;
    private FloatingActionButton muteActionFab;
    private Chronometer chronometer;

    private AlertDialog alertDialog;
    private UUID activeCallId;
    private UUID pendingCallId;
    private String identity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        log.debug("onCreate");

        super.onCreate(savedInstanceState);

        // These flags ensure that the activity can be launched when the screen is locked.
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // setup ui
        setContentView(R.layout.activity_voice);

        coordinatorLayout = findViewById(R.id.coordinator_layout);
        callActionFab = findViewById(R.id.call_action_fab);
        hangupActionFab = findViewById(R.id.hangup_action_fab);
        holdActionFab = findViewById(R.id.hold_action_fab);
        muteActionFab = findViewById(R.id.mute_action_fab);
        chronometer = findViewById(R.id.chronometer);

        callActionFab.setOnClickListener(callActionFabClickListener());
        hangupActionFab.setOnClickListener(hangupActionFabClickListener());
        holdActionFab.setOnClickListener(holdActionFabClickListener());
        muteActionFab.setOnClickListener(muteActionFabClickListener());

        // register with voice service
        voiceService(voiceService -> voiceService.registerObserver(this));

        // Ask for identity then register incoming calls
        initIdentityAndRegister();

        // handle incoming intents
        handleIntent(getIntent());

        // Ensure required permissions are enabled
        String[] permissionsList = providePermissions();
        if (!hasPermissions(this, permissionsList)) {
            ActivityCompat.requestPermissions(this, permissionsList, PERMISSIONS_ALL);
        } else {
            setupAudioDeviceManagement();
        }
    }

    private void initIdentityAndRegister() {
        SharedPreferences prefs = getSharedPreferences("voice", MODE_PRIVATE);
        identity = prefs.getString("identity", null);
        if (identity == null || identity.trim().isEmpty()) {
            final EditText input = new EditText(this);
            input.setHint("Enter caller ID (e.g. alice)");
            new AlertDialog.Builder(this)
                    .setTitle("Set Caller ID")
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("Save", (d, w) -> {
                        identity = input.getText().toString().trim();
                        if (identity.isEmpty()) identity = "alice";
                        prefs.edit().putString("identity", identity).apply();
                        log.debug("Using identity: " + identity);
                        registerIncomingCalls();
                    })
                    .show();
        } else {
            log.debug("Using saved identity: " + identity);
            registerIncomingCalls();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        log.debug("onResume");
        super.onResume();

        // update ui
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    protected void onPause() {
        log.debug("onPause");
        super.onPause();
    }

    @Override
    public void onDestroy() {
        log.debug("onDestroy");

        // unregister with voice service
        voiceService(voiceService -> voiceService.unregisterObserver(this));

        // unregister from receivers
        unregisterReceiver(bluetoothReceiver);
        unregisterReceiver(wiredHeadsetReceiver);

        // restore previous volume stream
        setVolumeControlStream(savedVolumeControlStream);
        super.onDestroy();
    }

    @Override
    public void incomingCall(@NonNull final UUID callId, @NonNull final CallInvite invite) {
        log.debug("🔥 [VOICE_ACTIVITY] *** INCOMING CALL RECEIVED IN UI ***");
        log.debug(format("🔥 [VOICE_ACTIVITY] UI Call details:\n\tCall ID: %s\n\tFrom: %s\n\tTo: %s\n\tCall SID: %s", 
                callId,
                invite.getFrom(),
                invite.getTo(),
                invite.getCallSid()));
        
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
        log.debug("🔥 [VOICE_ACTIVITY] Updated UI after incoming call");}

    @Override
    public void connectCall(@NonNull final UUID callId, @NonNull ConnectOptions options) {
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void disconnectCall(@NonNull final UUID callId) {
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void acceptIncomingCall(@NonNull final UUID callId) {
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void rejectIncomingCall(@NonNull final UUID callId) {
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void cancelledCall(@NonNull final UUID callId) {
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void muteCall(@NonNull final UUID callId, boolean isMuted) {
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void holdCall(@NonNull final UUID callId, boolean isOnHold) {
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void registrationSuccessful(@NonNull String fcmToken) {
        log.debug("Successfully registered FCM " + fcmToken);
    }

    @Override
    public void registrationFailed(@NonNull RegistrationException error) {
        String message = format(
                Locale.US,
                "Registration Error: %d, %s",
                error.getErrorCode(),
                error.getMessage());
        log.error(message);
        Snackbar.make(coordinatorLayout, message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onRinging(@NonNull UUID callId) {
        // does nothing
    }

    @Override
    public void onConnectFailure(@NonNull UUID callId, @NonNull CallException error) {
        String message = format(
                Locale.US,
                "Call Error: %d, %s",
                error.getErrorCode(),
                error.getMessage());
        Snackbar.make(coordinatorLayout, message, Snackbar.LENGTH_LONG).show();
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void onConnected(@NonNull UUID callId) {
        // does nothing
    }

    @Override
    public void onReconnecting(@NonNull UUID callId, @NonNull CallException callException) {
        Snackbar.make(
                coordinatorLayout, "Call attempting reconnection", Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onReconnected(@NonNull UUID callId) {
        Snackbar.make(coordinatorLayout, "Call reconnected", Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onDisconnected(@NonNull UUID callId, CallException error) {
        if (error != null) {
            String message = format(
                    Locale.US,
                    "Call Error: %d, %s",
                    error.getErrorCode(),
                    error.getMessage());
            Snackbar.make(coordinatorLayout, message, Snackbar.LENGTH_LONG).show();
        }
        voiceService(voiceService -> updateUI(voiceService.getStatus()));
    }

    @Override
    public void onCallQualityWarningsChanged(@NonNull UUID callId,
                                             @NonNull Set<Call.CallQualityWarning> currentWarnings,
                                             @NonNull Set<Call.CallQualityWarning> previousWarnings) {
        // currentWarnings: existing quality warnings that have not been cleared yet
        // previousWarnings: last set of warnings prior to receiving this callback
        //
        // Example:
        //  - currentWarnings: { A, B }
        //  - previousWarnings: { B, C }
        //
        // Newly raised warnings = currentWarnings - intersection = { A }
        // Newly cleared warnings = previousWarnings - intersection = { C }
        if (previousWarnings.size() > 1) {
            Set<Call.CallQualityWarning> intersection = new HashSet<>(currentWarnings);
            currentWarnings.removeAll(previousWarnings);
            intersection.retainAll(previousWarnings);
            previousWarnings.removeAll(intersection);
        }

        String message = format(
                Locale.US,
                "Newly raised warnings: " + currentWarnings + " Clear warnings " + previousWarnings);
        Snackbar.make(coordinatorLayout, message, Snackbar.LENGTH_LONG).show();
    }

    static private String[] providePermissions() {
        List<String> permissionsList = new Vector<>() {{
            add(Manifest.permission.RECORD_AUDIO);
            add(Manifest.permission.MANAGE_OWN_CALLS);
            if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }};
        String[] list = new String[permissionsList.size()];
        return permissionsList.toArray(list);
    }

    private Map<String, String> providePermissionsMesageMap() {
        return new HashMap<>() {{
            put(Manifest.permission.RECORD_AUDIO, getString(R.string.audio_permissions_rational));
            put(Manifest.permission.MANAGE_OWN_CALLS, getString(R.string.manage_call_permissions_rational));
            if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                put(Manifest.permission.BLUETOOTH_CONNECT, getString(R.string.bluetooth_permissions_rational));
            }
            if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                put(Manifest.permission.POST_NOTIFICATIONS, getString(R.string.notification_permissions_rational));
            }
        }};
    }

    private void updateUI(VoiceService.Status  status) {
        // if there are any active calls, show in-call UI
        if (status.callMap.isEmpty()) {
            // hide in-call buttons
            muteActionFab.hide();
            holdActionFab.hide();
            hangupActionFab.hide();
            chronometer.setVisibility(View.INVISIBLE);
            chronometer.stop();
            // show make-call ui
            callActionFab.show();
        } else {
            // hide make-call ui
            callActionFab.hide();
            // setup in-call ui
            muteActionFab.setImageDrawable(ContextCompat.getDrawable(
                    VoiceActivity.this, R.drawable.ic_mic_white_24dp));
            holdActionFab.setBackgroundTintList(ColorStateList
                    .valueOf(ContextCompat.getColor(this, R.color.colorAccent)));
            applyFabState(muteActionFab,
                    Objects.requireNonNull(status.callMap.get(status.activeCall)).isMuted);
            applyFabState(holdActionFab,
                    Objects.requireNonNull(status.callMap.get(status.activeCall)).onHold);
            // show ui
            hangupActionFab.show();
            holdActionFab.show();
            muteActionFab.show();
            chronometer.setVisibility(View.VISIBLE);
            chronometer.setBase(
                    Objects.requireNonNull(status.callMap.get(status.activeCall)).timestamp);
            chronometer.start();
        }
        // if there are any pending calls, show incoming call dialog
        hideAlertDialog();
        if (!status.pendingCalls.isEmpty()) {
            // get the first call
            for (Map.Entry<UUID, CallInvite> entry : status.pendingCalls.entrySet()) {
                pendingCallId = entry.getKey();
                if (isAppVisible()) {
                    showIncomingCallDialog(entry.getValue());
                }
                break;
            }
        }
        // set active call
        activeCallId = status.activeCall;
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            pendingCallId = (UUID) intent.getSerializableExtra(Constants.CALL_UUID);

            switch (action) {
                case Constants.ACTION_INCOMING_CALL_NOTIFICATION:
                    voiceService(voiceService -> updateUI(voiceService.getStatus()));
                    break;
                case Constants.ACTION_ACCEPT_CALL:
                    answerCall(pendingCallId);
                    break;
                default:
                    break;
            }
        }
    }

    private DialogInterface.OnClickListener answerCallClickListener() {
        return (dialog, which) -> {
            log.debug("Clicked accept");
            answerCall(pendingCallId);
        };
    }

    private DialogInterface.OnClickListener rejectCallClickListener() {
        return (dialogInterface, i) ->
                voiceService(voiceService -> voiceService.rejectIncomingCall(pendingCallId));
    }

    private DialogInterface.OnClickListener cancelCallClickListener() {
        return (dialogInterface, i) -> {
            hideAlertDialog();
        };
    }

    private DialogInterface.OnClickListener callClickListener() {
        return (dialog, which) -> {
            // Place a call
            EditText contact = ((AlertDialog) dialog).findViewById(R.id.contact);
            final Map<String, String> params = new HashMap<>() {{
                put("to", contact.getText().toString());
            }};
            ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                    .params(params)
                    .build();
            hideAlertDialog();
            voiceService(voiceService -> activeCallId = voiceService.connectCall(connectOptions));
        };
    }

    public static AlertDialog createIncomingCallDialog(
            Context context,
            CallInvite callInvite,
            DialogInterface.OnClickListener answerCallClickListener,
            DialogInterface.OnClickListener cancelClickListener) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
        alertDialogBuilder.setIcon(R.drawable.ic_call_black_24dp);
        alertDialogBuilder.setTitle("Incoming Call");
        alertDialogBuilder.setPositiveButton("Accept", answerCallClickListener);
        alertDialogBuilder.setNegativeButton("Reject", cancelClickListener);
        alertDialogBuilder.setMessage(callInvite.getFrom() + " is calling with " + callInvite.getCallerInfo().isVerified() + " status");
        return alertDialogBuilder.create();
    }

    private View.OnClickListener callActionFabClickListener() {
        return v -> {
            alertDialog = createCallDialog(
                    callClickListener(), cancelCallClickListener(), VoiceActivity.this);
            alertDialog.show();
        };
    }

    private View.OnClickListener hangupActionFabClickListener() {
        return v -> voiceService(voiceService -> voiceService.disconnectCall(activeCallId));
    }

    private View.OnClickListener holdActionFabClickListener() {
        return v -> hold();
    }

    private View.OnClickListener muteActionFabClickListener() {
        return v -> mute();
    }

    private void answerCall(final UUID callId) {
        voiceService(voiceService -> activeCallId = voiceService.acceptCall(callId));
    }

    private void hold() {
        if (activeCallId != null) {
            voiceService(voiceService -> voiceService.holdCall(activeCallId));
        }
    }

    private void mute() {
        if (activeCallId != null) {
            voiceService(voiceService -> voiceService.muteCall(activeCallId));
        }
    }

    private void applyFabState(FloatingActionButton button, boolean enabled) {
        // Set fab as pressed when call is on hold
        ColorStateList colorStateList = enabled ?
                ColorStateList.valueOf(ContextCompat.getColor(this,
                        R.color.colorPrimaryDark)) :
                ColorStateList.valueOf(ContextCompat.getColor(this,
                        R.color.colorAccent));
        button.setBackgroundTintList(colorStateList);
    }

    private static boolean hasPermissions(Context context, final String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (PERMISSION_GRANTED != ActivityCompat.checkSelfPermission(context, permission)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final Map<String, String> permissionsMessageMap = providePermissionsMesageMap();
        for (String permission : permissions) {
            if (!hasPermissions(this, permission)) {
                Snackbar.make(
                        coordinatorLayout,
                        Objects.requireNonNull(permissionsMessageMap.get(permission)),
                        Snackbar.LENGTH_LONG).show();
            }
        }
        setupAudioDeviceManagement();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        audioDeviceMenuItem = menu.findItem(R.id.menu_audio_device);
        if (audioDevices.contains(VoiceConnectionService.AudioDevices.Bluetooth)) {
            updateAudioDeviceIcon(VoiceConnectionService.AudioDevices.Bluetooth);
        } else if (!audioDevices.isEmpty()) {
            updateAudioDeviceIcon(audioDevices.get(audioDevices.size() - 1));
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_audio_device) {
            showAudioDevices();
            return true;
        }
        return false;
    }

    // Show the current available audio devices.
    private void showAudioDevices() {
        List<String> devices = new ArrayList<>();

        for (VoiceConnectionService.AudioDevices device : audioDevices) {
            devices.add(device.name());
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.select_device)
            .setSingleChoiceItems(
                devices.toArray(new CharSequence[0]),
                0,
                (dialog, index) -> {
                    dialog.dismiss();
                    VoiceConnectionService.AudioDevices selectedDevice = audioDevices.get(index);
                    updateAudioDeviceIcon(selectedDevice);
                    if (null != activeCallId) {
                        VoiceConnectionService.selectAudioDevice(activeCallId, selectedDevice);
                    }
                    Collections.swap(audioDevices, 0, index);
                }).create().show();
    }

    // Update the menu icon based on the currently selected audio device.
    private void updateAudioDeviceIcon(VoiceConnectionService.AudioDevices selectedAudioDevice) {
        if (audioDeviceMenuItem != null) {
            switch (selectedAudioDevice) {
                case Bluetooth:
                    audioDeviceMenuItem.setIcon(R.drawable.ic_bluetooth_white_24dp);
                    break;
                case Headset:
                    audioDeviceMenuItem.setIcon(R.drawable.ic_headset_mic_white_24dp);
                    break;
                case Speaker:
                    audioDeviceMenuItem.setIcon(R.drawable.ic_volume_up_white_24dp);
                    break;
                default:
                    audioDeviceMenuItem.setIcon(R.drawable.ic_phonelink_ring_white_24dp);
            }
        }
    }

    private static AlertDialog createCallDialog(final DialogInterface.OnClickListener callClickListener,
                                                final DialogInterface.OnClickListener cancelClickListener,
                                                final Activity activity) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(activity);

        alertDialogBuilder.setIcon(R.drawable.ic_call_black_24dp);
        alertDialogBuilder.setTitle("Call");
        alertDialogBuilder.setPositiveButton("Call", callClickListener);
        alertDialogBuilder.setNegativeButton("Cancel", cancelClickListener);
        alertDialogBuilder.setCancelable(false);

        LayoutInflater li = LayoutInflater.from(activity);
        View dialogView = li.inflate(
                R.layout.dialog_call,
                activity.findViewById(android.R.id.content),
                false);
        final EditText contact = dialogView.findViewById(R.id.contact);
        contact.setHint(R.string.callee);
        alertDialogBuilder.setView(dialogView);

        return alertDialogBuilder.create();

    }

    private void showIncomingCallDialog(@NonNull final CallInvite callInvite) {
        alertDialog = createIncomingCallDialog(VoiceActivity.this,
                callInvite,
                answerCallClickListener(),
                rejectCallClickListener());
        alertDialog.show();
    }

    private void hideAlertDialog() {
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
            alertDialog = null;
        }
    }

    private boolean isAppVisible() {
        return ProcessLifecycleOwner
                .get()
                .getLifecycle()
                .getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED);
    }

    private BroadcastReceiver wiredHeadsetReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra("state", 0);
                if (state == 1) { // plugged
                    audioDevices.add(VoiceConnectionService.AudioDevices.Headset);
                    updateAudioDeviceIcon(VoiceConnectionService.AudioDevices.Headset);
                } else {
                    audioDevices.remove(VoiceConnectionService.AudioDevices.Headset);
                    if (audioDevices.contains(VoiceConnectionService.AudioDevices.Bluetooth)) {
                        updateAudioDeviceIcon(VoiceConnectionService.AudioDevices.Bluetooth);
                    } else {
                        updateAudioDeviceIcon(VoiceConnectionService.AudioDevices.Earpiece);
                    }
                }
            }
        };
    }

    private BroadcastReceiver bluetoothReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR);
                    switch (state) {
                        case BluetoothAdapter.STATE_CONNECTED:
                            addBluetoothDevice();
                            break;
                        case BluetoothAdapter.STATE_DISCONNECTED:
                            removeBluetoothDevice();
                            break;
                    }
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    switch (state) {
                        case BluetoothAdapter.STATE_ON:
                            addBluetoothDevice();
                            break;
                        case BluetoothAdapter.STATE_OFF:
                            removeBluetoothDevice();
                            break;
                    }
                }
            }
        };
    }

    @SuppressLint("MissingPermission")
    private boolean setupBluetooth() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(bluetoothReceiver, intentFilter);
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()
                && mBluetoothAdapter.getProfileConnectionState(BluetoothHeadset.HEADSET) == BluetoothAdapter.STATE_CONNECTED;
    }

    private void addBluetoothDevice() {
        audioDevices.add(VoiceConnectionService.AudioDevices.Bluetooth);
        updateAudioDeviceIcon(VoiceConnectionService.AudioDevices.Bluetooth);
    }

    private void removeBluetoothDevice() {
        audioDevices.remove(VoiceConnectionService.AudioDevices.Bluetooth);
        if (audioDevices.contains(VoiceConnectionService.AudioDevices.Headset)) {
            updateAudioDeviceIcon(VoiceConnectionService.AudioDevices.Headset);
        } else {
            updateAudioDeviceIcon(VoiceConnectionService.AudioDevices.Earpiece);
        }
    }

    private void setupAudioDeviceManagement() {
        /*
         * Setup audio device management and set the volume control stream
         * Assume devices have speaker and earpiece
         */
        savedVolumeControlStream = getVolumeControlStream();
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        audioDevices.add(VoiceConnectionService.AudioDevices.Earpiece);
        audioDevices.add(VoiceConnectionService.AudioDevices.Speaker);
        if (setupBluetooth()) {
            audioDevices.add(VoiceConnectionService.AudioDevices.Bluetooth);
        }
        registerReceiver(wiredHeadsetReceiver, new IntentFilter(AudioManager.ACTION_HEADSET_PLUG));
    }

    private void registerIncomingCalls() {
        // First fetch the access token, then register FCM
        fetchAccessToken(new AccessTokenCallback() {
            @Override
            public void onSuccess(String token) {
                accessToken = token;
                // Update VoiceApplication with the new access token
                updateVoiceApplicationToken(token);
                // Now register FCM with the fresh token
                log.debug("🔥 [FCM] Fetching FCM token...");
                FirebaseMessaging.getInstance().getToken()
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                String fcmToken = task.getResult();
                                log.debug("🔥 [FCM] Got FCM token: " + fcmToken.substring(0, Math.min(50, fcmToken.length())) + "...");
                                voiceService(voiceService ->
                                        voiceService.registerFCMToken(fcmToken));
                            } else {
                                log.error("🔥 [FCM] FCM token retrieval failed: " +
                                        Objects.requireNonNull(task.getException()).getMessage());
                            }
                        });
            }

            @Override
            public void onError(String error) {
                log.error("Failed to fetch access token: " + error);
                // Fallback to registration with existing token if available
                if (accessToken != null) {
                    FirebaseMessaging.getInstance().getToken()
                            .addOnCompleteListener(task -> {
                                if (task.isSuccessful()) {
                                    voiceService(voiceService ->
                                            voiceService.registerFCMToken(task.getResult()));
                                } else {
                                    log.error("FCM token retrieval failed: " +
                                            Objects.requireNonNull(task.getException()).getMessage());
                                }
                            });
                }
            }
        });
    }

    private void fetchAccessToken(AccessTokenCallback callback) {
        executor.execute(() -> {
            try {
                String encoded = identity != null ? identity : "alice";
                try { encoded = URLEncoder.encode(encoded, "UTF-8"); } catch (Exception ignore) {}
                String url = ACCESS_TOKEN_URL + "?identity=" + encoded;
                Request request = new Request.Builder().url(url).build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String token = response.body().string().trim();
                        runOnUiThread(() -> callback.onSuccess(token));
                    } else {
                        runOnUiThread(() -> callback.onError("HTTP " + response.code()));
                    }
                }
            } catch (IOException e) {
                runOnUiThread(() -> callback.onError(e.getMessage()));
            }
        });
    }

    private interface AccessTokenCallback {
        void onSuccess(String token);
        void onError(String error);
    }

    private void updateVoiceApplicationToken(String token) {
        // Update the VoiceApplication with the new access token
        VoiceApplication.updateAccessToken(token);
    }
}
