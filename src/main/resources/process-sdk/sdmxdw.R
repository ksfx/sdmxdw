# SDK for SDMxDW process steps (R).
#
# A step is a plain R script started by SDMxDW (Rscript). It never talks to the database:
# * Writing: sdmxdw_write(flow, df) writes SDMX-CSV into the step's output folder; SDMxDW loads it
#   into the Alpha stage right after the step (all files of a step together, all or nothing).
# * Reading: sdmxdw_read() / sdmxdw_read_microdata() use the read API with the run's own
#   short-lived credential - nothing to configure.
# * Parameters: sdmxdw_param("period"); secrets are environment variables (Sys.getenv("NAME")).
#
# Use it with:  source(file.path(Sys.getenv("SDMXDW_SHARED_DIR"), "sdmxdw.R"))
#
# Example:
#   df <- sdmxdw_read("SNB:DF_KRED(3.0.0)", key = "Q.DEMO01._T..26", startPeriod = "2025-Q1")
#   result <- data.frame(FREQ = "Q", ..., TIME_PERIOD = "2026-Q2", OBS_VALUE = 1.5)
#   sdmxdw_write("SNB:DF_RESULT(1.0.0)", result)

sdmxdw_param <- function(name, default = NULL) {
  value <- Sys.getenv(paste0("SDMXDW_PARAM_", toupper(name)), unset = "")
  if (identical(value, "")) default else value
}

sdmxdw_out_dir <- function() {
  path <- Sys.getenv("SDMXDW_OUT_DIR", unset = file.path(getwd(), "out"))
  dir.create(path, showWarnings = FALSE, recursive = TRUE)
  path
}

sdmxdw_work_dir <- function() Sys.getenv("SDMXDW_WORK_DIR", unset = getwd())

sdmxdw_process_dir <- function() Sys.getenv("SDMXDW_PROCESS_DIR", unset = getwd())

sdmxdw_dry_run <- function() identical(Sys.getenv("SDMXDW_DRY_RUN"), "1")

# source() a part script relative to the process folder
sdmxdw_source <- function(path, ...) source(file.path(sdmxdw_process_dir(), path), ...)

.sdmxdw_counter <- new.env()
.sdmxdw_counter$n <- 0

# Writes a data.frame as SDMX-CSV for `flow` ("AGENCY:ID(VERSION)", or ".../GROUP" for the rows of
# a microdata repeating group). Columns are concept / field codes; for microdata ENTITY_ID and
# REPORTING_PERIOD (and ITEM_NO for groups).
sdmxdw_write <- function(flow, df) {
  stopifnot(is.data.frame(df), grepl("^[^:/]+:[^:(/]+\\([^)]+\\)(/.+)?$", flow))
  df <- df[, setdiff(names(df), c("STRUCTURE", "STRUCTURE_ID", "ACTION")), drop = FALSE]
  out <- cbind(data.frame(STRUCTURE = rep("dataflow", nrow(df)), STRUCTURE_ID = rep(flow, nrow(df)),
                          ACTION = rep("I", nrow(df)), stringsAsFactors = FALSE), df)
  .sdmxdw_counter$n <- .sdmxdw_counter$n + 1
  safe <- gsub("^_|_$", "", gsub("[^A-Za-z0-9]+", "_", flow))
  path <- file.path(sdmxdw_out_dir(), sprintf("%s-%04d-%d.csv", safe, .sdmxdw_counter$n, Sys.getpid()))
  utils::write.csv(out, path, row.names = FALSE, na = "", fileEncoding = "UTF-8")
  message(nrow(df), " rows for ", flow, " -> ", basename(path))
  invisible(path)
}

.sdmxdw_flow_path <- function(flow) {
  m <- regmatches(flow, regexec("^([^:]+):([^(]+)\\(([^)]+)\\)$", flow))[[1]]
  if (length(m) == 4) paste(m[2], m[3], m[4], sep = ",") else flow
}

.sdmxdw_get <- function(path, params) {
  base <- sub("/$", "", Sys.getenv("SDMXDW_API_URL", unset = "http://localhost:8080/rest"))
  params <- params[!vapply(params, is.null, logical(1))]
  query <- if (length(params)) paste0("?", paste(
    vapply(names(params), utils::URLencode, character(1), reserved = TRUE),
    vapply(as.character(params), utils::URLencode, character(1), reserved = TRUE),
    sep = "=", collapse = "&")) else ""
  url <- paste0(base, path, query)
  token <- Sys.getenv("SDMXDW_API_TOKEN")
  if (requireNamespace("httr2", quietly = TRUE)) {
    req <- httr2::request(url)
    if (nzchar(token)) req <- httr2::req_auth_bearer_token(req, token)
    req <- httr2::req_error(req, is_error = function(resp) FALSE)
    resp <- httr2::req_perform(req)
    if (httr2::resp_status(resp) == 404) return("")
    if (httr2::resp_status(resp) >= 400) stop("read API ", httr2::resp_status(resp), ": ", httr2::resp_body_string(resp))
    return(httr2::resp_body_string(resp))
  }
  con <- url(url, headers = if (nzchar(token)) c(Authorization = paste("Bearer", token)) else character())
  on.exit(close(con))
  paste(readLines(con, warn = FALSE, encoding = "UTF-8"), collapse = "\n")
}

.sdmxdw_frame <- function(text) {
  if (!nzchar(trimws(text))) return(data.frame())
  df <- utils::read.csv(text = text, stringsAsFactors = FALSE, check.names = FALSE, encoding = "UTF-8")
  df[, setdiff(names(df), c("STRUCTURE", "STRUCTURE_ID", "ACTION")), drop = FALSE]
}

# Observations of a dataflow as data.frame; ... are read API parameters (startPeriod, asOf, ...).
sdmxdw_read <- function(flow, key = "all", stage = NULL, ...) {
  .sdmxdw_frame(.sdmxdw_get(paste0("/data/", .sdmxdw_flow_path(flow), "/", key),
                            c(list(format = "csv", stage = stage), list(...))))
}

# Records (or rows of a repeating group) of a microdata flow; filters as c = list(CURRENCY = "CHF").
sdmxdw_read_microdata <- function(flow, group = NULL, stage = NULL, c = list(), ...) {
  params <- c(list(format = "csv", stage = stage), list(...))
  for (field in names(c)) params[[paste0("c[", field, "]")]] <- paste(c[[field]], collapse = ",")
  path <- paste0("/microdata/", .sdmxdw_flow_path(flow), if (!is.null(group)) paste0("/groups/", group) else "")
  .sdmxdw_frame(.sdmxdw_get(path, params))
}
