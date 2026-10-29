"""Standard plugin: import observations from a local CSV file.

CSV columns must be named after SDMX concept codes used in the target DSD
(e.g. FREQ, TIME_PERIOD, OBS_VALUE). --measure-columns tells the plugin
which of those columns are measures; every other column is treated as a
dimension.
"""

import csv

from sdmxdw_sdk import IngestClient, base_arg_parser


def main():
    parser = base_arg_parser("Import observations from a CSV file")
    parser.add_argument("--csv-path", required=True)
    parser.add_argument(
        "--measure-columns", required=True, help="Comma-separated CSV columns that are measures"
    )
    args = parser.parse_args()

    measure_columns = {c.strip() for c in args.measure_columns.split(",") if c.strip()}

    observations = []
    with open(args.csv_path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            dimensions = {k: v for k, v in row.items() if k not in measure_columns}
            measures = {k: v for k, v in row.items() if k in measure_columns}
            observations.append({"dimensions": dimensions, "measures": measures})

    print(f"Read {len(observations)} rows from {args.csv_path}")
    client = IngestClient(args.flow, dry_run=args.dry_run)
    client.send(observations)


if __name__ == "__main__":
    main()
