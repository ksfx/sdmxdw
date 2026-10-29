"""Builds the SNB EKE (Einzelkrediterhebung) logical data model v5 (31.07.2026) in SDMxDW.

Source: "EKE - Logisches Datenmodell - v5" (eke-logical-datamodel.de.pdf). The 16 entities with a
primary key become microdata flows; foreign keys become reference fields. XOR subtypes are
flattened into their base entity: every subtype attribute carries the subtype name and the
classification rule (REG-xxxx) that selects it, plus the obligation as published (M, M*, O).
Only base-entity "M" fields are enforced on ingest - subtype obligations depend on the REG rules
documented in the EKE Erläuterungen.

Value lists are only created where the model itself defines the values (Kundentyp, Sicherheitstyp);
all other codes are specified in the Erläuterungen and are left uncoded for now.

Usage: python build_eke_v5.py   (expects CS_EKE / category EKE to exist; loads demo data 2026-Q3)
"""

import os
import json
import re
import subprocess
import sys

import requests

BASE = "http://localhost:8080"
AGENCY, VERSION, PERIOD = "SNB", "5.0.0", "2026-Q3"
DESCRIPTION = "EKE - Logisches Datenmodell v5 (31.07.2026)"
s = requests.Session()

S, N, D, B = "STRING", "NUMBER", "DATE", "BOOLEAN"


def F(code, label, ftype=S, obligation="O", subtype=None, rule=None, codelist=None, facet=None):
    return dict(code=code, label=label, type=ftype, obligation=obligation, subtype=subtype,
                rule=rule, codelist=codelist, facet=facet)


def REF(code, label, target, obligation="M", facet=None):
    return F(code, label, "REF:" + target, obligation, facet=facet)


