package com.capacitorjs.plugins.pushnotifications.vollbild;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.RequiresApi;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.capacitorjs.plugins.pushnotifications.MessagingService;
import com.capacitorjs.plugins.pushnotifications.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vollbild-Alarm (auch über dem Sperrbildschirm): oben der Push-Titel, in der Mitte der Push-Text (Hintergrund je
 * nach Push-Art: Einsatz dunkelblau, Übung gelb, sonst grau).
 * Mit Einsatz: Ich komme / Verzögert / Nicht verfügbar. Verzögert zeigt zuerst die Auswahl der Verzögerung
 * (5 / 10 / 15 / 30 / 60 Minuten, Zurück). Jede Auswahl wird an das Backend gesendet, bei Ich komme und Verzögert
 * wird danach die App mit dem Einsatz geöffnet. Ohne Einsatz: App öffnen / Schließen.
 */
public class VollbildAlarmActivity extends Activity {
    private static final String TAG = "VollbildAlarmActivity";

    // Auswahl der Verzögerung, Reihenfolge wie enuEinsatzVerfuegbarkeitVerzoegerung (VollbildTexte.minuten)
    private static final int[] MINUTEN_BUTTONS = {
        R.id.tuga_vollbild_minuten5,
        R.id.tuga_vollbild_minuten10,
        R.id.tuga_vollbild_minuten15,
        R.id.tuga_vollbild_minuten30,
        R.id.tuga_vollbild_minuten60
    };
    private static final int[] MINUTEN_TEXTE = {
        R.id.tuga_vollbild_minuten5_text,
        R.id.tuga_vollbild_minuten10_text,
        R.id.tuga_vollbild_minuten15_text,
        R.id.tuga_vollbild_minuten30_text,
        R.id.tuga_vollbild_minuten60_text
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Bundle daten;
    private String notificationTag;
    private int notificationId;
    private boolean sendet;
    private boolean mitEinsatz;
    // nach "Verzögert": die Auswahl der Verzögerung wird angezeigt
    private boolean verzoegerungAuswahl;
    // ab Android 13 (OnBackInvokedCallback): Zurück in der Auswahl der Verzögerung, als Object wegen älterer Android-Versionen
    private Object zurueckCallback;

    private VollbildTexte texte;
    private TextView zeitView;
    private TextView titelView;
    private View karteView;
    private TextView textView;
    private TextView statusView;
    private View progressView;
    private View buttonsEinsatz;
    private View buttonsVerzoegerung;
    private View buttonsOhneEinsatz;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ueberSperrbildschirmAnzeigen();
        setContentView(R.layout.tuga_vollbild_alarm);
        randAnpassen();

        zeitView = findViewById(R.id.tuga_vollbild_zeit);
        titelView = findViewById(R.id.tuga_vollbild_titel);
        karteView = findViewById(R.id.tuga_vollbild_karte);
        textView = findViewById(R.id.tuga_vollbild_text);
        statusView = findViewById(R.id.tuga_vollbild_status);
        progressView = findViewById(R.id.tuga_vollbild_progress);
        buttonsEinsatz = findViewById(R.id.tuga_vollbild_buttons_einsatz);
        buttonsVerzoegerung = findViewById(R.id.tuga_vollbild_buttons_verzoegerung);
        buttonsOhneEinsatz = findViewById(R.id.tuga_vollbild_buttons_ohne_einsatz);

        texte = VollbildTexte.fuer(this);
        ((TextView) findViewById(R.id.tuga_vollbild_ichkomme_text)).setText(texte.ichKomme);
        ((TextView) findViewById(R.id.tuga_vollbild_verzoegert_text)).setText(texte.verzoegert);
        ((TextView) findViewById(R.id.tuga_vollbild_nichtverfuegbar_text)).setText(texte.nichtVerfuegbar);
        ((TextView) findViewById(R.id.tuga_vollbild_appoeffnen_text)).setText(texte.appOeffnen);
        ((TextView) findViewById(R.id.tuga_vollbild_schliessen_text)).setText(texte.schliessen);
        ((TextView) findViewById(R.id.tuga_vollbild_zurueck_text)).setText(texte.zurueck);

        findViewById(R.id.tuga_vollbild_ichkomme).setOnClickListener(v -> rueckmelden(VollbildAlarm.TYP_ICHKOMME, true, null));
        findViewById(R.id.tuga_vollbild_verzoegert).setOnClickListener(v -> {
            // gesendet wird erst die Auswahl der Verzögerung. Der Alarmton endet, der Benutzer hat reagiert
            MessagingService.stopRingtone();
            verzoegerungAuswahlAnzeigen(true);
        });
        findViewById(R.id.tuga_vollbild_nichtverfuegbar).setOnClickListener(v -> rueckmelden(VollbildAlarm.TYP_NICHTVERFUEGBAR, false, null));
        for (int i = 0; i < MINUTEN_BUTTONS.length; i++) {
            ((TextView) findViewById(MINUTEN_TEXTE[i])).setText(texte.minuten[i]);
            final int verzoegerung = VollbildAlarm.VERZOEGERUNG_MINUTEN5 + i;
            findViewById(MINUTEN_BUTTONS[i]).setOnClickListener(v -> rueckmelden(VollbildAlarm.TYP_VERZOEGERT, true, verzoegerung));
        }
        findViewById(R.id.tuga_vollbild_zurueck).setOnClickListener(v -> verzoegerungAuswahlAnzeigen(false));
        findViewById(R.id.tuga_vollbild_appoeffnen).setOnClickListener(v -> {
            MessagingService.stopRingtone();
            benachrichtigungEntfernen();
            appOeffnen(null, null, false);
        });
        findViewById(R.id.tuga_vollbild_schliessen).setOnClickListener(v -> {
            // Die Benachrichtigung bleibt, über sie lässt sich der Alarm erneut öffnen
            MessagingService.stopRingtone();
            finish();
        });

        anzeigen(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // ein neuer Alarm ersetzt den angezeigten
        anzeigen(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    // bis Android 15 (ab Android 16 über zurueckCallback): Zurück in der Auswahl der Verzögerung zeigt wieder
    // Ich komme / Verzögert / Nicht verfügbar
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (verzoegerungAuswahl) {
            verzoegerungAuswahlAnzeigen(false);
            return;
        }
        super.onBackPressed();
    }

    private void ueberSperrbildschirmAnzeigen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Ab Android 15 immer Edge-to-Edge: Abstand für Status- und Navigationsleiste selbst setzen
    private void randAnpassen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(true);
        insetsController.setAppearanceLightNavigationBars(true);
        View root = findViewById(R.id.tuga_vollbild_root);
        final int paddingLeft = root.getPaddingLeft();
        final int paddingTop = root.getPaddingTop();
        final int paddingRight = root.getPaddingRight();
        final int paddingBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(paddingLeft + insets.left, paddingTop + insets.top, paddingRight + insets.right, paddingBottom + insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void anzeigen(Intent intent) {
        Bundle neueDaten = intent.getBundleExtra(VollbildAlarm.EXTRA_DATEN);
        if (neueDaten == null) {
            Log.w(TAG, "keine Daten");
            finish();
            return;
        }
        daten = neueDaten;
        notificationTag = intent.getStringExtra(VollbildAlarm.EXTRA_NOTIFICATION_TAG);
        notificationId = intent.getIntExtra(VollbildAlarm.EXTRA_NOTIFICATION_ID, 0);
        sendet = false;

        String zeit = alarmzeitText(daten.getString("alarmzeit"));
        zeitView.setText(zeit);
        zeitView.setVisibility(zeit.isEmpty() ? View.GONE : View.VISIBLE);
        String titel = daten.getString("title");
        titelView.setText(titel != null && !titel.isEmpty() ? titel : texte.titel);
        String text = daten.getString("body");
        textView.setText(text != null ? text : "");
        karteFaerben(daten.getString("art"));

        String einsatzId = daten.getString("einsatz");
        mitEinsatz = einsatzId != null && !einsatzId.isEmpty() && !"null".equals(einsatzId);
        verzoegerungAuswahl = false;
        buttonsAnzeigen();
        statusAnzeigen(null, false);
        buttonsAktivieren(true);
    }

    // Karte mit dem Push-Text je nach Push-Art (enuPushArt): Einsatz dunkelblau mit weißer Schrift, Übung gelb mit
    // schwarzer Schrift, alle anderen (Information, Test, Digitale Alarmierung) grau
    private void karteFaerben(String art) {
        String pushArt = art != null ? art.trim() : "";
        int hintergrund = R.drawable.tuga_vollbild_karte;
        int textFarbe = R.color.tuga_vollbild_text;
        if (VollbildAlarm.ART_EINSATZ.equals(pushArt)) {
            hintergrund = R.drawable.tuga_vollbild_karte_einsatz;
            textFarbe = R.color.tuga_vollbild_text_einsatz;
        } else if (VollbildAlarm.ART_UEBUNG.equals(pushArt)) {
            hintergrund = R.drawable.tuga_vollbild_karte_uebung;
            textFarbe = R.color.tuga_vollbild_text_uebung;
        }
        karteView.setBackgroundResource(hintergrund);
        textView.setTextColor(getColor(textFarbe));
    }

    private void verzoegerungAuswahlAnzeigen(boolean anzeigen) {
        if (sendet) {
            // während des Sendens bleibt die Ansicht
            return;
        }
        verzoegerungAuswahl = anzeigen;
        buttonsAnzeigen();
        statusAnzeigen(null, false);
    }

    private void buttonsAnzeigen() {
        buttonsEinsatz.setVisibility(mitEinsatz && !verzoegerungAuswahl ? View.VISIBLE : View.GONE);
        buttonsVerzoegerung.setVisibility(mitEinsatz && verzoegerungAuswahl ? View.VISIBLE : View.GONE);
        buttonsOhneEinsatz.setVisibility(mitEinsatz ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            zurueckAbfangen(mitEinsatz && verzoegerungAuswahl);
        }
    }

    // Ab Android 16 (targetSdk 36) ruft Android onBackPressed nicht mehr auf, Zurück geht dann an den registrierten Callback
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void zurueckAbfangen(boolean abfangen) {
        if (abfangen && zurueckCallback == null) {
            OnBackInvokedCallback callback = () -> verzoegerungAuswahlAnzeigen(false);
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            zurueckCallback = callback;
        } else if (!abfangen && zurueckCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) zurueckCallback);
            zurueckCallback = null;
        }
    }

    // Das Backend sendet "YYYY-MM-DD HH:MM:SS", angezeigt wird "DD.MM.YYYY HH:MM:SS" (italienisch "DD/MM/YYYY HH:MM:SS").
    // Ältere Backends senden keine Zeit: dann bleibt die Zeile ausgeblendet.
    private String alarmzeitText(String alarmzeit) {
        if (alarmzeit == null) {
            return "";
        }
        String zeit = alarmzeit.trim();
        int leerzeichen = zeit.indexOf(' ');
        if (leerzeichen < 0) {
            return zeit;
        }
        String[] datum = zeit.substring(0, leerzeichen).split("-");
        if (datum.length != 3) {
            return zeit;
        }
        return datum[2] + texte.datumTrenner + datum[1] + texte.datumTrenner + datum[0] + " " + zeit.substring(leerzeichen + 1).trim();
    }

    /** verzoegerung: nur bei Verzögert (enuEinsatzVerfuegbarkeitVerzoegerung), sonst null */
    private void rueckmelden(final int typ, final boolean appOeffnen, final Integer verzoegerung) {
        if (sendet) {
            return;
        }
        sendet = true;
        MessagingService.stopRingtone();
        buttonsAktivieren(false);
        statusAnzeigen(texte.wirdGesendet, true);

        final Bundle gesendeteDaten = daten;
        final Context appContext = getApplicationContext();
        executor.execute(() -> {
            final boolean gesendet = VerfuegbarkeitPublisher.senden(appContext, gesendeteDaten, typ, verzoegerung);
            runOnUiThread(() -> nachRueckmeldung(gesendeteDaten, typ, verzoegerung, appOeffnen, gesendet));
        });
    }

    private void nachRueckmeldung(Bundle gesendeteDaten, int typ, Integer verzoegerung, boolean appOeffnen, boolean gesendet) {
        if (isFinishing() || isDestroyed() || gesendeteDaten != daten) {
            // inzwischen geschlossen oder durch einen neuen Alarm ersetzt
            return;
        }
        if (appOeffnen) {
            // auch wenn das Senden fehlgeschlagen ist: die App sendet die Verfügbarkeit (mit Verzögerung) dann selbst
            statusAnzeigen(gesendet ? texte.gesendet : null, false);
            benachrichtigungEntfernen();
            appOeffnen(typ, verzoegerung, gesendet);
            return;
        }
        if (gesendet) {
            Toast.makeText(this, texte.gesendet, Toast.LENGTH_SHORT).show();
            benachrichtigungEntfernen();
            finish();
            return;
        }
        rueckmeldungFehlgeschlagen();
    }

    private void rueckmeldungFehlgeschlagen() {
        sendet = false;
        statusAnzeigen(texte.fehler, false);
        buttonsAktivieren(true);
    }

    /**
     * Öffnet die App wie beim Antippen der Benachrichtigung (pushNotificationActionPerformed mit den Push-Daten).
     * Ist das Gerät gesperrt, muss es zuerst entsperrt werden.
     */
    private void appOeffnen(final Integer typ, final Integer verzoegerung, final boolean gesendet) {
        final Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent == null) {
            finish();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Bundle extras = new Bundle(daten);
        if (typ != null) {
            extras.putString(VollbildAlarm.DATEN_TYP, String.valueOf(typ));
            extras.putString(VollbildAlarm.DATEN_GESENDET, String.valueOf(gesendet));
        }
        if (verzoegerung != null) {
            extras.putString(VollbildAlarm.DATEN_VERZOEGERUNG, String.valueOf(verzoegerung));
        }
        launchIntent.putExtras(extras);

        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            keyguardManager.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    appStarten(launchIntent);
                }

                @Override
                public void onDismissError() {
                    // Android zeigt dann selbst die Entsperrung, bevor die App sichtbar wird
                    appStarten(launchIntent);
                }

                @Override
                public void onDismissCancelled() {
                    // nicht entsperrt: die Rückmeldung ist (falls gesendet) erfolgt, die App wird nicht geöffnet
                    if (typ == null || gesendet) {
                        if (typ != null) {
                            Toast.makeText(VollbildAlarmActivity.this, texte.gesendet, Toast.LENGTH_SHORT).show();
                        }
                        finish();
                    } else {
                        rueckmeldungFehlgeschlagen();
                    }
                }
            });
        } else {
            // vor Android 8 verlangt Android die Entsperrung selbst, da die App nicht über dem Sperrbildschirm angezeigt wird
            appStarten(launchIntent);
        }
    }

    private void appStarten(Intent launchIntent) {
        try {
            startActivity(launchIntent);
        } catch (RuntimeException e) {
            Log.e(TAG, "App konnte nicht geöffnet werden", e);
        }
        finish();
    }

    private void benachrichtigungEntfernen() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && notificationTag != null) {
            notificationManager.cancel(notificationTag, notificationId);
        }
    }

    private void statusAnzeigen(String text, boolean progress) {
        statusView.setText(text);
        statusView.setVisibility(text != null ? View.VISIBLE : View.GONE);
        progressView.setVisibility(progress ? View.VISIBLE : View.GONE);
    }

    private void buttonsAktivieren(boolean aktiv) {
        int[] ids = {
            R.id.tuga_vollbild_ichkomme,
            R.id.tuga_vollbild_verzoegert,
            R.id.tuga_vollbild_nichtverfuegbar,
            R.id.tuga_vollbild_zurueck,
            R.id.tuga_vollbild_appoeffnen,
            R.id.tuga_vollbild_schliessen
        };
        for (int id : ids) {
            buttonAktivieren(findViewById(id), aktiv);
        }
        for (int id : MINUTEN_BUTTONS) {
            buttonAktivieren(findViewById(id), aktiv);
        }
    }

    private static void buttonAktivieren(View button, boolean aktiv) {
        button.setEnabled(aktiv);
        button.setAlpha(aktiv ? 1f : 0.5f);
    }
}
