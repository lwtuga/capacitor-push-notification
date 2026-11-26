package com.capacitorjs.plugins.pushnotifications.vollbild;

import android.content.Context;

/**
 * Texte des Vollbild-Alarms in der Sprache der App (Preferences "sprache"). Bewusst keine Android-Ressourcen:
 * die Sprache der App kann von der des Geräts abweichen und bei App Bundles liefert Play nur die Sprachressourcen
 * des Geräts aus.
 */
final class VollbildTexte {
    final String titel;
    final String ichKomme;
    final String verzoegert;
    final String nichtVerfuegbar;
    final String appOeffnen;
    final String schliessen;
    final String wirdGesendet;
    final String gesendet;
    final String fehler;
    /** Trennzeichen im Datum der Alarmierungszeit: 26.11.2026 bzw. 26/11/2026 */
    final String datumTrenner;

    private static final VollbildTexte DE = new VollbildTexte(
        "Neuer Einsatz",
        "Ich komme",
        "Verzögert",
        "Nicht verfügbar",
        "App öffnen",
        "Schließen",
        "Rückmeldung wird gesendet …",
        "Rückmeldung gesendet",
        "Rückmeldung konnte nicht gesendet werden. Bitte erneut versuchen.",
        "."
    );

    private static final VollbildTexte IT = new VollbildTexte(
        "Nuovo intervento",
        "Arrivo",
        "Arrivo in ritardo",
        "Non disponibile",
        "Apri app",
        "Chiudi",
        "Invio della risposta …",
        "Risposta inviata",
        "Impossibile inviare la risposta. Riprovare.",
        "/"
    );

    private VollbildTexte(String titel, String ichKomme, String verzoegert, String nichtVerfuegbar, String appOeffnen,
                          String schliessen, String wirdGesendet, String gesendet, String fehler, String datumTrenner) {
        this.titel = titel;
        this.ichKomme = ichKomme;
        this.verzoegert = verzoegert;
        this.nichtVerfuegbar = nichtVerfuegbar;
        this.appOeffnen = appOeffnen;
        this.schliessen = schliessen;
        this.wirdGesendet = wirdGesendet;
        this.gesendet = gesendet;
        this.fehler = fehler;
        this.datumTrenner = datumTrenner;
    }

    static VollbildTexte fuer(Context context) {
        return "it".equals(VollbildAlarm.getSprache(context)) ? IT : DE;
    }
}