ENTITIES = [
    ("GEGENPARTEI", "Gegenpartei", [
        F("BANKINTERNES_RATING", "Bankinternes Rating", obligation="M", facet="facet1"),
        F("PROBABILITY_OF_DEFAULT", "Probability of Default", N),
        F("DATUM_LETZTE_RISIKOBEURTEILUNG", "Datum letzte Risikobeurteilung", D, "M",
          "Gegenpartei mit Rating", "REG-0034 (Bankinternes Rating)"),
    ]),
    ("KUNDE", "Kunde", [
        F("KUNDENTYP", "Kundentyp", obligation="M", codelist="CL_EKE_KUNDENTYP", facet="facet1"),
        F("KUNDENDOMIZIL_LAND", "Kundendomizil - Land", obligation="M", facet="facet2"),
        F("KUNDENDOMIZIL_KANTON", "Kundendomizil - Kanton", obligation="M",
          subtype="Kunde Schweiz", rule="REG-0016 (Kundendomizil - Land)"),
        F("SENSITIVER_KUNDE", "Sensitiver Kunde", B, "M", "Privatkunde", "REG-0017 (Kundentyp)"),
        F("PRIVATKUNDETYP", "Privatkundetyp", S, "M", "Nicht-sensitiver Privatkunde",
          "REG-0018 (Sensitiver Kunde)"),
        *[F(c, l, t, "O", "Nicht-sensitiver Privatkunde", "REG-0018 (Sensitiver Kunde)") for c, l, t in [
            ("WEITERES_EINKOMMEN", "Weiteres Einkommen", N), ("MIETEINNAHMEN", "Mieteinnahmen", N),
            ("VERMOEGENSERTRAG", "Vermögensertrag", N), ("EXTERNE_KREDITKOSTEN", "Externe Kreditkosten", N),
            ("WEITERE_VERPFLICHTUNGEN", "Weitere Verpflichtungen", N),
            ("VERMOEGEN_BANKINTERN", "Vermögen nach bankinterner Definition", N),
            ("QUELLE_VERMOEGEN", "Quelle Vermögen", S), ("DATUM_ERHEBUNG_VERMOEGEN", "Datum Erhebung Vermögen", D)]],
        F("GEBURTSJAHR", "Geburtsjahr", N, "M", "Einzel- oder Partner-Privatkunde", "REG-0019 (Privatkundetyp)"),
        *[F(c, l, t, "M", "Privatkunde mit Hypothek \"selbstgenutzt\" (sonst optional)",
            "REG-0020 / REG-0021 (verbunden mit Instrument \"selbstgenutzte\" Hypothek)") for c, l, t in [
            ("DATUM_ERHEBUNG_EINKOMMEN", "Datum Erhebung Einkommen", D),
            ("BRUTTO_NETTO_EINKOMMEN", "Brutto- oder Netto-Einkommenskomponenten", S),
            ("EINKOMMEN_BANKINTERN", "Einkommen nach bankinterner Definition", N),
            ("FIXE_LOHNKOMPONENTE", "Fixe Lohnkomponente aus (un-)selbständiger Arbeit", N),
            ("VARIABLE_LOHNKOMPONENTE", "Variable Lohnkomponente aus (un-)selbständiger Arbeit", N),
            ("RENTENEINKOMMEN", "Renteneinkommen", N)]],
        F("FIRMENKUNDETYP", "Firmenkundetyp", S, "M", "Firmenkunde", "REG-0017 (Kundentyp)", facet="facet3"),
        F("NOGA", "NOGA", S, "M", "Firmenkunde", "REG-0017 (Kundentyp)"),
        F("ANZAHL_MITARBEITER", "Anzahl Mitarbeiter", N, "M", "Firmenkunde", "REG-0017 (Kundentyp)"),
        *[F(c, l, t, "O", "Firmenkunde", "REG-0017 (Kundentyp)") for c, l, t in [
            ("FINANZABSCHLUSSART", "Finanzabschlussart", S), ("DATUM_FINANZABSCHLUSS", "Datum Finanzabschluss", D),
            ("UMSATZ", "Umsatz", N), ("GEWINN_VERLUST", "Gewinn / Verlust", N),
            ("BILANZSUMME", "Bilanzsumme", N), ("FREMDKAPITAL", "Fremdkapital", N)]],
        F("RECHTLICHER_NAME", "Rechtlicher Name", S, "M", "Juristischer Firmenkunde ohne KMU-Meldeerleichterung",
          "REG-0023 (Firmenkundetyp & KMU-Meldeerleichterung)"),
        F("ULTIMATE_PARENT", "Ultimate Parent", S, "O", "Juristischer Firmenkunde ohne KMU-Meldeerleichterung",
          "REG-0023 (Firmenkundetyp & KMU-Meldeerleichterung)"),
        F("VERBUNDENE_GEGENPARTEIEN", "Verbundene Gegenparteien", S, "O",
          "Juristischer Firmenkunde ohne KMU-Meldeerleichterung", "REG-0023 (Firmenkundetyp & KMU-Meldeerleichterung)"),
        F("UID", "UID", S, "M", "Firmenkunde ohne Meldeerleichterung - Schweiz (Ausland: optional)",
          "REG-0024 / REG-0025 (Kundendomizil - Land)"),
        F("LEI", "LEI", S, "O", "Firmenkunde ohne Meldeerleichterung - Ausland", "REG-0025 (Kundendomizil - Land)"),
    ]),
    ("GEGENPARTEI_KUNDE", "Gegenpartei - Kunde", [
        REF("GEGENPARTEI", "Gegenpartei ID", "GEGENPARTEI", facet="facet1"),
        REF("KUNDE", "Kunde ID", "KUNDE", facet="facet2"),
        F("SOLIDARHAFTUNG", "Solidarhaftung", B, "M"),
    ]),
    ("PORTFOLIO_WERTBERICHTIGUNGEN", "Portfolio Wertberichtigungen", [
        F("PAUSCHALIERTE_EWB_GEFAEHRDET", "Pauschalierte Einzelwertberichtigungen auf gefährdeten Forderungen", N, "M"),
        F("PORTFOLIO_WB_NICHT_GEFAEHRDET", "Portfolio Wertberichtigungen auf nicht gefährdeten Forderungen", N, "M"),
    ]),
    ("PORTFOLIO_RUECKSTELLUNGEN", "Portfolio Rückstellungen", [
        F("PORTFOLIO_RUECKSTELLUNGEN_AUSSERBILANZ", "Portfolio Rückstellungen Ausserbilanzgeschäfte", N, "M"),
    ]),
    ("RAHMEN", "Rahmen", [
        REF("GEGENPARTEI", "Gegenpartei ID", "GEGENPARTEI", facet="facet1"),
        F("RAHMEN_LIMITE_VERFUEGBAR", "Rahmen-Limite, verfügbar", N, "M*"),
        F("RAHMEN_LIMITE_VEREINBART", "Rahmen-Limite, vereinbart", N, "M*"),
        F("RAHMEN_LIMITE_MAXIMALBETRAG", "Rahmen-Limite, kommunizierter Maximalbetrag", N, "M*"),
    ]),
    ("INSTRUMENT", "Instrument", [
        REF("GEGENPARTEI", "Gegenpartei ID", "GEGENPARTEI", facet="facet3"),
        REF("RAHMEN", "Rahmen ID", "RAHMEN"),
        REF("PORTFOLIO_WERTBERICHTIGUNGEN", "Portfolio Wertberichtigungen ID", "PORTFOLIO_WERTBERICHTIGUNGEN", "O"),
        REF("PORTFOLIO_RUECKSTELLUNGEN", "Portfolio Rückstellungen ID", "PORTFOLIO_RUECKSTELLUNGEN", "O"),
        F("EINHEITLICHER_INSTRUMENTTYP", "Einheitlicher Instrumenttyp", obligation="M", facet="facet1"),
        F("INTERNE_PRODUKTBEZEICHNUNG", "Interne Produktbezeichnung", obligation="M"),
        F("INSTRUMENT_LIMITE_VERFUEGBAR", "Instrument-Limite, verfügbar", N, "M*"),
        F("INSTRUMENT_LIMITE_VEREINBART", "Instrument-Limite, vereinbart", N, "M*"),
        F("INSTRUMENT_LIMITE_MAXIMALBETRAG", "Instrument-Limite, kommunizierter Maximalbetrag", N, "M*"),
        F("WAEHRUNG_INSTRUMENT", "Währung Instrument", obligation="M", facet="facet2"),
        F("BETRAG_HYPOTHEKARFORDERUNGEN", "Betrag unter Hypothekarforderungen", N, "M"),
        F("BETRAG_FORDERUNGEN_KUNDEN", "Betrag unter Forderungen gegenüber Kunden", N, "M"),
        F("BETRAG_AUSSERBILANZ", "Betrag Ausserbilanz", N, "M"),
        F("BENUETZUNG", "Benützung", N, "M"),
        F("KREDIT_BELASTUNG", "Kredit Belastung", N, "M"),
        F("ZAHLUNGSVERZUEGE", "Zahlungsverzüge", N, "M"),
        F("KREDITVERGEBENDE_BANKSTELLE", "Kreditvergebende Bankstelle", obligation="M"),
        F("KONSORTIALKREDIT", "Konsortialkredit", B, "M"),
        F("ERSTES_VERFUEGBARKEITSDATUM", "Erstes Verfügbarkeitsdatum", D, "M"),
        F("DATUM_FAELLIGKEIT", "Datum Fälligkeit", D, "M"),
        F("KKG", "KKG", B, "M"),
        F("AMORTISATION_JAEHRLICH", "Jährlicher regelmässiger Amortisationsbetrag", N, "M"),
        F("AMORTISATION_UNREGELMAESSIG", "Unregelmässiger Amortisationsbetrag", N, "M"),
        F("AMORTISATION_12_MONATE", "Amortisationsbetrag in den nächsten 12 Monaten", N, "M"),
        F("GEFAEHRDETE_FORDERUNG", "Gefährdete Forderung", B, "M", facet="facet4"),
        F("UEBERFAELLIGE_FORDERUNG", "Überfällige Forderung", B, "M"),
        F("ZINSPRODUKT", "Zinsprodukt", obligation="M"),
        F("LETZTES_ZINSFESTSETZUNGSDATUM", "Letztes Zinsfestsetzungsdatum", D, "M", "Instrument mit Zinsprodukt", "REG-0012 (Zinsprodukt)"),
        F("ENDDATUM_ZINSBINDUNG", "Enddatum Zinsbindung", D, "M", "Instrument mit Zinsprodukt", "REG-0012 (Zinsprodukt)"),
        F("ZINS", "Zins", N, "M", "FVZ-/VVZ-/BAZ-Zinsprodukt (FVZ: M, VVZ: M*, BAZ: optional)",
          "REG-0013 / REG-0014 / REG-0033 (Zinsprodukt)"),
        F("MARGE", "Marge", N, "M", "BAZ-Zinsprodukt", "REG-0013 (Zinsprodukt)"),
        F("BASISZINSSATZ_NAME", "Basiszinssatz Name", S, "M", "BAZ-Zinsprodukt", "REG-0013 (Zinsprodukt)"),
        F("ETP", "ETP", N, "M", "Hypothek", "REG-0008 (Einheitlicher Instrumenttyp)"),
        F("VERLETZUNG_SBVG_MINDESTANFORDERUNG", "Verletzung Mindestanforderung der SBVg Selbstregulierung", B, "O",
          "Hypothek", "REG-0008 (Einheitlicher Instrumenttyp)"),
        F("KOMMISSIONSSATZ", "Kommissionssatz", N, "M", "Instrument mit Kommission", "REG-0007 (Kommissionssatz)"),
        F("KOMMISSIONSBASIS", "Kommissionsbasis", S, "M", "Instrument mit Kommission", "REG-0007 (Kommissionssatz)"),
        F("KUENDIGUNGSFRIST_BANK", "Kündigungsfrist in Tagen (Bank)", N, "M",
          "Instrument ohne Fälligkeit (mit Fälligkeit: optional)", "REG-0015 (Datum Fälligkeit)"),
        F("KUENDIGUNGSFRIST_KUNDE", "Kündigungsfrist in Tagen (Kunden)", N, "M",
          "Instrument ohne Fälligkeit (mit Fälligkeit: optional)", "REG-0015 (Datum Fälligkeit)"),
        F("RUECKSTELLUNG_AUSSERBILANZ", "Rückstellung Ausserbilanzgeschäfte", N, "M",
          "Instrument ohne Portfolio Rückstellungen", "REG-0011 (Portfolio Rückstellungen ID)"),
        F("EWB_GEFAEHRDET", "Einzelwertberichtigung auf gefährdeten Forderungen", N, "M",
          "Gefährdete Forderung ohne Portfolio Wertberichtigung", "REG-0009 (Gefährdete Forderung & Portfolio Wertberichtigungen ID)"),
        F("WB_NICHT_GEFAEHRDET", "Wertberichtigung auf nicht gefährdeten Forderungen", N, "M",
          "Nicht-gefährdete Forderung ohne Portfolio Wertberichtigung", "REG-0010 (Gefährdete Forderung & Portfolio Wertberichtigungen ID)"),
    ]),
    ("SICHERHEIT", "Sicherheit", [
        F("SICHERHEITSTYP", "Sicherheitstyp", obligation="M", codelist="CL_EKE_SICHERHEITSTYP", facet="facet1"),
        F("MARKTWERT_SICHERHEIT", "Marktwert Sicherheit", N, "M"),
        F("WERT_NACH_HAIRCUTS_BANK", "Gegenwärtiger Wert der erhaltenen Sicherheit nach Haircuts gemäss Bank", N, "M",
          "Wertschriftenportfolio / Andere Sicherheit", "Sicherheitstyp"),
        F("WERT_NACH_HAIRCUTS_ERV", "Gegenwärtiger Wert der erhaltenen Sicherheit nach Haircuts gemäss ERV", N, "M",
          "Wertschriftenportfolio (Andere Sicherheit: optional)", "Sicherheitstyp"),
        F("DATUM_BESTIMMUNG_MARKTWERT", "Datum Bestimmung Marktwert", D, "M", "Andere Sicherheit", "Sicherheitstyp"),
        F("WAEHRUNG_SICHERHEIT", "Währung Sicherheit", S, "M", "Andere Sicherheit", "Sicherheitstyp", facet="facet2"),
    ]),
    ("RAHMEN_SICHERHEIT", "Rahmen - Sicherheit", [
        REF("RAHMEN", "Rahmen ID", "RAHMEN", facet="facet1"),
        REF("SICHERHEIT", "Sicherheit ID", "SICHERHEIT", facet="facet2"),
        F("ZUGEWIESENER_WERT", "Zugewiesener Wert der Sicherheit", N, "M"),
    ]),
    ("GEGENPARTEI_SICHERHEIT", "Gegenpartei - Sicherheit", [
        REF("GEGENPARTEI", "Gegenpartei ID", "GEGENPARTEI", facet="facet1"),
        REF("SICHERHEIT", "Sicherheit ID", "SICHERHEIT", facet="facet2"),
    ]),
    ("GRUNDPFANDOBJEKT", "Grundpfandobjekt", [
        F("OBJEKT_ART", "Objekt Art", obligation="M", facet="facet1"),
        F("OBJEKT_GEMISCHT_GENUTZT", "Objekt gemischt genutzt", B, "M"),
        F("LAND_DES_OBJEKTS", "Land des Objekts", obligation="M", facet="facet2"),
        F("NUTZUNG_DES_OBJEKTS", "Nutzung des Objekts", obligation="M", facet="facet3"),
        F("STATUS_DES_OBJEKTS", "Status des Objekts", obligation="M"),
        F("BELEHNUNGSWERT", "Belehnungswert", N, "M"),
        F("MARKTWERT_GRUNDPFANDOBJEKT", "Marktwert Grundpfandobjekt", N, "M"),
        F("KAUFPREIS", "Kaufpreis", N),
        F("WERT_HEDONISCHE_SCHAETZUNG", "Wert aus hedonischer Schätzung", N),
        F("DATUM_BEWERTUNG", "Datum Bewertung", D, "M"),
        F("DATUM_KAUFPREIS", "Datum Kaufpreis", D),
        F("FREIHANDTRANSAKTION", "Freihandtransaktion", B),
        F("ANLAGEKOSTEN", "Anlagekosten", N),
        F("NETTOMIETZINSEN_IST", "Nettomietzinsen (Ist)", N),
        F("DATUM_NETTOMIETZINSEN_IST", "Datum Nettomietzinsen (Ist)", D),
        F("PLZ_DES_OBJEKTS", "PLZ des Objekts", S, "M", "Grundpfandobjekt Inland von nicht sensitivem Kunden",
          "REG-0031 (Land des Objekts & Sensitiver Kunde)"),
        F("NETTOMIETZINSEN_SOLL", "Nettomietzinsen (Soll)", N, "M", "Vermietungsobjekt (sonst optional)",
          "REG-0032 (Nutzung des Objekts)"),
        F("DATUM_NETTOMIETZINSEN_SOLL", "Datum Nettomietzinsen (Soll)", D, "M", "Vermietungsobjekt (sonst optional)",
          "REG-0032 (Nutzung des Objekts)"),
    ]),
    ("SICHERHEIT_GRUNDPFANDOBJEKT", "Sicherheit - Grundpfandobjekt", [
        REF("SICHERHEIT", "Sicherheit ID", "SICHERHEIT", facet="facet1"),
        REF("GRUNDPFANDOBJEKT", "Grundpfandobjekt ID", "GRUNDPFANDOBJEKT", facet="facet2"),
        F("RANG", "Rang", N, "M"),
        F("VORRANG", "Vorrang", N, "M"),
        F("NEBENRANG", "Nebenrang", N, "M"),
    ]),
    ("WERTSCHRIFT", "Wertschrift", [
        REF("SICHERHEIT", "Sicherheit ID", "SICHERHEIT", facet="facet2"),
        F("WERTSCHRIFTENTYP", "Wertschriftentyp", obligation="M", facet="facet1"),
        F("MARKTWERT_WERTSCHRIFT", "Marktwert Wertschrift", N, "M"),
        F("WAEHRUNG_WERTSCHRIFT", "Währung Wertschrift", obligation="M", facet="facet3"),
        F("ISIN", "ISIN"),
    ]),
    ("EIGENMITTEL", "Eigenmittel", [
        REF("RAHMEN", "Rahmen ID", "RAHMEN", facet="facet1"),
        F("SA_BIZ_MELDEKATEGORIE", "SA-BIZ Meldekategorie", obligation="M", facet="facet2"),
        F("SA_BIZ_EXPOSURE_PRE_CCF", "SA-BIZ Exposure gemäss Standardansatz (pre-CCF, pre-CRM)", N, "M"),
        F("SA_BIZ_EXPOSURE_POST_CCF", "SA-BIZ Exposure (post-CCF, pre-CRM)", N, "M"),
        F("SA_BIZ_SICHERHEITEN_EINFACH", "SA-BIZ Sicherheiten einfacher Ansatz", N, "M"),
        F("SA_BIZ_SICHERHEITEN_UMFASSEND", "SA-BIZ Sicherheiten umfassender Ansatz", N, "M"),
        F("SA_BIZ_VOLLSTAENDIG_ANGEPASSTE_POSITION", "SA-BIZ Vollständig angepasste Position", N, "M"),
        F("SA_BIZ_TOTAL_RWA", "SA-BIZ Total RWA", N, "M"),
        F("SA_BIZ_LTV", "SA-BIZ LTV für Eigenmittelregulierung", N, "M", "SA-BIZ Position mit Grundpfand",
          "REG-0026 (SA-BIZ Meldekategorie)"),
        F("IRB_MELDEKATEGORIE", "IRB Meldekategorie", S, "M", "IRB Position", "REG-0027 (IRB Meldekategorie)", facet="facet3"),
        *[F(c, l, N, "M", "IRB Position", "REG-0027 (IRB Meldekategorie)") for c, l in [
            ("IRB_EAD", "IRB EAD zur RWA Berechnung"),
            ("IRB_LGD", "IRB LGD zur RWA Berechnung vor Input Floor"),
            ("IRB_PD", "IRB PD zur RWA Berechnung vor Input Floor"),
            ("IRB_EXPECTED_LOSS", "IRB Expected Loss"), ("IRB_TOTAL_RWA", "IRB Total RWA")]],
    ]),
    ("ABSCHREIBUNG", "Abschreibung", [
        REF("GEGENPARTEI", "Gegenpartei ID", "GEGENPARTEI", "O", facet="facet1"),
        REF("INSTRUMENT", "Instrument ID", "INSTRUMENT", "O", facet="facet2"),
        F("ID_ABGESCHRIEBENES_INSTRUMENT", "ID abgeschriebenes Instrument"),
        F("ID_ABGESCHRIEBENE_GEGENPARTEI", "ID abgeschriebene Gegenpartei"),
        F("ABSCHREIBUNGSBETRAG", "Abschreibungsbetrag", N, "M"),
    ]),
    ("AGGREGIERTE_PRODUKTE", "Aggregierte Produkte", [
        F("EINHEITLICHER_INSTRUMENTTYP", "Einheitlicher Instrumenttyp", obligation="M", facet="facet1"),
        F("TOTAL_BENUETZUNG", "Total Benützung", N, "M"),
        F("KUNDENTYP", "Kundentyp", S, "M", "Nicht-Korrekturbuchung", "REG-0035 (Einheitlicher Instrumenttyp)",
          codelist="CL_EKE_KUNDENTYP", facet="facet2"),
        F("AUSLANDSKREDITE", "Auslandskredite", B, "M", "Nicht-Korrekturbuchung", "REG-0035 (Einheitlicher Instrumenttyp)"),
        F("KKG", "KKG", B, "M", "Nicht-Korrekturbuchung", "REG-0035 (Einheitlicher Instrumenttyp)"),
        *[F(c, l, N, "M", "Nicht-Korrekturbuchung", "REG-0035 (Einheitlicher Instrumenttyp)") for c, l in [
            ("ANZAHL_KREDITE", "Anzahl Kredite"), ("TOTAL_LIMITE", "Total Limite"),
            ("TOTAL_GEFAEHRDETE_FORDERUNGEN", "Total Gefährdete Forderungen"),
            ("TOTAL_UEBERFAELLIGE_FORDERUNGEN", "Total Überfällige Forderungen"),
            ("TOTAL_EWB_GEFAEHRDET", "Total Einzelwertberichtigungen auf gefährdeten Forderungen"),
            ("TOTAL_WB_NICHT_GEFAEHRDET", "Total Wertberichtigungen auf nicht gefährdeten Forderungen"),
            ("TOTAL_ABSCHREIBUNGSBETRAG", "Total Abschreibungsbetrag")]],
    ]),
]

