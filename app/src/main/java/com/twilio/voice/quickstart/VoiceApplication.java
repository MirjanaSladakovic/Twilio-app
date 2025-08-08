package com.twilio.voice.quickstart;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class VoiceApplication extends Application {

    private static VoiceApplication instance;
    private ServiceConnectionManager serviceConnectionManager;

    public interface VoiceServiceTask {
        void run(final VoiceService voiceService);
    }

    public static void voiceService(VoiceServiceTask task) {
        instance.serviceConnectionManager.invoke(task);
    }

    public static void updateAccessToken(String token) {
        instance.serviceConnectionManager.updateAccessToken(token);
    }

    public VoiceApplication() {
        instance = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize service connection manager without access token initially
        serviceConnectionManager = new ServiceConnectionManager(this, null);
    }

    @Override
    public void onTerminate() {
        // Note: this method is not called when running on device, devices just kill the process.
        serviceConnectionManager.unbind();

        super.onTerminate();
    }

    private static class ServiceConnectionManager {
        private VoiceService voiceService = null;
        private final List<VoiceServiceTask> pendingTasks = new ArrayList<>();
        private String accessToken;
        private final Context context;
        private final ServiceConnection serviceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // verify is main thread, all Voice SDK calls must be made on the same Looper thread
                assert(Looper.myLooper() == Looper.getMainLooper());
                // link to voice service
                voiceService = ((VoiceService.VideoServiceBinder)service).getService();
                // run tasks
                synchronized(ServiceConnectionManager.this) {
                    for (VoiceServiceTask task : pendingTasks) {
                        task.run(voiceService);
                    }
                    pendingTasks.clear();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                voiceService = null;
            }
        };

        public ServiceConnectionManager(final Context context,
                                        final String accessToken) {
            this.context = context;
            this.accessToken = accessToken;
        }

        public void unbind() {
            if (null != voiceService) {
                context.unbindService(serviceConnection);
            }
        }

        public void invoke(VoiceServiceTask task) {
            if (null != voiceService) {
                // verify is main thread, all Voice SDK calls must be made on the same Looper thread
                assert(Looper.myLooper() == Looper.getMainLooper());
                // run task
                synchronized (this) {
                    task.run(voiceService);
                }
            } else {
                // queue runnable
                pendingTasks.add(task);
                // Only bind to service if we have an access token
                if (accessToken != null) {
                    bindToService();
                }
            }
        }

        public void updateAccessToken(String token) {
            this.accessToken = token;
            // If we have pending tasks and now have an access token, bind to service
            if (!pendingTasks.isEmpty()) {
                bindToService();
            }
        }

        private void bindToService() {
            Intent intent = new Intent(context, VoiceService.class);
            intent.putExtra(Constants.ACCESS_TOKEN, accessToken);
            intent.putExtra(Constants.CUSTOM_RINGBACK, BuildConfig.playCustomRingback);
            context.bindService(
                    intent,
                    serviceConnection,
                    BIND_AUTO_CREATE);
        }
    }
}
