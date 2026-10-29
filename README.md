# SDMxDW

An **SDMX data warehouse** for statistical data of central banks and statistical offices:
time series and granular microdata in one application, with versioned (bitemporal) data, a
release workflow and a standard SDMX read API.

## Features

* **SDMX structures:** concept schemes, codelists, DSDs, dataflows, category schemes (with Markdown
  descriptions) and categorisations.
* **Time series:**
  * bitemporal observations (every revision is kept, "as of" queries)
  * two stages: *Alpha* (unpublished) and *Beta* (published)
* **Microdata:** generic container for register-style data (one record per entity and reporting
  period) with field catalog, codelists, references between flows, repeating groups and an
  entity-relationship view.
  * Examples: SNB EKE v5, ECB AnaCredit, SNB KRED as time series.
* **Explorer for everyone:**
  * searchable dimension pickers with counts, series charts, revisions and "published as of"
  * for data managers additionally: unpublished data, *pending changes* (Alpha vs Beta), corrections
    with reason, promotion and an activity log
* **Read API** (`/rest`):
  * SDMX 2.1/3.0 structure and data queries: SDMX-ML Generic and StructureSpecific, SDMX-CSV,
    SDMX-JSON
  * microdata as SDMX-CSV, JSON or Parquet
  * personal API tokens
  * works with Python `sdmx1` and R `rsdmx`
* **Processes:** imports and analyses as Python or R scripts with steps, schedules, parameters,
  secrets and chaining.
  * Steps hand over SDMX-CSV/Parquet files, which are bulk-loaded into Alpha (all or nothing).
  * Live logs, dry runs, a code editor with syntax highlighting.
  * The process folder can be linked to a Git repository (commit/push from the app, pull from an
    IDE).

## Stack

Java 25, Spring Boot 4 (Spring Data JDBC, Spring Security, Thymeleaf, htmx), PostgreSQL with
Flyway, DaisyUI/Tailwind, Chart.js, vis-network, CodeMirror 6, DuckDB (Parquet), JGit.

## Running

Requirements: JDK 25, PostgreSQL 16+, Python 3 (for processes; `sdmx1` for SDMX source imports),
optionally R.

```sh
createdb sdmxdw            # plus a user with access, see ops/sdmxdw.env.example
export SDMXDW_DB_PASSWORD=... SDMXDW_ADMIN_PASSWORD=...
./mvnw package -DskipTests
java -jar target/sdmxdw-0.0.1-SNAPSHOT.jar
```

Open http://localhost:8080 and log in as `admin@sdmxdw.local` with `SDMXDW_ADMIN_PASSWORD`. If it
is not set, a random password is generated and printed to the log once. You must change it at the
first login. All settings are environment variables, see `ops/sdmxdw.env.example`;
`ops/sdmxdw.service` is a systemd unit, `ops/backup-postgres.sh` a backup job.

**Back up** the secret key file (`SDMXDW_SECRET_KEY_FILE`) together with the database: process
secrets and the Git token are encrypted with it.

## Development

```sh
./mvnw spotless:apply      # formatting (google-java-format)
./mvnw verify              # tests; need a PostgreSQL database sdmxdw_test (SDMXDW_TEST_DB_*)
```

* `ops/seed/` contains the scripts that build the demo structures (EKE v5, AnaCredit, KRED).
* `ops/codemirror/` contains the recipe for the bundled editor.
* Documentation of the read API is in the app under **API**; how to write process steps is in
  `shared/README.md` of the process folder.

## Licence

Not yet decided.