CODELISTS = {
    "CL_EKE_KUNDENTYP": ("Kundentyp", [("PRIVATKUNDE", "Privatkunde"), ("FIRMENKUNDE", "Firmenkunde")]),
    "CL_EKE_SICHERHEITSTYP": ("Sicherheitstyp", [
        ("SCHULDBRIEF", "Schuldbrief"), ("WERTSCHRIFTENPORTFOLIO", "Wertschriftenportfolio"),
        ("ANDERE_SICHERHEIT", "Andere Sicherheit")]),
}

# Demo reporting (synthetic): one SME, a private couple with an owner-occupied mortgage and a
# foreign corporate with an impaired current account - in dependency order (references are checked).
SAMPLE = [
    ("GEGENPARTEI", [
        ("GP-0001", dict(BANKINTERNES_RATING="R4", PROBABILITY_OF_DEFAULT="0.006", DATUM_LETZTE_RISIKOBEURTEILUNG="2026-03-31")),
        ("GP-0002", dict(BANKINTERNES_RATING="R2", PROBABILITY_OF_DEFAULT="0.002", DATUM_LETZTE_RISIKOBEURTEILUNG="2026-05-15")),
        ("GP-0003", dict(BANKINTERNES_RATING="R9", PROBABILITY_OF_DEFAULT="0.21", DATUM_LETZTE_RISIKOBEURTEILUNG="2026-08-31"))]),
    ("KUNDE", [
        ("K-0001", dict(KUNDENTYP="FIRMENKUNDE", KUNDENDOMIZIL_LAND="CH", KUNDENDOMIZIL_KANTON="ZH", FIRMENKUNDETYP="Juristische Person",
                        NOGA="41", ANZAHL_MITARBEITER="48", FINANZABSCHLUSSART="OR", DATUM_FINANZABSCHLUSS="2025-12-31",
                        UMSATZ="18400000", GEWINN_VERLUST="920000", BILANZSUMME="11200000", FREMDKAPITAL="6100000",
                        RECHTLICHER_NAME="Muster Bau AG", UID="CHE-123.456.789")),
        ("K-0002", dict(KUNDENTYP="PRIVATKUNDE", KUNDENDOMIZIL_LAND="CH", KUNDENDOMIZIL_KANTON="BE", SENSITIVER_KUNDE="false",
                        PRIVATKUNDETYP="Einzel-Privatkunde", GEBURTSJAHR="1981", VERMOEGEN_BANKINTERN="240000",
                        DATUM_ERHEBUNG_EINKOMMEN="2025-11-20", BRUTTO_NETTO_EINKOMMEN="Brutto", EINKOMMEN_BANKINTERN="132000",
                        FIXE_LOHNKOMPONENTE="124000", VARIABLE_LOHNKOMPONENTE="8000", RENTENEINKOMMEN="0")),
        ("K-0003", dict(KUNDENTYP="PRIVATKUNDE", KUNDENDOMIZIL_LAND="CH", KUNDENDOMIZIL_KANTON="BE", SENSITIVER_KUNDE="false",
                        PRIVATKUNDETYP="Partner-Privatkunde", GEBURTSJAHR="1983",
                        DATUM_ERHEBUNG_EINKOMMEN="2025-11-20", BRUTTO_NETTO_EINKOMMEN="Brutto", EINKOMMEN_BANKINTERN="78000",
                        FIXE_LOHNKOMPONENTE="78000", VARIABLE_LOHNKOMPONENTE="0", RENTENEINKOMMEN="0")),
        ("K-0004", dict(KUNDENTYP="FIRMENKUNDE", KUNDENDOMIZIL_LAND="DE", FIRMENKUNDETYP="Juristische Person", NOGA="56",
                        ANZAHL_MITARBEITER="12", RECHTLICHER_NAME="Gasthaus Seeblick GmbH", LEI="529900SEEBLICKGMBH01"))]),
    ("GEGENPARTEI_KUNDE", [
        ("GPK-0001", dict(GEGENPARTEI="GP-0001", KUNDE="K-0001", SOLIDARHAFTUNG="false")),
        ("GPK-0002", dict(GEGENPARTEI="GP-0002", KUNDE="K-0002", SOLIDARHAFTUNG="true")),
        ("GPK-0003", dict(GEGENPARTEI="GP-0002", KUNDE="K-0003", SOLIDARHAFTUNG="true")),
        ("GPK-0004", dict(GEGENPARTEI="GP-0003", KUNDE="K-0004", SOLIDARHAFTUNG="false"))]),
    ("PORTFOLIO_WERTBERICHTIGUNGEN", [
        ("PWB-0001", dict(PAUSCHALIERTE_EWB_GEFAEHRDET="1250000", PORTFOLIO_WB_NICHT_GEFAEHRDET="3400000"))]),
    ("PORTFOLIO_RUECKSTELLUNGEN", [("PRS-0001", dict(PORTFOLIO_RUECKSTELLUNGEN_AUSSERBILANZ="410000"))]),
    ("RAHMEN", [
        ("R-0001", dict(GEGENPARTEI="GP-0001", RAHMEN_LIMITE_VERFUEGBAR="1500000", RAHMEN_LIMITE_VEREINBART="1500000")),
        ("R-0002", dict(GEGENPARTEI="GP-0002", RAHMEN_LIMITE_VERFUEGBAR="780000", RAHMEN_LIMITE_VEREINBART="780000")),
        ("R-0003", dict(GEGENPARTEI="GP-0003", RAHMEN_LIMITE_VERFUEGBAR="0", RAHMEN_LIMITE_VEREINBART="150000"))]),
    ("INSTRUMENT", [
        ("I-0001", dict(GEGENPARTEI="GP-0001", RAHMEN="R-0001", PORTFOLIO_WERTBERICHTIGUNGEN="PWB-0001", PORTFOLIO_RUECKSTELLUNGEN="PRS-0001",
                        EINHEITLICHER_INSTRUMENTTYP="Betriebskredit", INTERNE_PRODUKTBEZEICHNUNG="KMU Kontokorrent",
                        INSTRUMENT_LIMITE_VERFUEGBAR="1500000", INSTRUMENT_LIMITE_VEREINBART="1500000", WAEHRUNG_INSTRUMENT="CHF",
                        BETRAG_HYPOTHEKARFORDERUNGEN="0", BETRAG_FORDERUNGEN_KUNDEN="640000", BETRAG_AUSSERBILANZ="860000",
                        BENUETZUNG="640000", KREDIT_BELASTUNG="640000", ZAHLUNGSVERZUEGE="0", KREDITVERGEBENDE_BANKSTELLE="Zürich",
                        KONSORTIALKREDIT="false", ERSTES_VERFUEGBARKEITSDATUM="2022-04-01", DATUM_FAELLIGKEIT="2027-03-31", KKG="false",
                        AMORTISATION_JAEHRLICH="0", AMORTISATION_UNREGELMAESSIG="0", AMORTISATION_12_MONATE="0",
                        GEFAEHRDETE_FORDERUNG="false", UEBERFAELLIGE_FORDERUNG="false", ZINSPRODUKT="BAZ",
                        LETZTES_ZINSFESTSETZUNGSDATUM="2026-07-01", ENDDATUM_ZINSBINDUNG="2026-09-30", MARGE="0.0175",
                        BASISZINSSATZ_NAME="SARON 3M compounded", KOMMISSIONSSATZ="0.0025", KOMMISSIONSBASIS="Limite")),
        ("I-0002", dict(GEGENPARTEI="GP-0002", RAHMEN="R-0002", PORTFOLIO_WERTBERICHTIGUNGEN="PWB-0001", PORTFOLIO_RUECKSTELLUNGEN="PRS-0001",
                        EINHEITLICHER_INSTRUMENTTYP="Hypothek selbstgenutzt", INTERNE_PRODUKTBEZEICHNUNG="Festhypothek 10 Jahre",
                        INSTRUMENT_LIMITE_VERFUEGBAR="780000", INSTRUMENT_LIMITE_VEREINBART="780000", WAEHRUNG_INSTRUMENT="CHF",
                        BETRAG_HYPOTHEKARFORDERUNGEN="780000", BETRAG_FORDERUNGEN_KUNDEN="0", BETRAG_AUSSERBILANZ="0",
                        BENUETZUNG="780000", KREDIT_BELASTUNG="780000", ZAHLUNGSVERZUEGE="0", KREDITVERGEBENDE_BANKSTELLE="Bern",
                        KONSORTIALKREDIT="false", ERSTES_VERFUEGBARKEITSDATUM="2021-06-30", DATUM_FAELLIGKEIT="2031-06-30", KKG="false",
                        AMORTISATION_JAEHRLICH="10000", AMORTISATION_UNREGELMAESSIG="0", AMORTISATION_12_MONATE="10000",
                        GEFAEHRDETE_FORDERUNG="false", UEBERFAELLIGE_FORDERUNG="false", ZINSPRODUKT="FVZ",
                        LETZTES_ZINSFESTSETZUNGSDATUM="2021-06-30", ENDDATUM_ZINSBINDUNG="2031-06-30", ZINS="0.0105",
                        ETP="0.31", VERLETZUNG_SBVG_MINDESTANFORDERUNG="false")),
        ("I-0003", dict(GEGENPARTEI="GP-0003", RAHMEN="R-0003", EINHEITLICHER_INSTRUMENTTYP="Kontokorrentkredit",
                        INTERNE_PRODUKTBEZEICHNUNG="Kontokorrent Gewerbe", INSTRUMENT_LIMITE_VERFUEGBAR="0",
                        INSTRUMENT_LIMITE_VEREINBART="150000", WAEHRUNG_INSTRUMENT="EUR", BETRAG_HYPOTHEKARFORDERUNGEN="0",
                        BETRAG_FORDERUNGEN_KUNDEN="138000", BETRAG_AUSSERBILANZ="0", BENUETZUNG="138000", KREDIT_BELASTUNG="152000",
                        ZAHLUNGSVERZUEGE="14000", KREDITVERGEBENDE_BANKSTELLE="Basel", KONSORTIALKREDIT="false",
                        ERSTES_VERFUEGBARKEITSDATUM="2019-02-01", DATUM_FAELLIGKEIT="9999-12-31", KKG="false",
                        AMORTISATION_JAEHRLICH="0", AMORTISATION_UNREGELMAESSIG="0", AMORTISATION_12_MONATE="0",
                        GEFAEHRDETE_FORDERUNG="true", UEBERFAELLIGE_FORDERUNG="true", ZINSPRODUKT="VVZ",
                        LETZTES_ZINSFESTSETZUNGSDATUM="2026-01-01", ENDDATUM_ZINSBINDUNG="2026-12-31", ZINS="0.0675",
                        KUENDIGUNGSFRIST_BANK="30", KUENDIGUNGSFRIST_KUNDE="30", RUECKSTELLUNG_AUSSERBILANZ="0",
                        EWB_GEFAEHRDET="62000"))]),
    ("SICHERHEIT", [
        ("S-0001", dict(SICHERHEITSTYP="SCHULDBRIEF", MARKTWERT_SICHERHEIT="1100000")),
        ("S-0002", dict(SICHERHEITSTYP="WERTSCHRIFTENPORTFOLIO", MARKTWERT_SICHERHEIT="820000",
                        WERT_NACH_HAIRCUTS_BANK="560000", WERT_NACH_HAIRCUTS_ERV="530000")),
        ("S-0003", dict(SICHERHEITSTYP="ANDERE_SICHERHEIT", MARKTWERT_SICHERHEIT="50000", WERT_NACH_HAIRCUTS_BANK="50000",
                        DATUM_BESTIMMUNG_MARKTWERT="2026-09-30", WAEHRUNG_SICHERHEIT="EUR"))]),
    ("RAHMEN_SICHERHEIT", [
        ("RS-0001", dict(RAHMEN="R-0001", SICHERHEIT="S-0002", ZUGEWIESENER_WERT="530000")),
        ("RS-0002", dict(RAHMEN="R-0002", SICHERHEIT="S-0001", ZUGEWIESENER_WERT="780000")),
        ("RS-0003", dict(RAHMEN="R-0003", SICHERHEIT="S-0003", ZUGEWIESENER_WERT="50000"))]),
    ("GEGENPARTEI_SICHERHEIT", [("GPS-0001", dict(GEGENPARTEI="GP-0003", SICHERHEIT="S-0003"))]),
    ("GRUNDPFANDOBJEKT", [
        ("G-0001", dict(OBJEKT_ART="Einfamilienhaus", OBJEKT_GEMISCHT_GENUTZT="false", LAND_DES_OBJEKTS="CH",
                        NUTZUNG_DES_OBJEKTS="Selbstgenutzt", STATUS_DES_OBJEKTS="Bestehend", BELEHNUNGSWERT="1150000",
                        MARKTWERT_GRUNDPFANDOBJEKT="1250000", KAUFPREIS="1180000", WERT_HEDONISCHE_SCHAETZUNG="1240000",
                        DATUM_BEWERTUNG="2026-02-28", DATUM_KAUFPREIS="2021-05-20", FREIHANDTRANSAKTION="true",
                        PLZ_DES_OBJEKTS="3072"))]),
    ("SICHERHEIT_GRUNDPFANDOBJEKT", [
        ("SG-0001", dict(SICHERHEIT="S-0001", GRUNDPFANDOBJEKT="G-0001", RANG="1", VORRANG="0", NEBENRANG="0"))]),
    ("WERTSCHRIFT", [
        ("W-0001", dict(SICHERHEIT="S-0002", WERTSCHRIFTENTYP="Aktie", MARKTWERT_WERTSCHRIFT="420000", WAEHRUNG_WERTSCHRIFT="CHF")),
        ("W-0002", dict(SICHERHEIT="S-0002", WERTSCHRIFTENTYP="Obligation", MARKTWERT_WERTSCHRIFT="400000", WAEHRUNG_WERTSCHRIFT="CHF"))]),
    ("EIGENMITTEL", [
        ("E-0001", dict(RAHMEN="R-0001", SA_BIZ_MELDEKATEGORIE="Unternehmen", SA_BIZ_EXPOSURE_PRE_CCF="1500000",
                        SA_BIZ_EXPOSURE_POST_CCF="1070000", SA_BIZ_SICHERHEITEN_EINFACH="0", SA_BIZ_SICHERHEITEN_UMFASSEND="530000",
                        SA_BIZ_VOLLSTAENDIG_ANGEPASSTE_POSITION="540000", SA_BIZ_TOTAL_RWA="540000")),
        ("E-0002", dict(RAHMEN="R-0002", SA_BIZ_MELDEKATEGORIE="Wohnliegenschaften", SA_BIZ_EXPOSURE_PRE_CCF="780000",
                        SA_BIZ_EXPOSURE_POST_CCF="780000", SA_BIZ_SICHERHEITEN_EINFACH="0", SA_BIZ_SICHERHEITEN_UMFASSEND="0",
                        SA_BIZ_VOLLSTAENDIG_ANGEPASSTE_POSITION="780000", SA_BIZ_TOTAL_RWA="234000", SA_BIZ_LTV="0.62"))]),
    ("ABSCHREIBUNG", [("A-0001", dict(GEGENPARTEI="GP-0003", INSTRUMENT="I-0003", ABSCHREIBUNGSBETRAG="8000"))]),
    ("AGGREGIERTE_PRODUKTE", [
        ("AP-0001", dict(EINHEITLICHER_INSTRUMENTTYP="Kreditkarten", TOTAL_BENUETZUNG="2350000")),
        ("AP-0002", dict(EINHEITLICHER_INSTRUMENTTYP="Nicht-Korrekturbuchung", TOTAL_BENUETZUNG="410000", KUNDENTYP="PRIVATKUNDE",
                         AUSLANDSKREDITE="false", KKG="true", ANZAHL_KREDITE="37", TOTAL_LIMITE="520000",
                         TOTAL_GEFAEHRDETE_FORDERUNGEN="12000", TOTAL_UEBERFAELLIGE_FORDERUNGEN="15000",
                         TOTAL_EWB_GEFAEHRDET="6000", TOTAL_WB_NICHT_GEFAEHRDET="4100", TOTAL_ABSCHREIBUNGSBETRAG="2500"))]),
]


