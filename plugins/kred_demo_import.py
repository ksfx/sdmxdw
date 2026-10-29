"""Demo plugin: generates a consistent SNB KRED (Kreditvolumenstatistik) reporting for two
fictitious banks and sends it to the DF_KRED dataflow.

The numbers are synthetic, but they obey the check rules of the official form D_KRED_3.0:
  - Kol. 01-07 = 08, 09-15 = 16, 17-23 = 24, 25 = 16 + 24, 26 = 08 + 25
  - "davon" positions never exceed their parent position
  - K021 (borrower group _T) = sum of K022_1..5, except rows 01, 02, 53, 55, 56
  - row 54 (Total Inland) = sum of all domestic rows, row 56 = 54 + 55
Values are in CHF thousands. Usage (normally started by an import job):
  kred_demo_import.py --flow "SNB:DF_KRED(3.0.0)" [--periods 2025-Q3,...] [--dry-run]
"""

import random
import time

from sdmxdw_sdk import IngestClient, base_arg_parser

BANKS = {"DEMO01": 1.0, "DEMO02": 0.38}
GROUPS = ["MA_0_9", "MA_10_49", "MA_50_249", "MA_250P", "OERK"]
GROUP_SHARE = {"MA_0_9": 0.27, "MA_10_49": 0.24, "MA_50_249": 0.21, "MA_250P": 0.26, "OERK": 0.02}
PUBLIC_ROWS = {19, 20, 43, 46, 47}  # sectors where public-law corporations borrow

# main domestic enterprise rows (code -> base volume in CHF thousands for DEMO01) and their "davon" rows
MAIN = {3: 420_000, 4: 60_000, 5: 1_900_000, 19: 380_000, 20: 140_000, 21: 1_350_000, 22: 1_600_000,
        26: 520_000, 31: 610_000, 32: 380_000, 35: 950_000, 39: 4_800_000, 40: 690_000, 42: 410_000,
        43: 230_000, 46: 120_000, 47: 560_000, 49: 140_000, 50: 260_000, 51: 5_000, 52: 35_000}
DAVON = {5: [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18], 22: [23, 24, 25], 26: [27, 28, 29, 30],
         32: [33, 34], 35: [36, 37, 38], 40: [41], 43: [44, 45], 47: [48]}
NOGA2008_ONLY = {23}  # "nur NOGA 2008" - stays zero under NOGA 2025

# share of mortgage / covered / uncovered credit, typical per sector
MIX = {39: (0.86, 0.06, 0.08), 21: (0.55, 0.15, 0.30), 31: (0.60, 0.10, 0.30), 35: (0.10, 0.45, 0.45)}
DEFAULT_MIX = (0.38, 0.22, 0.40)
MATURITY = {  # shares for auf Sicht, kündbar, <=1M, 1-3M, 3-12M, 1-5Y, >5Y
    "hypo": (0.00, 0.03, 0.02, 0.05, 0.12, 0.43, 0.35),
    "gedeckt": (0.08, 0.10, 0.12, 0.18, 0.20, 0.22, 0.10),
    "ungedeckt": (0.15, 0.20, 0.15, 0.17, 0.15, 0.13, 0.05),
}


def split(total, shares):
    parts = [int(total * s) for s in shares]
    parts[-1] += total - sum(parts)
    return parts


def enterprise_cells(rng, bank_scale, growth):
    """{group: {row: dict(h, g, u, lim, wb, ab, gf)}} for K022 groups."""
    cells = {g: {} for g in GROUPS}
    for row, base in MAIN.items():
        for g in GROUPS:
            if g == "OERK" and row not in PUBLIC_ROWS:
                vol = 0
            else:
                share = GROUP_SHARE[g] * (6 if g == "OERK" else 1)
                vol = int(base * bank_scale * growth * share * rng.uniform(0.85, 1.15))
            h_s, g_s, u_s = MIX.get(row, DEFAULT_MIX)
            h, gd = int(vol * h_s), int(vol * g_s)
            cells[g][row] = dict(h=h, g=gd, u=vol - h - gd)
    for g in GROUPS:  # davon rows: shares of the parent that never exceed it
        for parent, children in DAVON.items():
            weights = [0 if c in NOGA2008_ONLY else rng.uniform(0.5, 1.5) for c in children]
            covered = rng.uniform(0.75, 0.95) / (sum(weights) or 1)
            for child, w in zip(children, weights):
                cells[g][child] = {k: int(v * w * covered) for k, v in cells[g][parent].items()}
    for g in GROUPS:
        for row, c in cells[g].items():
            used = c["h"] + c["g"] + c["u"]
            c.update(lim=int(used * rng.uniform(1.08, 1.45)), wb=int(used * rng.uniform(0.002, 0.012)),
                     ab=int(used * rng.uniform(0.0, 0.002)), gf=int(used * rng.uniform(0.004, 0.02)))
    return cells


