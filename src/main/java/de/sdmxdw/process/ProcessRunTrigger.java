package de.sdmxdw.process;

public enum ProcessRunTrigger {
  SCHEDULED,
  MANUAL,
  /** Started after a successful run of another process. */
  CHAINED,
  /** Started by a PROCESS step of another run. */
  SUB_PROCESS
}
