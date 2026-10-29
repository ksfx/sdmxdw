"""SDK for SDMxDW process steps (Python).

A step is a plain script started by SDMxDW. It never talks to the database:

* Writing: deliveries are written as SDMX-CSV files into the step's output folder
  (SDMXDW_OUT_DIR). SDMxDW validates and loads them into the Alpha stage right after the
  step - all files of a step together, all or nothing. The STRUCTURE_ID column names the target
  flow, e.g. "SNB:DF_KRED(3.0.0)" or a microdata flow "SNB:MDF_EKE_INSTRUMENT(5.0.0)".
* Reading: read_data() / read_microdata() use the read API with the run's own short-lived
  credential (SDMXDW_API_TOKEN) - nothing to configure.
* Parameters: param("period"); secrets are plain environment variables (os.environ["NAME"]).

Example:
    from sdmxdw_sdk import IngestClient, param
    IngestClient("BIS:DF_CREDIT_GAP(1.0.0)").send([
        {"dimensions": {"FREQ": "Q", "BORROWERS_CTY": "CH", "TC_BORROWERS": "P", "TC_LENDERS": "A",
                        "CG_DTYPE": "A", "TIME_PERIOD": "2026-Q2"},
         "measures": {"OBS_VALUE": "1.5"}},
    ])
"""

import argparse
import csv
import io
import itertools
import os
import re
import sys
import urllib.parse
import urllib.request

_counter = itertools.count(1)


# --- environment ---------------------------------------------------------------------------

def param(name, default=None):
    """Run parameter as entered when starting the process (or its default)."""
    value = os.environ.get("SDMXDW_PARAM_" + name.upper())
    return default if value in (None, "") else value


def out_dir():
    """Folder whose SDMX-CSV / Parquet files are loaded after the step."""
    path = os.environ.get("SDMXDW_OUT_DIR") or os.path.join(os.getcwd(), "out")
    os.makedirs(path, exist_ok=True)
    return path


def work_dir():
    """Working directory shared by all steps of the run (for intermediate files)."""
    return os.environ.get("SDMXDW_WORK_DIR") or os.getcwd()


def process_dir():
    """Folder of the process code (to open data files that live next to the scripts)."""
    return os.environ.get("SDMXDW_PROCESS_DIR") or os.path.dirname(os.path.abspath(sys.argv[0]))


def dry_run():
    """True for a test run: deliveries are validated but not loaded."""
    return os.environ.get("SDMXDW_DRY_RUN") == "1"


