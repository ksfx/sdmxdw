"""Extracts the KRED borrower positions (K021 rows) and columns (Kol. 01-30) from the official
form D_KRED_x.y.xlsx into kred_codelists.json. Usage: extract_kred_codelists.py <xlsx> <out.json>
(needs openpyxl)."""
import json, re, sys, openpyxl
wb = openpyxl.load_workbook(sys.argv[1], read_only=True, data_only=True)
ws = wb["K021.MELD"]

def clean(v):
    return re.sub(r"\s+", " ", str(v)).strip() if v not in (None, "") else ""

borrowers, pending, davon = [], None, False
for row in ws.iter_rows(min_row=16, max_row=86, values_only=True):
    a, b, c, d, e = (clean(x) for x in row[:5])
    if not any((a, b, c, d, e)):
        continue
    if c == "davon":
        davon = True
        continue
    if a or c and not d:          # new main row (section level) starts
        davon = False
    text = d or c
    if pending is None:
        pending = {"section": a, "division": b, "title": text, "sub": bool(d)}
    else:                          # continuation line of a wrapped title
        # a hyphen at a line break followed by a lowercase word is a syllable split, not a dash
        if pending["title"].endswith("-") and text[:1].islower():
            pending["title"] = pending["title"][:-1] + text
        else:
            pending["title"] += " " + text
    if e:
        p = pending
        prefix = " ".join(x for x in (p["section"], p["division"]) if x)
        label = (prefix + " " if prefix else "") + p["title"]
        if p["sub"]:
            label = "davon: " + label
        borrowers.append({"code": f"{int(e):02d}", "name": label})
        pending = None

# column labels from the K021 header block (rows 7-12), Kol. numbers in row 15
groups = {"F": "Hypothekarkredite", "O": "Übrige Kredite gedeckt", "X": "Übrige Kredite ungedeckt"}
maturity = ["auf Sicht", "kündbar", "mit Restlaufzeit bis 1 Monat", "mit Restlaufzeit über 1 Monat bis 3 Monate",
            "mit Restlaufzeit über 3 Monate bis 1 Jahr", "mit Restlaufzeit über 1 Jahr bis 5 Jahre",
            "mit Restlaufzeit über 5 Jahre"]
columns = []
for g, start in (("Hypothekarkredite", 1), ("Übrige Kredite gedeckt", 9), ("Übrige Kredite ungedeckt", 17)):
    for i, m in enumerate(maturity):
        columns.append({"code": f"{start + i:02d}", "name": f"{g}: {m}"})
    columns.append({"code": f"{start + 7:02d}", "name": f"{g}: Total (Kol. {start:02d} bis {start + 6:02d})"})
columns += [
    {"code": "25", "name": "Übrige Kredite: Total (Kol. 16 + 24)"},
    {"code": "26", "name": "Total Kredite: Stand der Benützung (Kol. 08 + 25)"},
    {"code": "27", "name": "Total Kredite: Stand der Limiten"},
    {"code": "28", "name": "Stand direkte Wertberichtigungen und Differenzen zum Fair Value"},
    {"code": "29", "name": "Abschreibungen"},
    {"code": "30", "name": "Gefährdete Forderungen (gem. RVB-Rz 413ff.)"},
]
# sanity check against the sheet's own "Kol. NN" header row
kol = [clean(c) for c in next(ws.iter_rows(min_row=15, max_row=15, values_only=True)) if clean(c).startswith("Kol")][:30]
assert len(kol) == 30 and kol[-1] == "Kol. 30", kol
json.dump({"borrowers": borrowers, "columns": columns}, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
print(len(borrowers), "borrowers,", len(columns), "columns")
for b in borrowers: print(" ", b["code"], b["name"][:110])
