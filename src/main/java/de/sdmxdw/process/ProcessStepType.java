package de.sdmxdw.process;

/** What a process step does. */
public enum ProcessStepType {
  /** Runs a Python (.py) or R (.R) script from the process folder. */
  SCRIPT,
  /** Built-in: aggregates a microdata flow into its linked time series dataflow. */
  AGGREGATE,
  /** Built-in: publishes the pending Alpha changes of a dataflow or microdata flow to Beta. */
  PROMOTE,
  /** Runs all steps of another process as part of this run. */
  PROCESS
}