def totals(cells, keys, rows):
    return {k: sum(cells[r][k] for r in rows) for k in keys}


def build_period(rng, bank, scale, period, t):
    growth = 1 + 0.007 * t
    ent = enterprise_cells(rng, scale, growth)
    keys = ("h", "g", "u", "lim", "wb", "ab", "gf")
    domestic_rows = list(MAIN)  # rows that are summed into row 54 (davon rows are not)
    for g in GROUPS:
        ent[g][54] = totals(ent[g], keys, domestic_rows)

    total = {}
    for row in list(MAIN) + [c for cs in DAVON.values() for c in cs] + [54]:
        total[row] = {k: sum(ent[g][row][k] for g in GROUPS) for k in keys}
    # rows reported only on K021: private persons, unallocated value adjustments, foreign, grand total
    private = int(9_500_000 * scale * growth * rng.uniform(0.95, 1.05))
    total[1] = dict(h=int(private * 0.93), g=int(private * 0.03), u=private - int(private * 0.93) - int(private * 0.03))
    consumer = int(total[1]["u"] * rng.uniform(0.4, 0.6))
    total[2] = dict(h=0, g=0, u=consumer)
    for row in (1, 2):
        used = sum(total[row][k] for k in ("h", "g", "u"))
        total[row].update(lim=int(used * 1.05), wb=int(used * 0.001), ab=int(used * 0.0003), gf=int(used * 0.003))
    total[53] = dict(h=0, g=0, u=0, lim=0, wb=int(40_000 * scale), ab=0, gf=0)
    for k in keys:
        total[54][k] += total[1][k] + total[53][k]
    foreign = int(1_100_000 * scale * growth * rng.uniform(0.9, 1.1))
    total[55] = dict(h=int(foreign * 0.2), g=int(foreign * 0.5), u=foreign - int(foreign * 0.2) - int(foreign * 0.5))
    used = foreign
    total[55].update(lim=int(used * 1.3), wb=int(used * 0.004), ab=int(used * 0.0005), gf=int(used * 0.006))
    total[56] = {k: total[54][k] + total[55][k] for k in keys}

    obs = []

    def add(group, row, kol, value):
        obs.append({"dimensions": {"FREQ": "Q", "MELDEINSTITUT": bank, "KREDITNEHMERGRUPPE": group,
                                   "KREDITNEHMER": f"{row:02d}", "KOLONNE": f"{kol:02d}", "TIME_PERIOD": period},
                    "measures": {"OBS_VALUE": str(value)}})

    def add_totals(group, row, c):
        add(group, row, 8, c["h"])
        add(group, row, 16, c["g"])
        add(group, row, 24, c["u"])
        add(group, row, 25, c["g"] + c["u"])
        add(group, row, 26, c["h"] + c["g"] + c["u"])
        add(group, row, 27, c["lim"])
        add(group, row, 28, c["wb"])
        add(group, row, 29, c["ab"])
        add(group, row, 30, c["gf"])

    for g in GROUPS:
        for row in sorted(ent[g]):
            add_totals(g, row, ent[g][row])
    for row in sorted(total):
        c = total[row]
        if row == 53:  # only the value adjustment column applies to this position
            add("_T", 53, 28, c["wb"])
            continue
        for start, key, kind in ((1, "h", "hypo"), (9, "g", "gedeckt"), (17, "u", "ungedeckt")):
            for i, part in enumerate(split(c[key], MATURITY[kind])):
                add("_T", row, start + i, part)
        add_totals("_T", row, c)
    return obs


def main():
    parser = base_arg_parser("Generate a synthetic, rule-consistent KRED reporting")
    parser.add_argument("--periods", default="2025-Q3,2025-Q4,2026-Q1,2026-Q2")
    parser.add_argument("--seed", type=int, default=2026)
    args = parser.parse_args()
    client = IngestClient(args.flow, dry_run=args.dry_run)
    periods = [p.strip() for p in args.periods.split(",") if p.strip()]
    sent = 0
    for t, period in enumerate(periods):
        for bank, scale in BANKS.items():
            rng = random.Random(f"{args.seed}-{bank}-{period}")
            obs = build_period(rng, bank, scale, period, t)
            started = time.time()
            if args.dry_run:
                print(f"[dry-run] {bank} {period}: {len(obs)} observations, e.g. {obs[0]}")
            else:
                client.send(obs)
                print(f"{bank} {period}: {len(obs)} observations in {time.time() - started:.1f}s")
            sent += len(obs)
    print(f"Done: {sent} observations for {len(BANKS)} banks and {len(periods)} periods")


if __name__ == "__main__":
    main()
