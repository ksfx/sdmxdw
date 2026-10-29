"""Demo plugin for the generic microdata ingest path.

Generates a handful of synthetic individual-loan records (modeled loosely on
the shape of the SNB's Einzelkrediterhebung - borrower industry, collateral
type, currency, maturity bucket, amount, interest rate) and hands them to SDMxDW
as a microdata delivery (file handoff, loaded into Alpha after the step).
"""

import random

from sdmxdw_sdk import MicrodataClient, base_arg_parser

INDUSTRIES = ["MANUFACTURING", "SERVICES", "CONSTRUCTION"]
COLLATERALS = ["MORTGAGE", "UNSECURED", "GUARANTEE"]
CURRENCIES = ["CHF", "EUR"]
MATURITIES = ["SHORT", "MEDIUM", "LONG"]
PERIODS = ["2025-Q4", "2026-Q1", "2026-Q2"]


def generate_records(count: int, seed: int) -> list[dict]:
    rng = random.Random(seed)
    records = []
    for i in range(count):
        entity_id = f"LOAN-{i:05d}"
        period = rng.choice(PERIODS)
        records.append(
            {
                "entityId": entity_id,
                "reportingPeriod": period,
                "fields": {
                    "INDUSTRY": rng.choice(INDUSTRIES),
                    "COLLATERAL_TYPE": rng.choice(COLLATERALS),
                    "CURRENCY": rng.choice(CURRENCIES),
                    "MATURITY_BUCKET": rng.choice(MATURITIES),
                    "AMOUNT": str(round(rng.uniform(50_000, 2_000_000), 2)),
                    "INTEREST_RATE": str(round(rng.uniform(0.5, 4.5), 3)),
                },
            }
        )
    return records


def main() -> None:
    parser = base_arg_parser(__doc__)
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    records = generate_records(args.count, args.seed)
    MicrodataClient(args.flow, dry_run=args.dry_run).send(records)


if __name__ == "__main__":
    main()