def csrf():
    return re.search(r'name="_csrf" value="([^"]+)"', s.get(BASE + "/microdata").text).group(1)


def post(path, data):
    r = s.post(BASE + path, data=dict(data, _csrf=csrf()), allow_redirects=False)
    if r.status_code not in (302, 303):
        sys.exit(f"POST {path} failed: {r.status_code} {r.text[:300]}")
    return r.headers.get("Location", "")


def sql(query):
    return subprocess.run(["psql", "-h", "localhost", "-U", "sdmxdw", "-d", "sdmxdw", "-tA", "-c", query],
                          env={"PGPASSWORD": os.environ["SDMXDW_DB_PASSWORD"], "PATH": "/usr/bin:/bin"},
                          capture_output=True, text=True, check=True).stdout.strip()


def main():
    token = re.search(r'name="_csrf" value="([^"]+)"', s.get(BASE + "/login").text).group(1)
    s.post(BASE + "/login", data={"username": os.environ.get("SDMXDW_ADMIN_USER", "admin@sdmxdw.local"), "password": os.environ["SDMXDW_ADMIN_PASSWORD"], "_csrf": token})
    if sql("select count(*) from microdata_flow where artefact_id like 'MDF_EKE_%'") != "0":
        sys.exit("EKE flows already exist - aborting")
    category = sql("select c.id from category c join category_scheme s on s.id=c.category_scheme_id "
                   "where s.artefact_id='CS_EKE' and c.code='EKE'")

    cl_ids = {}
    for artefact, (name, codes) in CODELISTS.items():
        post("/structures/codelists", dict(agencyId=AGENCY, artefactId=artefact, version=VERSION, name=name))
        cl_ids[artefact] = sql(f"select id from codelist where agency_id='{AGENCY}' and artefact_id='{artefact}'")
        for code, label in codes:
            post(f"/structures/codelists/{cl_ids[artefact]}/codes", dict(code=code, name=label))

    flow_ids = {}
    for key, name, _ in ENTITIES:
        loc = post("/microdata", dict(agencyId=AGENCY, artefactId=f"MDF_EKE_{key}", version=VERSION,
                                      name=name, description=DESCRIPTION))
        flow_ids[key] = loc.rstrip("/").split("/")[-1]
        post(f"/microdata/{flow_ids[key]}/categorisation", dict(categoryId=category))

    for key, _, fields in ENTITIES:
        fid = flow_ids[key]
        for f in fields:
            data = dict(fieldCode=f["code"], label=f["label"], obligation=f["obligation"],
                        subtype=f["subtype"] or "", subtypeRule=f["rule"] or "")
            if f["obligation"] == "M" and not f["subtype"]:
                data["required"] = "true"
            if f["facet"]:
                data["facetSlot"] = f["facet"]
            if f["type"].startswith("REF:"):
                data["referencedFlowId"] = flow_ids[f["type"][4:]]
                post(f"/microdata/{fid}/reference-fields", data)
            else:
                data["dataType"] = f["type"]
                if f["codelist"]:
                    data["codelistId"] = cl_ids[f["codelist"]]
                post(f"/microdata/{fid}/fields", data)

    counts = {}
    for key, records in SAMPLE:
        fid = flow_ids[key]
        page = s.post(f"{BASE}/microdata/{fid}/ingest-token", data={"pluginIdentity": "eke-v5-demo", "_csrf": csrf()}).text
        ingest_token = re.search(r"<code>([0-9a-f-]{36})</code>", page).group(1)
        body = {"records": [{"entityId": e, "reportingPeriod": PERIOD, "fields": f} for e, f in records]}
        r = requests.post(f"{BASE}/api/ingest/microdata/{fid}/records", json=body, headers={"X-Ingest-Token": ingest_token})
        if r.status_code != 202:
            sys.exit(f"ingest {key} failed: {r.status_code} {r.text}")
        post(f"/microdata/{fid}/records/promote", {})
        counts[key] = len(records)
    print(json.dumps({"flows": len(flow_ids), "fields": sum(len(e[2]) for e in ENTITIES), "records": counts}))


if __name__ == "__main__":
    main()
