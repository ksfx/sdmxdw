"""Builds the SNB "Kreditvolumenstatistik" (KRED, release 3.0, forms K021/K022_1-5) as an SDMX
time series model in SDMxDW: concept scheme, codelists, DSD, dataflow and categorisation.

Codelist texts for borrowers (56 positions, NOGA 2025) and columns (Kol. 01-30) come verbatim
from the official form D_KRED_3.0.xlsx (extracted into kred_codelists.json). The K022 forms
number their total columns 01-04; per the form's own check rules those are the same quantities
as K021 Kol. 08/16/24/25, so the cube uses the K021 column codes for every borrower group.

Usage: python build_kred.py [--base http://localhost:8080]
"""

import os
import argparse
import json
import pathlib
import re
import subprocess
import sys

import requests

AGENCY, VERSION = "SNB", "3.0.0"
s = requests.Session()
BASE = "http://localhost:8080"


def csrf():
    return re.search(r'name="_csrf" value="([^"]+)"', s.get(BASE + "/structures/dataflows").text).group(1)


def post(path, data):
    data = dict(data, _csrf=csrf())
    r = s.post(BASE + path, data=data, allow_redirects=False)
    if r.status_code not in (302, 303):
        sys.exit(f"POST {path} failed: {r.status_code} {r.text[:300]}")
    return r.headers.get("Location", "")


def sql(query):
    return subprocess.run(
        ["psql", "-h", "localhost", "-U", "sdmxdw", "-d", "sdmxdw", "-tA", "-c", query],
        env={"PGPASSWORD": os.environ["SDMXDW_DB_PASSWORD"], "PATH": "/usr/bin:/bin"},
        capture_output=True, text=True, check=True).stdout.strip()


def login():
    token = re.search(r'name="_csrf" value="([^"]+)"', s.get(BASE + "/login").text).group(1)
    s.post(BASE + "/login", data={"username": os.environ.get("SDMXDW_ADMIN_USER", "admin@sdmxdw.local"), "password": os.environ["SDMXDW_ADMIN_PASSWORD"], "_csrf": token})


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=BASE)
    BASE = parser.parse_args().base
    login()
    if sql("select count(*) from dataflow where artefact_id='DF_KRED'") != "0":
        sys.exit("DF_KRED already exists - aborting to avoid duplicates")

    form = json.loads((pathlib.Path(__file__).parent / "kred_codelists.json").read_text())
    codelists = {
        "CL_KRED_FREQ": ("Frequenz", [("Q", "Quartal"), ("M", "Monat")]),
        "CL_KRED_MELDEINSTITUT": ("Meldeinstitut (SNB-Code)", [
            ("DEMO01", "Musterbank AG (Demo)"), ("DEMO02", "Beispiel Kantonalbank (Demo)")]),
        "CL_KRED_KREDITNEHMERGRUPPE": ("Kreditnehmergruppe (Formular)", [
            ("_T", "Total (Alle Kreditnehmer) - K021"),
            ("MA_0_9", "Unternehmen mit bis zu 9 Mitarbeitern - K022_1"),
            ("MA_10_49", "Unternehmen mit 10 bis 49 Mitarbeitern - K022_2"),
            ("MA_50_249", "Unternehmen mit 50 bis 249 Mitarbeitern - K022_3"),
            ("MA_250P", "Unternehmen mit 250 und mehr Mitarbeitern - K022_4"),
            ("OERK", "Kredite an öffentlich-rechtliche Körperschaften - K022_5")]),
        "CL_KRED_KREDITNEHMER": ("Kreditnehmer (Wirtschaftszweige nach NOGA 2025)",
                                 [(b["code"], b["name"]) for b in form["borrowers"]]),
        "CL_KRED_KOLONNE": ("Kolonne (Kreditart, Restlaufzeit, Limiten, Wertberichtigungen)",
                            [(c["code"], c["name"]) for c in form["columns"]]),
    }
    cl_ids = {}
    for artefact, (name, codes) in codelists.items():
        post("/structures/codelists", dict(agencyId=AGENCY, artefactId=artefact, version=VERSION, name=name))
        cl_ids[artefact] = sql(f"select id from codelist where agency_id='{AGENCY}' and artefact_id='{artefact}'")
        for code, label in codes:
            post(f"/structures/codelists/{cl_ids[artefact]}/codes", dict(code=code, name=label))

    concepts = [
        ("FREQ", "Frequenz", "CL_KRED_FREQ", "STRING"),
        ("MELDEINSTITUT", "Meldeinstitut (SNB-Code)", "CL_KRED_MELDEINSTITUT", "STRING"),
        ("KREDITNEHMERGRUPPE", "Kreditnehmergruppe (Formular K021 / K022)", "CL_KRED_KREDITNEHMERGRUPPE", "STRING"),
        ("KREDITNEHMER", "Kreditnehmer (Wirtschaftszweige nach NOGA 2025)", "CL_KRED_KREDITNEHMER", "STRING"),
        ("KOLONNE", "Kolonne", "CL_KRED_KOLONNE", "STRING"),
        ("TIME_PERIOD", "Stichdatum", None, "DATE"),
    ]
    post("/structures/concept-schemes", dict(agencyId=AGENCY, artefactId="CS_KRED", version=VERSION,
                                             name="Kreditvolumenstatistik - Konzepte"))
    cs_id = sql("select id from concept_scheme where artefact_id='CS_KRED'")
    for code, name, _, _ in concepts + [("OBS_VALUE", "Wert (in 1'000 Franken)", None, None)]:
        post(f"/structures/concept-schemes/{cs_id}/concepts", dict(code=code, name=name))
    concept_id = lambda code: sql(f"select id from concept where concept_scheme_id={cs_id} and code='{code}'")

    post("/structures/dsds", dict(agencyId=AGENCY, artefactId="DSD_KRED", version=VERSION,
                                  name="Kreditvolumenstatistik (Release 3.0)"))
    dsd_id = sql("select id from data_structure_definition where artefact_id='DSD_KRED'")
    for code, _, codelist, dtype in concepts:
        post(f"/structures/dsds/{dsd_id}/dimensions",
             dict(conceptId=concept_id(code), codelistId=cl_ids[codelist] if codelist else "", dataType=dtype))
    post(f"/structures/dsds/{dsd_id}/measures", dict(conceptId=concept_id("OBS_VALUE"), dataType="NUMBER"))

    post("/structures/dataflows", dict(agencyId=AGENCY, artefactId="DF_KRED", version=VERSION,
                                       name="Kreditvolumenstatistik", dsdId=dsd_id))
    df_id = sql("select id from dataflow where artefact_id='DF_KRED'")

    post("/structures/category-schemes", dict(agencyId=AGENCY, artefactId="CS_SNB_EMI", version="1.0.0",
                                              name="SNB-Erhebungen (eMI)"))
    cat_scheme = sql("select id from category_scheme where artefact_id='CS_SNB_EMI'")
    post(f"/structures/category-schemes/{cat_scheme}/categories",
         dict(parentCategoryId="", code="KRED", name="Kreditvolumenstatistik (KRED)"))
    cat_id = sql(f"select id from category where category_scheme_id={cat_scheme} and code='KRED'")
    post(f"/structures/dataflows/{df_id}/categorisation", dict(categoryId=cat_id))
    print(json.dumps({"dataflowId": df_id, "dsdId": dsd_id, "conceptSchemeId": cs_id, "codelists": cl_ids}))


if __name__ == "__main__":
    main()
