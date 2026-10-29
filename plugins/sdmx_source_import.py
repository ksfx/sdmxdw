"""Standard plugin: import observations from an SDMX source via sdmx1.

Fetches one dataflow/key from a known SDMX source (e.g. ECB, OECD, BIS -
see sdmx1's list of built-in sources) and maps the resulting series onto
SDMxDW concept codes. Source dimension ids that don't already match the
target DSD's concept codes can be remapped with --dimension-rename.
"""

import sdmx

from sdmxdw_sdk import IngestClient, base_arg_parser


def main():
    parser = base_arg_parser("Import observations from an SDMX source via sdmx1")
    parser.add_argument("--source", required=True, help="sdmx1 source id, e.g. ECB")
    parser.add_argument("--resource-id", required=True, help="Dataflow id at the source, e.g. EXR")
    parser.add_argument("--key", default="", help="SDMX key filter, e.g. D.USD.EUR.SP00.A")
    parser.add_argument("--measure-concept", default="OBS_VALUE")
    parser.add_argument(
        "--dimension-rename",
        default="",
        help="old=new,old2=new2 to map source dimension ids to SDMxDW concept codes",
    )
    parser.add_argument("--start-period", default=None)
    args = parser.parse_args()

    rename = dict(pair.split("=", 1) for pair in args.dimension_rename.split(",") if pair)

    client_lib = sdmx.Client(args.source)
    params = {}
    if args.start_period:
        params["startPeriod"] = args.start_period
    key = args.key if args.key else None
    data_message = client_lib.data(resource_id=args.resource_id, key=key, params=params)
    series = sdmx.to_pandas(data_message)

    observations = []
    for index_values, value in series.items():
        names = series.index.names
        if not isinstance(index_values, tuple):
            index_values = (index_values,)
        dims = {}
        for name, val in zip(names, index_values):
            dims[rename.get(name, name)] = str(val)
        observations.append({"dimensions": dims, "measures": {args.measure_concept: str(value)}})

    print(f"Fetched {len(observations)} observations from {args.source}/{args.resource_id}")
    client = IngestClient(args.flow, dry_run=args.dry_run)
    client.send(observations)


if __name__ == "__main__":
    main()
