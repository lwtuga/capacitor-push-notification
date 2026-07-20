package com.capacitorjs.plugins.pushnotifications;

// import static androidx.core.content.ContextCompat.getSystemService;

import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import com.capacitorjs.plugins.pushnotifications.acknowledge.AcknowledgeService;

import android.net.Uri;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaMetadataRetriever;

import android.os.Bundle;

import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

public class MessagingService extends FirebaseMessagingService {
    private final AcknowledgeService acknowledgeService = new AcknowledgeService();
    private static Ringtone ringtone;

    public void handleIntent(Intent intent) {
        Log.i("MessagingService", "intent received");

        // 1. Channel VOR der Verarbeitung durch das System/Plugin erstellen -> unterdrückt den Systemton
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
          if (notificationManager != null) {
            // Die ID "critical_alerts_silent" muss mit der channel_id im Push-Payload übereinstimmen
            String channelId = "critical_alerts_silent";
            NotificationChannel channel = notificationManager.getNotificationChannel(channelId);

            if (channel == null) {
              channel = new NotificationChannel(
                channelId,
                "Kritische Alarme",
                NotificationManager.IMPORTANCE_HIGH
              );
              // Absolut stumm schalten
              channel.setSound(null, null);
              channel.enableVibration(true);
              notificationManager.createNotificationChannel(channel);
              Log.i("MessagingService", "Silent Channel erstellt");
            }
          }
        }

        super.handleIntent(intent);
        Bundle bundle = intent.getExtras();

        SharedPreferences sharedPreferences = getApplicationContext().getSharedPreferences("it.tuga.fireteam", Context.MODE_PRIVATE);
        String criticalAlert = sharedPreferences.getString("criticalalert", "0");
        String ricalarmton = sharedPreferences.getString("ricalarmton", "");
        String sound = null;

        if(bundle != null) {
          String ric = bundle.getString("ric");
          String subric = bundle.getString("subric");
          if(ric != null && subric != null) {
            Log.i("MessagingServiceTuGA ric", ric);
            Log.i("MessagingServiceTuGA subric", subric);
            
            try {
              if (Objects.equals(subric, "A")) {
                sound = new JSONObject(ricalarmton).getJSONObject(ric).getString("alarmtona");
                Log.i("MessagingServiceTuGA sound", sound);
              } else {
                sound = new JSONObject(ricalarmton).getJSONObject(ric).getString("alarmtonc");
              }
            } catch (JSONException e) {
              Log.e("MessagingService", "Error parsing JSON from ricalarmton", e);
            }
          }

          // Benutzer muss Lokal criticalAlert gesetzt haben + die Nachricht muss key criticalalert enthalten
          if (Objects.equals(criticalAlert, "1") && bundle.containsKey("criticalalert") && sound != null) {
            Log.i("MessagingService bundle", "criticalalert");

            // Wert aus Nachricht auswerten
            String bundleCriticalalert = bundle.getString("criticalalert");
            if (Objects.equals(bundleCriticalalert, "1")) {
              Log.i("MessagingService ricalarmton", "ricalarmton");
              Log.i("MessagingServiceTuGA bundle", criticalAlert);
              Log.i("MessagingServiceTuGA ricalarmton", ricalarmton);

              var audioManager = (AudioManager) getSystemService(ContextWrapper.AUDIO_SERVICE);
              if(audioManager != null) {
                int originalRingMode = audioManager.getRingerMode();
                //boolean volumeFixed = audioManager.isVolumeFixed();

                int originalNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                int maxNotificationVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
                // bei Xiami Geräten wird STREAM_ALARM statt STREAM_RING verwendet
                int originalNotificationVolumeAlarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                int maxNotificationVolumeAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);

                Log.i("MessagingService", "originalRingMode " + originalRingMode + " " + originalNotificationVolume + " " + maxNotificationVolume);
                var notificationManager = (NotificationManager) getSystemService(ContextWrapper.NOTIFICATION_SERVICE);
                int isDndModeEnabled = 0;
                if (notificationManager != null) {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    isDndModeEnabled = notificationManager.getCurrentInterruptionFilter();
                  }
                  if (isDndModeEnabled != NotificationManager.INTERRUPTION_FILTER_ALL && originalRingMode == AudioManager.RINGER_MODE_SILENT && originalNotificationVolume != 0) {
                    originalRingMode = AudioManager.RINGER_MODE_NORMAL;
                  }
                }
                int finalIsDndModeEnabled = isDndModeEnabled;

                Log.i("MessagingService", "isDndModeEnabled "+isDndModeEnabled);
                try {  // Samsung Geräte benötigen setInterruptionFilter=INTERRUPTION_FILTER_All (1)
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && finalIsDndModeEnabled != 1) {
                    notificationManager.setInterruptionFilter(1);
                    isDndModeEnabled = notificationManager.getCurrentInterruptionFilter();
                    Log.i("MessagingService", "isDndModeEnabled2 "+isDndModeEnabled);
                  }
                } catch (Exception e) {
                  Log.e("MessagingService", "setInterruptionFilter fehler", e);
                }

                // When DND mode is enabled, we get ringerMode as silent even though actual ringer mode is Normal
                //          int isDndModeEnabled = NotificationManagerCompat.from(myContext).getCurrentInterruptionFilter();
                //          if (isDndModeEnabled != NotificationManager.INTERRUPTION_FILTER_ALL && originalRingMode == AudioManager.RINGER_MODE_SILENT && originalNotificationVolume != 0) {
                //            originalRingMode = AudioManager.RINGER_MODE_NORMAL;
                //          }
                Log.i("MessagingService", "originalNotificationVolume "+originalNotificationVolume+" maxNotificationVolume "+maxNotificationVolume);
                // ringToneVolume != null ? (int) Math.ceil(maxNotificationVolume * ringToneVolume) : originalNotificationVolume;

                try {
                  audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                } catch (Exception e) {
                  Log.e("MessagingService", "RINGER_MODE_NORMAL not set", e);
                }
                int originalRingMode1 = audioManager.getRingerMode();
                Log.i("MessagingService", "RINGER_MODE_NORMAL "+originalRingMode1);

                try {
                  audioManager.setStreamVolume(AudioManager.STREAM_RING, maxNotificationVolume, 0);
                  audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxNotificationVolumeAlarm, 0);
                } catch (Exception e) {
                  Log.e("MessagingService", "maxNotificationVolume not set", e);
                }

