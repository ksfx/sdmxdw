# Writing process steps

A process is a list of steps. A **script step** runs a Python (`.py`) or R (`.R`) script from the
process folder; built-in steps aggregate microdata, promote Alpha to Beta or run another process.

## Contract of a script step

| What | How |
|---|---|
| Working directory | `SDMXDW_WORK_DIR`, shared by all steps of the run (intermediate files) |
| Deliveries | SDMX-CSV or Parquet files in `SDMXDW_OUT_DIR`; loaded into **Alpha** right after the step, all files together, all or nothing |
| Target flow | column `STRUCTURE_ID`, e.g. `DEMO:DF_LOANS(1.0.0)`; microdata group rows `DEMO:MDF_LOANS(1.0.0)/COLLATERAL` with `ENTITY_ID`, `REPORTING_PERIOD`, `ITEM_NO` |
| Parameters | `SDMXDW_PARAM_<NAME>`, `${name}` in the step arguments |
| Secrets | environment variables with their own name (selected in the process settings) |
| Reading data | read API at `SDMXDW_API_URL` with the run's credential `SDMXDW_API_TOKEN` (read only) |
| Dry run | `SDMXDW_DRY_RUN=1`: deliveries are validated, not loaded |
| Success | exit code 0; output (stdout/stderr) is the step log |

The process folder and `shared/` are on the Python path; R files of the process are loaded with
`sdmxdw_source("lib.R")`.

## Python

```python
from sdmxdw_sdk import IngestClient, MicrodataClient, read_data, read_microdata, param, write_table

IngestClient("DEMO:DF_LOANS(1.0.0)").send([
    {"dimensions": {"FREQ": "Q", "BANK": "B01", ..., "TIME_PERIOD": "2026-Q3"},
     "measures": {"OBS_VALUE": "123"}},
])
MicrodataClient("DEMO:MDF_LOANS(1.0.0)").send([
    {"entityId": "I-1", "reportingPeriod": "2026-Q3", "fields": {...}, "groups": {"COLLATERAL": [{...}]}},
])
write_table("DEMO:DF_LOANS(1.0.0)", pandas_dataframe)   # columns = concept codes
rows = read_data("DEMO:DF_LOANS(1.0.0)", key="Q.B01..", startPeriod="2025-Q1")
loans = read_microdata("DEMO:MDF_LOANS(1.0.0)", reportingPeriod="latest", c={"CURRENCY": "CHF"})
period = param("period", "latest")
```

## R

```r
source(file.path(Sys.getenv("SDMXDW_SHARED_DIR"), "sdmxdw.R"))
loans <- sdmxdw_read("DEMO:DF_LOANS(1.0.0)", key = "Q.B01..", startPeriod = "2025-Q1")
result <- data.frame(FREQ = "Q", ..., TIME_PERIOD = "2026-Q3", OBS_VALUE = 1.5)
sdmxdw_write("DEMO:DF_RESULT(1.0.0)", result)
period <- sdmxdw_param("period", "latest")
```

A delivery is a complete statement per key: values are added or replace the current Alpha value;
keys missing from a delivery stay untouched. Re-delivering an unchanged value creates no new
version. Published (Beta) data changes only by promotion.
