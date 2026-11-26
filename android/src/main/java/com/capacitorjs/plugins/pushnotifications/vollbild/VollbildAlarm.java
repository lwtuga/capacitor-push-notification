package com.capacitorjs.plugins.pushnotifications.vollbild;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.messaging.RemoteMessage;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Vollbild-Alarm: bei jedem Alarm (Subric A und C) eines RIC, für den in der App "Vollbild" eingeschaltet ist, wird zusätzlich zur
 * Benachrichtigung die {@link VollbildAlarmActivity} angezeigt.
 * - Gerät gesperrt bzw. Bildschirm aus: über den Full-Screen-Intent der Benachrichtigung (ab Android 14 nur mit
 *   der Berechtigung "Vollbild-Benachrichtigungen")
 * - Gerät entsperrt und in Verwendung: Android zeigt dann nur eine Heads-up-Benachrichtigung, deshalb wird die
 *   Activity direkt gestartet. Aus dem Hintergrund erlaubt Android das nur mit "Über anderen Apps einblenden".
 * Die Einstellungen schreibt die App über Capacitor Preferences (Gruppe it.tuga.fireteam):
 * ricalarmton {ric: {alarmtona, alarmtonc, vollbild}}, resturls {db: url}, restdb, sprache.
 */
public final class VollbildAlarm {
    static final String PREFS = "it.tuga.fireteam";
    private static final String TAG = "VollbildAlarm";

    // Push-Daten (alle Werte als String) inkl. google.message_id, damit die App beim Öffnen
    // pushNotificationActionPerformed wie beim Antippen der Benachrichtigung erhält
    static final String EXTRA_DATEN = "vollbild_daten";
    static final String EXTRA_NOTIFICATION_TAG = "vollbild_notification_tag";
    static final String EXTRA_NOTIFICATION_ID = "vollbild_notification_id";
    // gewählte Verfügbarkeit, wird beim Öffnen der App mitgegeben. gesendet=false: die App sendet sie selbst nach
    static final String DATEN_TYP = "vollbild_typ";
    static final String DATEN_GESENDET = "vollbild_gesendet";

    // enuEinsatzVerfuegbarkeitTyp
    static final int TYP_ICHKOMME = 1;
    static final int TYP_VERZOEGERT = 2;
    static final int TYP_NICHTVERFUEGBAR = 3;

    private VollbildAlarm() {}

    /** Vollbild, wenn es für diesen RIC eingeschaltet ist (Subric A und C). */
    public static boolean isVollbild(Context context, String ric) {
        if (ric == null) {
            return false;
        }
        try {
            JSONObject ricKonfig = getRicAlarmton(context).optJSONObject(ric);
            return ricKonfig != null && ricKonfig.optBoolean("vollbild", false);
        } catch (JSONException e) {
            Log.e(TAG, "ricalarmton nicht lesbar", e);
            return false;
        }
    }

    /** true, wenn für mindestens einen RIC Vollbild eingeschaltet ist (dann werden die Berechtigungen benötigt). */
    public static boolean isRequired(Context context) {
        try {
            JSONObject ricalarmton = getRicAlarmton(context);
            Iterator<String> rics = ricalarmton.keys();
            while (rics.hasNext()) {
                JSONObject ricKonfig = ricalarmton.optJSONObject(rics.next());
                if (ricKonfig != null && ricKonfig.optBoolean("vollbild", false)) {
                    return true;
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "ricalarmton nicht lesbar", e);
        }
        return false;
    }

    private static JSONObject getRicAlarmton(Context context) throws JSONException {
        String ricalarmton = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ricalarmton", "");
        return ricalarmton == null || ricalarmton.isEmpty() ? new JSONObject() : new JSONObject(ricalarmton);
    }

    /** Ab Android 14 muss der Benutzer Vollbild-Benachrichtigungen erlauben (außer Telefon- und Wecker-Apps). */
    public static boolean canUseFullScreenIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true;
        }
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        return notificationManager == null || notificationManager.canUseFullScreenIntent();
    }

    public static Intent createIntent(Context context, RemoteMessage remoteMessage, String notificationTag, int notificationId) {
        Bundle daten = new Bundle();
        for (Map.Entry<String, String> entry : remoteMessage.getData().entrySet()) {
            daten.putString(entry.getKey(), entry.getValue());
        }
        String messageId = remoteMessage.getMessageId();
        daten.putString("google.message_id", messageId != null && !messageId.isEmpty() ? messageId : "vollbild_" + SystemClock.elapsedRealtimeNanos());

        Intent intent = new Intent(context, VollbildAlarmActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.putExtra(EXTRA_DATEN, daten);
        intent.putExtra(EXTRA_NOTIFICATION_TAG, notificationTag);
        intent.putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        return intent;
    }

    /** requestCode je Benachrichtigung, sonst würden sich die Extras mehrerer Alarme überschreiben. */
    public static PendingIntent createPendingIntent(Context context, Intent intent, String notificationTag) {
        return PendingIntent.getActivity(context, notificationTag != null ? notificationTag.hashCode() : 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Startet die Activity direkt, wenn Android das erlaubt: App im Vordergrund oder "Über anderen Apps einblenden".
     * Ist das Gerät gesperrt, übernimmt das der Full-Screen-Intent der Benachrichtigung. Starten beide, kommt der
     * zweite Start wegen singleTask als onNewIntent mit denselben Daten an.
     */
    public static void startDirekt(Context context, Intent intent) {
        boolean overlay = Settings.canDrawOverlays(context);
        boolean vordergrund = isAppImVordergrund();
        if (!overlay && !vordergrund) {
            Log.i(TAG, "Activity nicht direkt gestartet (kein Overlay, App im Hintergrund) - nur Full-Screen-Intent");
            return;
        }
        try {
            context.startActivity(intent);
            Log.i(TAG, "Activity direkt gestartet overlay=" + overlay + " vordergrund=" + vordergrund);
        } catch (RuntimeException e) {
            Log.e(TAG, "Activity konnte nicht gestartet werden", e);
        }
    }

    private static boolean isAppImVordergrund() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /** Sprache der App (de/it), ohne gespeicherte Sprache die des Geräts */
    static String getSprache(Context context) {
        String sprache = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sprache", null);
        if (sprache == null || sprache.isEmpty()) {
            sprache = Locale.getDefault().getLanguage().equals("it") ? "it" : "de";
        }
        return sprache;
    }

    /** REST-URL des Backends, von dem der Push kommt (Push-Feld db), sonst die der aktuellen DB der App. */
    static String getRestUrl(Context context, String db) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String resturls = prefs.getString("resturls", "");
            if (resturls == null || resturls.isEmpty()) {
                return null;
            }
            JSONObject urls = new JSONObject(resturls);
            String url = db != null && !db.isEmpty() ? urls.optString(db, "") : "";
            if (url.isEmpty()) {
                url = urls.optString(prefs.getString("restdb", ""), "");
            }
            if (url.isEmpty()) {
                return null;
            }
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        } catch (JSONException e) {
            Log.e(TAG, "resturls nicht lesbar", e);
            return null;
        }
    }
}
