package com.capacitorjs.plugins.pushnotifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.CommonNotificationBuilder;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.NotificationParams;
import com.google.firebase.messaging.RemoteMessage;

import com.capacitorjs.plugins.pushnotifications.acknowledge.AcknowledgeService;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

public class MessagingService extends FirebaseMessagingService {
    // Wert von alarmtona/alarmtonc, bei dem gar kein Ton abgespielt wird (auch nicht der Standardton)
    private static final String SOUND_SILENT = "Silent";
    // Kanal ohne Ton (der Alarmton wird selbst abgespielt), vibriert
    private static final String ALARM_CHANNEL_ID = "critical_alerts_silent";
    // Kanal ohne Ton und Vibration für Alarme mit Ton "Silent", wenn kein kritischer Alarm ist
    private static final String SILENT_NO_VIBRATION_CHANNEL_ID = "alerts_silent_no_vibration";
    // wie ALARM_CHANNEL_ID, darf aber Nicht-Stören umgehen (z.B. Xiaomi stumm). bypassDnd wird von Android nur beim
    // Erstellen und nur mit Zugriff auf Nicht-Stören übernommen, deshalb ein eigener Kanal, der erst dann erstellt wird
    private static final String ALARM_DND_CHANNEL_ID = "critical_alerts_dnd";
    private static final int DEFAULT_SOUND_DURATION_MS = 5000;
    // Puffer, damit das Tonende nicht abgeschnitten wird, bevor die Einstellungen zurückgesetzt werden
    private static final int SOUND_END_BUFFER_MS = 500;
    // wie im FirebaseMessagingService: an den letzten 10 Message-IDs werden doppelt zugestellte Nachrichten erkannt
    private static final int RECENT_MESSAGE_IDS_MAX = 10;

    private static final Object LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<String> recentMessageIds = new ArrayDeque<>();
    private static Ringtone ringtone;
    // Einstellungen von vor dem Alarm, != null solange ein Alarmton läuft
    private static AlarmSession session;
    // verhindert, dass der verzögerte Stopp eines bereits ersetzten Alarms den neuen Alarm beendet
    private static volatile int alarmGeneration;

    private final AcknowledgeService acknowledgeService = new AcknowledgeService();

    public void handleIntent(Intent intent) {
        Log.i("MessagingService", "intent received");

        // 1. Channels VOR der Verarbeitung durch das System/Plugin erstellen -> unterdrückt den Systemton
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
          if (notificationManager != null) {
            // Die ID "critical_alerts_silent" muss mit der channel_id im Push-Payload übereinstimmen
            createSilentChannel(notificationManager, ALARM_CHANNEL_ID, "Kritische Alarme", true, false);
            // für Alarme mit Ton "Silent" ohne kritischen Alarm: weder Ton noch Vibration
            createSilentChannel(notificationManager, SILENT_NO_VIBRATION_CHANNEL_ID, "Stille Alarme", false, false);
            if (notificationManager.isNotificationPolicyAccessGranted()) {
              createSilentChannel(notificationManager, ALARM_DND_CHANNEL_ID, "Kritische Alarme", true, true);
            }
          }
        }

        // super.handleIntent verwirft doppelt zugestellte Nachrichten, der Code danach würde aber trotzdem laufen
        boolean duplicate = isDuplicateMessage(intent);

        // ruft bei Datennachrichten onMessageReceived auf, dort wird die Benachrichtigung angezeigt
        super.handleIntent(intent);
        if (duplicate) {
            Log.i("MessagingService", "duplicate message ignored");
            return;
        }
        Bundle bundle = intent.getExtras();

        if(bundle != null) {
          String ric = bundle.getString("ric");
          String subric = bundle.getString("subric");
          if(ric != null && subric != null) {
            Log.i("MessagingServiceTuGA ric", ric);
            Log.i("MessagingServiceTuGA subric", subric);
          }
          String sound = getAlarmSound(ric, subric);
          boolean critical = isCriticalAlarm(bundle.getString("criticalalert"));

          if (sound != null && isSilentSound(sound)) {
            // Kein Ton und keine Änderung an Klingelmodus, Lautstärke oder Nicht-Stören - ohne kritischen Alarm auch keine Vibration
            Log.i("MessagingService", critical ? "Silent critical alarm - no sound played" : "Silent alarm - no sound and no vibration");
          } else if (sound != null) {
            Log.i("MessagingService", critical ? "Critical alarm - playing custom sound" : "Normal alarm - playing custom sound");
            playAlarm(sound, critical);
          }
        }

