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
 * Vollbild-Alarm (auch über dem Sperrbildschirm): oben der Push-Titel, in der Mitte der Push-Text.
 * Mit Einsatz: Ich komme / Verzögert / Nicht verfügbar - jede Auswahl wird an das Backend gesendet, bei Ich komme
 * und Verzögert wird danach die App mit dem Einsatz geöffnet. Ohne Einsatz: App öffnen / Schließen.
 */
public class VollbildAlarmActivity extends Activity {
    private static final String TAG = "VollbildAlarmActivity";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Bundle daten;
    private String notificationTag;
    private int notificationId;
    private boolean sendet;

    private VollbildTexte texte;
    private TextView zeitView;
    private TextView titelView;
    private TextView textView;
    private TextView statusView;
    private View progressView;
    private View buttonsEinsatz;
    private View buttonsOhneEinsatz;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ueberSperrbildschirmAnzeigen();
        setContentView(R.layout.tuga_vollbild_alarm);
        randAnpassen();

        zeitView = findViewById(R.id.tuga_vollbild_zeit);
        titelView = findViewById(R.id.tuga_vollbild_titel);
        textView = findViewById(R.id.tuga_vollbild_text);
        statusView = findViewById(R.id.tuga_vollbild_status);
        progressView = findViewById(R.id.tuga_vollbild_progress);
        buttonsEinsatz = findViewById(R.id.tuga_vollbild_buttons_einsatz);
        buttonsOhneEinsatz = findViewById(R.id.tuga_vollbild_buttons_ohne_einsatz);

        texte = VollbildTexte.fuer(this);
        ((TextView) findViewById(R.id.tuga_vollbild_ichkomme_text)).setText(texte.ichKomme);
        ((TextView) findViewById(R.id.tuga_vollbild_verzoegert_text)).setText(texte.verzoegert);
        ((TextView) findViewById(R.id.tuga_vollbild_nichtverfuegbar_text)).setText(texte.nichtVerfuegbar);
        ((TextView) findViewById(R.id.tuga_vollbild_appoeffnen_text)).setText(texte.appOeffnen);
        ((TextView) findViewById(R.id.tuga_vollbild_schliessen_text)).setText(texte.schliessen);

        findViewById(R.id.tuga_vollbild_ichkomme).setOnClickListener(v -> rueckmelden(VollbildAlarm.TYP_ICHKOMME, true));
        findViewById(R.id.tuga_vollbild_verzoegert).setOnClickListener(v -> rueckmelden(VollbildAlarm.TYP_VERZOEGERT, true));
        findViewById(R.id.tuga_vollbild_nichtverfuegbar).setOnClickListener(v -> rueckmelden(VollbildAlarm.TYP_NICHTVERFUEGBAR, false));
        findViewById(R.id.tuga_vollbild_appoeffnen).setOnClickListener(v -> {
            MessagingService.stopRingtone();
            benachrichtigungEntfernen();
            appOeffnen(null, false);
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

        String einsatzId = daten.getString("einsatz");
        boolean mitEinsatz = einsatzId != null && !einsatzId.isEmpty() && !"null".equals(einsatzId);
        buttonsEinsatz.setVisibility(mitEinsatz ? View.VISIBLE : View.GONE);
        buttonsOhneEinsatz.setVisibility(mitEinsatz ? View.GONE : View.VISIBLE);
        statusAnzeigen(null, false);
        buttonsAktivieren(true);
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

    private void rueckmelden(final int typ, final boolean appOeffnen) {
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
            final boolean gesendet = VerfuegbarkeitPublisher.senden(appContext, gesendeteDaten, typ);
            runOnUiThread(() -> nachRueckmeldung(gesendeteDaten, typ, appOeffnen, gesendet));
        });
    }

    private void nachRueckmeldung(Bundle gesendeteDaten, int typ, boolean appOeffnen, boolean gesendet) {
        if (isFinishing() || isDestroyed() || gesendeteDaten != daten) {
            // inzwischen geschlossen oder durch einen neuen Alarm ersetzt
            return;
        }
        if (appOeffnen) {
            // auch wenn das Senden fehlgeschlagen ist: die App sendet die Verfügbarkeit dann selbst
            statusAnzeigen(gesendet ? texte.gesendet : null, false);
            benachrichtigungEntfernen();
            appOeffnen(typ, gesendet);
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
    private void appOeffnen(final Integer typ, final boolean gesendet) {
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
            R.id.tuga_vollbild_appoeffnen,
            R.id.tuga_vollbild_schliessen
        };
        for (int id : ids) {
            View button = findViewById(id);
            button.setEnabled(aktiv);
            button.setAlpha(aktiv ? 1f : 0.5f);
        }
    }
}
