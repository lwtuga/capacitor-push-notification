package com.capacitorjs.plugins.pushnotifications.vollbild;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Sendet die Verfügbarkeit ohne Login der App an PUT /einsaetze/einsatzverfuegbarkeitpush/{einsatz_id}.
 * Das Backend ermittelt den Benutzer über die pushdevice_id (FCM-Token dieses Geräts).
 * Nicht im UI-Thread aufrufen.
 */
final class VerfuegbarkeitPublisher {
    private static final String TAG = "VerfuegbarkeitPublisher";
    private static final int TIMEOUT_MS = 8000;
    private static final int TOKEN_TIMEOUT_S = 5;

    private VerfuegbarkeitPublisher() {}

    /** verzoegerung: nur bei Verzögert (enuEinsatzVerfuegbarkeitVerzoegerung), sonst null */
    static boolean senden(Context context, Bundle daten, int typ, Integer verzoegerung) {
        String einsatzId = daten != null ? daten.getString("einsatz") : null;
        if (einsatzId == null || einsatzId.isEmpty()) {
            Log.w(TAG, "keine einsatz_id");
            return false;
        }
        String restUrl = VollbildAlarm.getRestUrl(context, daten.getString("db"));
        if (restUrl == null) {
            Log.w(TAG, "keine REST-URL gespeichert");
            return false;
        }

        HttpURLConnection connection = null;
        try {
            String pushdeviceId = Tasks.await(FirebaseMessaging.getInstance().getToken(), TOKEN_TIMEOUT_S, TimeUnit.SECONDS);
            if (pushdeviceId == null || pushdeviceId.isEmpty()) {
                Log.w(TAG, "kein FCM-Token");
                return false;
            }

            JSONObject in = new JSONObject();
            in.put("id", einsatzId);
            in.put("pushdevice_id", pushdeviceId);
            in.put("typ", typ);
            if (verzoegerung != null) {
                in.put("verzoegerung", verzoegerung.intValue());
            }
            JSONObject body = new JSONObject();
            body.put("data", in);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(restUrl + "/einsaetze/einsatzverfuegbarkeitpush/" + URLEncoder.encode(einsatzId, "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("locale", VollbildAlarm.getSprache(context));
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }

            int responseCode = connection.getResponseCode();
            Log.i(TAG, "einsatzverfuegbarkeitpush typ " + typ + " verzoegerung " + verzoegerung + " response " + responseCode);
            return responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_NO_CONTENT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "einsatzverfuegbarkeitpush unterbrochen", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "einsatzverfuegbarkeitpush fehlgeschlagen", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