        this.acknowledgeService.initContent(this);
        this.acknowledgeService.newNotification(intent);
        Log.i("MessagingService", "intent exit");
    }

    /** Alarmton aus der RIC-Konfiguration: Subric "A" -> alarmtona, sonst alarmtonc. null, wenn keiner hinterlegt ist. */
    private String getAlarmSound(String ric, String subric) {
        if (ric == null || subric == null) {
            return null;
        }
        String ricalarmton = getSharedPreferences("it.tuga.fireteam", Context.MODE_PRIVATE).getString("ricalarmton", "");
        try {
            String key = Objects.equals(subric, "A") ? "alarmtona" : "alarmtonc";
            String sound = new JSONObject(ricalarmton).getJSONObject(ric).getString(key);
            Log.i("MessagingServiceTuGA sound", sound);
            return sound;
        } catch (JSONException e) {
            Log.e("MessagingService", "Error parsing JSON from ricalarmton", e);
            return null;
        }
    }

    // Benutzer muss Lokal criticalAlert gesetzt haben + die Nachricht muss criticalalert=1 enthalten
    private boolean isCriticalAlarm(String messageCriticalAlert) {
        SharedPreferences sharedPreferences = getSharedPreferences("it.tuga.fireteam", Context.MODE_PRIVATE);
        return Objects.equals(sharedPreferences.getString("criticalalert", "0"), "1") && Objects.equals(messageCriticalAlert, "1");
    }

    private static boolean isSilentSound(String sound) {
        return SOUND_SILENT.equalsIgnoreCase(sound.trim());
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static void createSilentChannel(NotificationManager notificationManager, String channelId, String name, boolean vibration, boolean bypassDnd) {
        if (notificationManager.getNotificationChannel(channelId) == null) {
            NotificationChannel channel = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH);
            // Absolut stumm schalten
            channel.setSound(null, null);
            channel.enableVibration(vibration);
            channel.setBypassDnd(bypassDnd);
            notificationManager.createNotificationChannel(channel);
            Log.i("MessagingService", "Silent Channel erstellt " + channelId);
        }
    }

    /**
     * Zeigt einen Alarm aus einer reinen Datennachricht an. Offline gesendete Notification-Nachrichten fasst Firebase
     * zusammen (nur die letzte kommt an), Datennachrichten nicht. Aufgebaut wird die Benachrichtigung mit dem Builder
     * von Firebase - gleiches Icon und gleiche Farbe wie bisher, Antippen öffnet die App mit den Daten der Nachricht
     * (inkl. google.message_id für pushNotificationActionPerformed).
     */
    private void showAlarmNotification(RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        String ric = data.get("ric");
        String title = data.get("title");
        String body = data.get("body");
        if (ric == null && title == null && body == null) {
            // Datennachricht ohne Alarm
            return;
        }
        String sound = getAlarmSound(ric, data.get("subric"));
        boolean silentNoVibration = sound != null && isSilentSound(sound) && !isCriticalAlarm(data.get("criticalalert"));

        Bundle params = remoteMessage.toIntent().getExtras();
        params.putString("gcm.n.title", title != null ? title : ("Nachricht"));
        params.putString("gcm.n.body", body != null ? body : "Nachricht Body");
        params.putString("gcm.n.android_channel_id", getChannelId(sound, silentNoVibration, data.get("android_channel_id")));
        // Eindeutiger Tag je Nachricht: sonst ersetzen sich Pushes zum selben Einsatz/RIC bzw. ohne Einsatz gegenseitig
        String messageId = remoteMessage.getMessageId();
        params.putString("gcm.n.tag", "push_" + (messageId != null && !messageId.isEmpty() ? messageId : String.valueOf(SystemClock.elapsedRealtimeNanos())));
        // vor Android 8 (ohne Kanäle): Pop-up und Vibration wie beim Kanal
        params.putString("gcm.n.notification_priority", String.valueOf(NotificationCompat.PRIORITY_HIGH));
        if (!silentNoVibration) {
            params.putString("gcm.n.default_vibrate_timings", "1");
        }

        try {
            NotificationParams notificationParams = new NotificationParams(params);
            Bundle metadata = getManifestMetadata();
            String channelId = CommonNotificationBuilder.getOrCreateChannel(this, notificationParams.getNotificationChannelId(), metadata);
            CommonNotificationBuilder.DisplayNotificationInfo notificationInfo =
                CommonNotificationBuilder.createNotificationInfo(this, this, notificationParams, channelId, metadata);
            // Eigene Gruppe je Benachrichtigung, sonst bündelt Android/Samsung mehrere Alarme und zeigt nur den letzten
            notificationInfo.notificationBuilder.setGroup(notificationInfo.tag);
            if (sound != null) {
                // Alarme sind bei Nicht-Stören "Nur wichtige Unterbrechungen" standardmäßig erlaubt
                notificationInfo.notificationBuilder.setCategory(NotificationCompat.CATEGORY_ALARM);
            }
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(notificationInfo.tag, notificationInfo.id, notificationInfo.notificationBuilder.build());
        } catch (RuntimeException e) {
            Log.e("MessagingService", "showing alarm notification failed", e);
        }
    }

    /**
     * Mit eigenem Alarmton (wird selbst abgespielt) ein stummer Kanal, mit Zugriff auf Nicht-Stören einer der dieses umgeht.
     * Ohne eigenen Alarmton der Kanal aus der Nachricht, damit dessen Ton abgespielt wird (null -> Standardkanal von Firebase).
     */
    private String getChannelId(String sound, boolean silentNoVibration, String messageChannelId) {
        if (sound == null) {
            return messageChannelId;
        }
        if (silentNoVibration) {
            return SILENT_NO_VIBRATION_CHANNEL_ID;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null && notificationManager.isNotificationPolicyAccessGranted()) {
                createSilentChannel(notificationManager, ALARM_DND_CHANNEL_ID, "Kritische Alarme", true, true);
                return ALARM_DND_CHANNEL_ID;
            }
        }
        return ALARM_CHANNEL_ID;
    }

    // wie Firebase: meta-data der App (Standard-Icon, -Farbe und -Kanal für Benachrichtigungen)
    private Bundle getManifestMetadata() {
        try {
            ApplicationInfo applicationInfo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? getPackageManager().getApplicationInfo(getPackageName(), PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA))
                : getApplicationInfoLegacy();
            if (applicationInfo.metaData != null) {
                return applicationInfo.metaData;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w("MessagingService", "Couldn't get own application info", e);
        }
        return Bundle.EMPTY;
    }

    @SuppressWarnings("deprecation")
    private ApplicationInfo getApplicationInfoLegacy() throws PackageManager.NameNotFoundException {
        return getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
    }

    private static boolean isDuplicateMessage(Intent intent) {
        String messageId = intent.getStringExtra("google.message_id");
        if (messageId == null) {
            messageId = intent.getStringExtra("message_id");
        }
        if (messageId == null || messageId.isEmpty()) {
            return false;
        }
        synchronized (recentMessageIds) {
            if (recentMessageIds.contains(messageId)) {
                return true;
            }
            if (recentMessageIds.size() >= RECENT_MESSAGE_IDS_MAX) {
                recentMessageIds.removeFirst();
            }
            recentMessageIds.addLast(messageId);
            return false;
        }
    }

    /**
     * Spielt den Alarmton ab und setzt die dafür veränderten Einstellungen danach wieder zurück.
     * Kritischer Alarm: über den Wecker-Stream mit maximaler Lautstärke, ein Nicht-Stören, das den Wecker
     * blockiert, wird aufgehoben. Normaler Alarm: ebenso mit maximaler Lautstärke, sofern das Gerät nicht
     * lautlos/vibrieren ist, kein Nicht-Stören aktiv ist und die Lautstärke nicht auf 0 steht - sonst mit der
     * aktuellen Benachrichtigungslautstärke.
     */
    private void playAlarm(String sound, boolean critical) {
        Uri soundUri = getSoundUri(sound);
        int durationMs = getSoundFileDuration(soundUri);
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // Ab Android 15 kann eine App mit targetSdk >= 35 das Nicht-Stören des Benutzers nicht mehr über
        // setInterruptionFilter aufheben, der Aufruf schaltet dann nur noch eine eigene Nicht-Stören-Regel der App
        boolean canChangeGlobalDnd = Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
            || getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.VANILLA_ICE_CREAM;
        Vibrator vibrator = getSystemService(Vibrator.class);
        boolean hasVibrator = vibrator != null && vibrator.hasVibrator();
        Ringtone newRingtone = RingtoneManager.getRingtone(getApplicationContext(), soundUri);
        if (newRingtone == null) {
            Log.e("MessagingService", "Ringtone not available " + soundUri);
            return;
        }

        synchronized (LOCK) {
            if (!critical && session != null && session.critical && ringtone != null && ringtone.isPlaying()) {
                // Ein laufender kritischer Alarm wird nicht durch einen normalen Alarm unterbrochen
                Log.i("MessagingService", "Critical alarm still playing - normal alarm sound skipped");
                newRingtone.stop();
                return;
            }

            // Ein noch laufender Alarm wird ersetzt. Die Session bleibt bestehen, damit am Ende der Zustand von vor
            // dem ersten Alarm zurückgesetzt wird und nicht die bereits erhöhten Werte.
            MAIN_HANDLER.removeCallbacksAndMessages(null);
            stopRingtoneLocked();
            if (session == null) {
                session = new AlarmSession(audioManager, notificationManager, getContentResolver(), canChangeGlobalDnd, hasVibrator);
            } else if (!critical && session.critical) {
                // Der kritische Ton ist schon zu Ende: dessen Änderungen gelten nicht für den normalen Alarm
                session.undoChanges();
            }
            session.critical = critical;
            try {
                if (critical) {
                    session.raiseForCriticalAlarm();
                }
                boolean useAlarmStream = critical || session.raiseForNormalAlarm();

                ringtone = newRingtone;
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(useAlarmStream ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION)
                    .build());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.setVolume(1.0f);
                }
                ringtone.play();

                int generation = ++alarmGeneration;
                MAIN_HANDLER.postDelayed(() -> finishAlarm(generation), durationMs + SOUND_END_BUFFER_MS);
            } catch (RuntimeException e) {
                // sonst blieben die Einstellungen verändert, ohne dass ein Zurücksetzen geplant ist
                Log.e("MessagingService", "playing alarm failed", e);
                if (ringtone != newRingtone) {
                    newRingtone.stop();
                }
                finishAlarmLocked();
            }
        }
    }

    /** Stoppt den Alarmton und setzt die für den Alarm veränderten Einstellungen sofort zurück. */
    public static void stopRingtone() {
        // ein Alarm, der erst während des Wartens auf den Lock gestartet wird, bleibt bestehen
        finishAlarm(alarmGeneration);
    }

    private static void finishAlarm(int generation) {
        synchronized (LOCK) {
            if (generation == alarmGeneration) {
                finishAlarmLocked();
            }
        }
    }

    private static void finishAlarmLocked() {
        MAIN_HANDLER.removeCallbacksAndMessages(null);
        stopRingtoneLocked();
        if (session != null) {
            session.restore();
            session = null;
        }
    }

    private static void stopRingtoneLocked() {
        if (ringtone != null) {
            ringtone.stop();
            ringtone = null;
        }
    }

    public int getSoundFileDuration(Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            int duration = durationStr != null ? Integer.parseInt(durationStr) : 0;
            return duration > 0 ? duration : DEFAULT_SOUND_DURATION_MS;
        } catch (Exception ex) {
            return DEFAULT_SOUND_DURATION_MS;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
                // nichts zu tun
            }
        }
    }

    public Uri getSoundUri(String sound) {
        if (sound != null && !sound.trim().isEmpty()) {
            // Ressourcennamen sind klein geschrieben und ohne Dateiendung (z.B. "Sirene.mp3" -> "sirene")
            String name = sound.trim().toLowerCase(Locale.ROOT);
            if (name.contains(".")) {
                name = name.substring(0, name.lastIndexOf('.'));
            }
            int soundId = getResources().getIdentifier(name, "raw", getPackageName());
            if (soundId != 0) {
                return Uri.parse("android.resource://" + getPackageName() + "/" + soundId);
            }
            Log.w("MessagingService", "Sound " + sound + " not found - using default notification sound");
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        // Notification-Nachrichten zeigt Firebase bzw. im Vordergrund das Plugin an, Datennachrichten die App selbst
        if (remoteMessage.getNotification() == null) {
            showAlarmNotification(remoteMessage);
        }
        PushNotificationsPlugin.sendRemoteMessage(remoteMessage);
    }

    @Override
    public void onNewToken(@NonNull String s) {
        super.onNewToken(s);
        PushNotificationsPlugin.onNewToken(s);
    }

    /**
     * Merkt sich die Einstellungen von vor dem ersten Alarm und welche davon verändert wurden. Treffen mehrere
     * Alarme kurz hintereinander ein, wird erst nach dem letzten und nur das Veränderte zurückgesetzt.
     * Der Ton läuft über USAGE_ALARM: den Wecker-Stream schaltet der Klingelmodus (lautlos/vibrieren) nicht stumm,
     * nur ein Nicht-Stören, das Wecker blockiert. Der Klingelmodus wird deshalb nur verändert, wenn es nicht anders
     * geht - lautlos lässt sich per setRingerMode nicht wiederherstellen, ohne dabei Nicht-Stören einzuschalten.
     */
    private static final class AlarmSession {
        private final AudioManager audioManager;
        private final NotificationManager notificationManager;
        private final boolean canChangeGlobalDnd;
        // ohne Vibrationsmotor macht Android aus vibrieren lautlos
        private final boolean hasVibrator;
        private final int originalInterruptionFilter;
        // eigentlicher Klingelmodus des Benutzers, auch wenn Nicht-Stören aktiv ist
        private final int originalRingerMode;
        // Ein stumm geschalteter Stream liefert 0 statt der eingestellten Lautstärke, die echte ist erst danach lesbar
        private int originalAlarmVolume;
        private boolean originalAlarmVolumeKnown;
        private boolean alarmVolumeChanged;
        private boolean interruptionFilterChanged;
        private boolean ringerModeChanged;
        private boolean dndLiftedViaRingerMode;
        boolean critical;

        AlarmSession(AudioManager audioManager, NotificationManager notificationManager, ContentResolver contentResolver,
                     boolean canChangeGlobalDnd, boolean hasVibrator) {
            this.audioManager = audioManager;
            this.notificationManager = notificationManager;
            this.canChangeGlobalDnd = canChangeGlobalDnd;
            this.hasVibrator = hasVibrator;
            originalInterruptionFilter = getInterruptionFilter();
            if (audioManager != null) {
                int ringerMode = readUserRingerMode(contentResolver, originalInterruptionFilter);
                if (ringerMode == -1) {
                    ringerMode = audioManager.getRingerMode();
                    // When DND mode is enabled, we get ringerMode as silent even though actual ringer mode is Normal
                    if (isDnd(originalInterruptionFilter) && ringerMode == AudioManager.RINGER_MODE_SILENT
                        && audioManager.getStreamVolume(AudioManager.STREAM_RING) != 0) {
                        ringerMode = AudioManager.RINGER_MODE_NORMAL;
                    }
                }
                originalRingerMode = ringerMode;
                // bei Xiaomi Geräten wird STREAM_ALARM statt STREAM_RING verwendet
                originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                originalAlarmVolumeKnown = !audioManager.isStreamMute(AudioManager.STREAM_ALARM);
            } else {
                originalRingerMode = -1;
            }
            Log.i("MessagingService", "original interruptionFilter " + originalInterruptionFilter + " ringerMode " + originalRingerMode
                + " alarm " + (originalAlarmVolumeKnown ? String.valueOf(originalAlarmVolume) : "muted"));
        }

        /**
         * getRingerMode liefert bei aktivem Nicht-Stören lautlos. Der eigentliche Klingelmodus steht in MODE_RINGER,
         * bei "Keine Unterbrechungen"/"Nur Wecker" erzwingt Android dort lautlos und merkt sich den vorherigen in
         * zen_mode_ringer_level. @return -1, wenn nicht lesbar
         */
        private static int readUserRingerMode(ContentResolver contentResolver, int interruptionFilter) {
            try {
                int ringerMode = Settings.Global.getInt(contentResolver, Settings.Global.MODE_RINGER, -1);
                if (ringerMode == AudioManager.RINGER_MODE_SILENT && (interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
                    || interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS)) {
                    ringerMode = Settings.Global.getInt(contentResolver, "zen_mode_ringer_level", AudioManager.RINGER_MODE_NORMAL);
                }
                return ringerMode;
            } catch (Exception e) {
                Log.e("MessagingService", "ringer mode setting not readable", e);
                return -1;
            }
        }

        // Ab Android 15 lässt sich lautlos nicht setzen, ohne dabei Nicht-Stören einzuschalten - stattdessen vibrieren
        private int withoutSilent(int ringerMode) {
            if (ringerMode == AudioManager.RINGER_MODE_NORMAL || !hasVibrator) {
                return AudioManager.RINGER_MODE_NORMAL;
            }
            return AudioManager.RINGER_MODE_VIBRATE;
        }

        private int getInterruptionFilter() {
            return notificationManager != null
                ? notificationManager.getCurrentInterruptionFilter()
                : NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }

        private static boolean isDnd(int interruptionFilter) {
            return interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                && interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }

        // Wecker blockiert: Stream stumm, Nicht-Stören "Keine Unterbrechungen" oder "Priorität" ohne Wecker
        private boolean isAlarmBlocked(int interruptionFilter) {
            if (audioManager.isStreamMute(AudioManager.STREAM_ALARM) || interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
                return true;
            }
            if (interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && notificationManager != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    return (notificationManager.getConsolidatedNotificationPolicy().priorityCategories
                        & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0;
                } catch (Exception e) {
                    Log.e("MessagingService", "notification policy not readable", e);
                }
            }
            return false;
        }

        void raiseForCriticalAlarm() {
            if (audioManager == null) {
                return;
            }
            int interruptionFilter = getInterruptionFilter();
            if (canChangeGlobalDnd && isDnd(interruptionFilter)) {
                // Samsung Geräte benötigen setInterruptionFilter=INTERRUPTION_FILTER_ALL
                try {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                    interruptionFilterChanged = true;
                } catch (Exception e) {
                    Log.e("MessagingService", "setInterruptionFilter fehler", e);
                }
                waitUntilAlarmUnmuted();
            } else if (!canChangeGlobalDnd && isDnd(interruptionFilter) && isAlarmBlocked(interruptionFilter)) {
                // Ab Android 15 hebt nur noch setRingerMode (normal/vibrieren) Nicht-Stören auf. Nur wenn es den Wecker
                // blockiert, denn wiederherstellen lässt es sich danach nicht (setInterruptionFilter würde nur eine
                // eigene, dauerhaft aktive Nicht-Stören-Regel der App einschalten). Mit dem eigentlichen Klingelmodus,
                // damit dieser gleich bleibt - lautlos wird dabei zu vibrieren.
                int ringerMode = withoutSilent(originalRingerMode);
                if (ringerMode != originalRingerMode) {
                    Log.w("MessagingService", "ringerMode " + originalRingerMode + " set to " + ringerMode + " to lift DND");
                }
                try {
                    audioManager.setRingerMode(ringerMode);
                } catch (Exception e) {
                    Log.e("MessagingService", "ringerMode " + ringerMode + " not set", e);
                }
                dndLiftedViaRingerMode = !isDnd(getInterruptionFilter());
                waitUntilAlarmUnmuted();
            }
            // Nur falls der Wecker trotzdem stumm ist (einzelne Hersteller schalten ihn im Lautlos-Modus stumm)
            // als letzte Möglichkeit Klingelmodus normal
            if (isAlarmBlocked(getInterruptionFilter()) && audioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
                try {
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    ringerModeChanged = true;
                } catch (Exception e) {
                    Log.e("MessagingService", "RINGER_MODE_NORMAL not set", e);
                }
                waitUntilAlarmUnmuted();
            }
            raiseAlarmVolume();
            Log.i("MessagingService", "critical alarm interruptionFilter " + getInterruptionFilter() + " ringerMode " + audioManager.getRingerMode()
                + " alarm " + audioManager.getStreamVolume(AudioManager.STREAM_ALARM));
        }

        /** @return true, wenn der Ton mit maximaler Lautstärke über den Wecker-Stream abgespielt werden soll */
        boolean raiseForNormalAlarm() {
            if (audioManager == null) {
                return false;
            }
            // Nur wenn das Gerät vor und bei dem Alarm weder lautlos noch auf Vibration steht, kein Nicht-Stören aktiv ist,
            // die Lautstärke nicht auf 0 steht und gerade kein Telefonat läuft
            boolean allowed = originalRingerMode == AudioManager.RINGER_MODE_NORMAL
                && !isDnd(originalInterruptionFilter)
                && audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                && !isDnd(getInterruptionFilter())
                && !audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION)
                && audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0
                && !audioManager.isStreamMute(AudioManager.STREAM_ALARM)
                && audioManager.getMode() == AudioManager.MODE_NORMAL;
            if (!allowed) {
                Log.i("MessagingService", "Normal alarm - device silent, vibrate, DND, volume 0 or in call: current volume");
                return false;
            }
            raiseAlarmVolume();
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) < audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)) {
                // Lautstärke ließ sich nicht erhöhen (z.B. Geräterichtlinie) - dann wie bisher mit der Benachrichtigungslautstärke
                Log.w("MessagingService", "Normal alarm - alarm volume could not be raised: current volume");
                return false;
            }
            return true;
        }

        // das System hebt die Stummschaltung nach dem Aufheben von Nicht-Stören teils verzögert auf
        private void waitUntilAlarmUnmuted() {
            if (!interruptionFilterChanged && !ringerModeChanged && !dndLiftedViaRingerMode) {
                return;
            }
            for (int i = 0; i < 10 && audioManager.isStreamMute(AudioManager.STREAM_ALARM); i++) {
                SystemClock.sleep(50);
            }
        }

        private void raiseAlarmVolume() {
            // die echte Lautstärke lesen, bevor sie verändert wird - sonst würde beim Zurücksetzen 0 geschrieben
            if (!originalAlarmVolumeKnown && !audioManager.isStreamMute(AudioManager.STREAM_ALARM)) {
                originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                originalAlarmVolumeKnown = true;
            }
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                alarmVolumeChanged = true;
            } catch (Exception e) {
                Log.e("MessagingService", "max alarm volume not set", e);
            }
        }

        /** Setzt die Änderungen zurück, behält aber den ursprünglichen Zustand für weitere Alarme dieser Session. */
        void undoChanges() {
            restore();
            alarmVolumeChanged = false;
            interruptionFilterChanged = false;
            ringerModeChanged = false;
            dndLiftedViaRingerMode = false;
        }

        /** Reihenfolge: Lautstärke, Klingelmodus, zuletzt Nicht-Stören (setRingerMode kann Nicht-Stören ein- oder ausschalten). */
        void restore() {
            if (audioManager == null) {
                return;
            }
            if (alarmVolumeChanged) {
                if (originalAlarmVolumeKnown) {
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0);
                    } catch (Exception e) {
                        Log.e("MessagingService", "original alarm volume not set", e);
                    }
                } else {
                    Log.w("MessagingService", "original alarm volume unknown - not restored");
                }
            }

            if (ringerModeChanged) {
                int ringerMode = originalRingerMode;
                if (!canChangeGlobalDnd) {
                    // Ab Android 15 würde lautlos Nicht-Stören dauerhaft einschalten, das ließe sich nicht mehr aufheben
                    ringerMode = withoutSilent(originalRingerMode);
                    if (ringerMode != originalRingerMode) {
                        Log.w("MessagingService", "ringerMode " + originalRingerMode + " cannot be restored on Android 15+ - set to " + ringerMode);
                    }
                }
                try {
                    if (audioManager.getRingerMode() != ringerMode) {
                        audioManager.setRingerMode(ringerMode);
                    }
                } catch (Exception e) {
                    Log.e("MessagingService", "originalRingerMode not set", e);
                }
            }

            int interruptionFilter = getInterruptionFilter();
            if (canChangeGlobalDnd) {
                if ((interruptionFilterChanged || ringerModeChanged)
                    && originalInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                    && interruptionFilter != originalInterruptionFilter) {
                    try {
                        notificationManager.setInterruptionFilter(originalInterruptionFilter);
                    } catch (Exception e) {
                        Log.e("MessagingService", "setInterruptionFilter fehler", e);
                    }
                }
            } else if (dndLiftedViaRingerMode) {
                Log.w("MessagingService", "DND was lifted for the alarm and cannot be restored on Android 15+");
            }

            Log.i("MessagingService", "restored interruptionFilter " + getInterruptionFilter() + " ringerMode " + audioManager.getRingerMode()
                + " alarm " + audioManager.getStreamVolume(AudioManager.STREAM_ALARM));
        }
    }
}