def base_arg_parser(description):
    """Common arguments of import scripts: --flow (target flow reference)."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--flow", required=True, help='target flow, e.g. "SNB:DF_KRED(3.0.0)"')
    parser.add_argument("--dry-run", action="store_true", help="print only, write nothing")
    return parser


# --- writing -------------------------------------------------------------------------------

def _check_flow(flow):
    if not re.fullmatch(r"[^:/]+:[^:(/]+\([^)]+\)(/.+)?", flow or ""):
        raise ValueError(f'flow must look like "AGENCY:ID(VERSION)", got {flow!r}')
    return flow


def _file_name(flow, suffix=""):
    safe = re.sub(r"[^A-Za-z0-9]+", "_", flow).strip("_")
    return os.path.join(out_dir(), f"{safe}{suffix}-{next(_counter):04d}-{os.getpid()}.csv")


def write_table(flow, rows, columns=None):
    """Writes rows (list of dicts keyed by concept / field code) as SDMX-CSV for `flow`.

    Accepts a pandas DataFrame too. Returns the file path.
    """
    _check_flow(flow)
    if hasattr(rows, "to_dict"):  # pandas DataFrame
        rows = rows.astype(object).where(rows.notna(), None).to_dict(orient="records")
    rows = list(rows)
    if columns is None:
        columns = []
        for row in rows:
            for key in row:
                if key not in columns and key not in ("STRUCTURE", "STRUCTURE_ID", "ACTION"):
                    columns.append(key)
    path = _file_name(flow.split("/")[0], "" if "/" not in flow else "_" + flow.split("/", 1)[1])
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["STRUCTURE", "STRUCTURE_ID", "ACTION"] + list(columns))
        for row in rows:
            writer.writerow(["dataflow", flow, "I"] + ["" if row.get(c) is None else row.get(c) for c in columns])
    return path


class IngestClient:
    """Delivers observations of a time series dataflow.

    observations: [{"dimensions": {CONCEPT: code, ..., "TIME_PERIOD": "2026-Q2"},
                    "measures": {"OBS_VALUE": "1.5"}}, ...]
    """

    def __init__(self, flow, *legacy, dry_run=False, **_):
        if legacy:  # old signature IngestClient(ingest_url, dataflow_id)
            raise ValueError('IngestClient now takes the flow reference, e.g. IngestClient("SNB:DF_KRED(3.0.0)")')
        self.flow = _check_flow(flow)
        self.dry_run = dry_run

    def send(self, observations):
        rows = [{**o.get("dimensions", {}), **o.get("measures", {})} for o in observations]
        if self.dry_run:
            print(f"[dry-run] {len(rows)} observations for {self.flow}, e.g. {rows[:1]}")
            return None
        path = write_table(self.flow, rows)
        print(f"{len(rows)} observations for {self.flow} -> {os.path.basename(path)}")
        return path


class MicrodataClient:
    """Delivers records of a microdata flow (each record completely, incl. its groups).

    records: [{"entityId": "L-1", "reportingPeriod": "2026-Q3",
               "fields": {"CURRENCY": "CHF", ...},
               "groups": {"COLLATERAL": [{"TYPE": "RE", ...}, ...]}}, ...]
    """

    def __init__(self, flow, dry_run=False):
        self.flow = _check_flow(flow)
        self.dry_run = dry_run

    def send(self, records):
        main, groups = [], {}
        for r in records:
            main.append({"ENTITY_ID": r["entityId"], "REPORTING_PERIOD": r["reportingPeriod"], **r.get("fields", {})})
            for group, items in (r.get("groups") or {}).items():
                for n, item in enumerate(items, start=1):
                    groups.setdefault(group, []).append(
                        {"ENTITY_ID": r["entityId"], "REPORTING_PERIOD": r["reportingPeriod"], "ITEM_NO": n, **item})
        if self.dry_run:
            print(f"[dry-run] {len(main)} records for {self.flow}, e.g. {main[:1]}")
            return []
        paths = [write_table(self.flow, main)]
        for group, rows in groups.items():
            paths.append(write_table(f"{self.flow}/{group}", rows))
        print(f"{len(main)} records for {self.flow} -> {', '.join(os.path.basename(p) for p in paths)}")
        return paths


# --- reading -------------------------------------------------------------------------------

def _api(path, params):
    base = os.environ.get("SDMXDW_API_URL", "http://localhost:8080/rest").rstrip("/")
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    request = urllib.request.Request(base + path + ("?" + query if query else ""))
    token = os.environ.get("SDMXDW_API_TOKEN")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return ""
        raise RuntimeError(f"read API {e.code}: {e.read().decode('utf-8', 'replace')}") from e


def _flow_path(flow):
    m = re.fullmatch(r"([^:]+):([^(]+)\(([^)]+)\)", flow)
    return f"{m.group(1)},{m.group(2)},{m.group(3)}" if m else flow


def _rows(text):
    if not text.strip():
        return []
    return [{k: v for k, v in row.items() if k not in ("STRUCTURE", "STRUCTURE_ID", "ACTION")}
            for row in csv.DictReader(io.StringIO(text))]


def read_data(flow, key="all", stage=None, **params):
    """Observations of a dataflow as list of dicts (SDMX-CSV columns). Params as in the read
    API, e.g. startPeriod="2025-Q1", asOf="2026-06-30"; stage="ALPHA" for unpublished data."""
    params.update(format="csv", stage=stage)
    return _rows(_api(f"/data/{_flow_path(flow)}/{key}", params))


def read_microdata(flow, group=None, stage=None, **params):
    """Records (or the rows of a repeating group) of a microdata flow as list of dicts.
    Params e.g. reportingPeriod="latest"; filters as c={"CURRENCY": "CHF"}."""
    filters = params.pop("c", {}) or {}
    for field, value in filters.items():
        params[f"c[{field}]"] = value
    params.update(format="csv", stage=stage)
    path = f"/microdata/{_flow_path(flow)}" + (f"/groups/{group}" if group else "")
    return _rows(_api(path, params))


def read_data_frame(flow, key="all", **params):
    """Like read_data, as pandas DataFrame (needs pandas)."""
    import pandas as pd
    return pd.DataFrame(read_data(flow, key, **params))