                int sv1 = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                Log.i("MessagingService", "sv1 "+sv1);
                int sv1Alarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                Log.i("MessagingService", "sv1Alarm "+sv1Alarm);
                // Resetting the original ring mode, volume and dnd mode
                int finalOriginalRingMode = originalRingMode;

                Uri soundUri = getSoundUri(sound);

                ringtone = RingtoneManager.getRingtone(getApplicationContext(), soundUri);
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ringtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .build());
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.setVolume(1.0f);
                    }
                    ringtone.play();
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                  if (ringtone != null) {
                      ringtone.stop();
                  }
                  try {
                    audioManager.setRingerMode(finalOriginalRingMode);
                  } catch (Exception e) {
                    Log.e("MessagingService", "finalOriginalRingMode not set", e);
                  }

                  try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                      notificationManager.setInterruptionFilter(finalIsDndModeEnabled);
                    }
                  } catch (Exception e) {
                    Log.e("MessagingService", "setInterruptionFilter fehler", e);
                  }

                  try {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, originalNotificationVolume, 0);
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalNotificationVolumeAlarm, 0);
                  } catch (Exception e) {
                    Log.e("MessagingService", "originalNotificationVolume not set", e);
                  }

                  try {
                    int sv2 = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                  } catch (Exception e) {
                    Log.e("MessagingService", "originalNotificationVolume not set3", e);
                  }
                  int sv2 = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                  Log.i("MessagingService", "sv2 " + sv2);
                }, getSoundFileDuration(soundUri));
              }
            }
          } else if (sound != null) {
            // Normaler Alarm: Benutzerdefinierter Ton mit normaler Lautstärke
            Log.i("MessagingService", "Normal alarm - playing custom sound");
            Uri soundUri = getSoundUri(sound);

            ringtone = RingtoneManager.getRingtone(getApplicationContext(), soundUri);
            if (ringtone != null) {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                  .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                  .build());
              }
              ringtone.play();
            }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
              if (ringtone != null) {
                ringtone.stop();
              }
            }, getSoundFileDuration(soundUri));
          }
        }

        this.acknowledgeService.initContent(this);
        this.acknowledgeService.newNotification(intent);
        Log.i("MessagingService", "intent exit");
    }

    public static void stopRingtone() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }

    public int getSoundFileDuration(Uri uri) {
      try {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(this, uri);
        String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        return durationStr != null ? Integer.parseInt(durationStr) : 0;
      } catch (Exception ex) {
        return 5000;
      }
    }

    public Uri getSoundUri(String sound) {
        if (sound != null && !sound.isEmpty()) {
            int soundId = getResources().getIdentifier(sound, "raw", getPackageName());
            if (soundId != 0) {
                return Uri.parse("android.resource://" + getPackageName() + "/" + soundId);
            }
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        // Only call super if there is no critical alert and no custom sound (ric/subric), so the default notification sound is not played.
        if (remoteMessage.getData().get("criticalalert") == null) {
            super.onMessageReceived(remoteMessage);
        }
        PushNotificationsPlugin.sendRemoteMessage(remoteMessage);
    }

    @Override
    public void onNewToken(@NonNull String s) {
        super.onNewToken(s);
        PushNotificationsPlugin.onNewToken(s);
    }
}
