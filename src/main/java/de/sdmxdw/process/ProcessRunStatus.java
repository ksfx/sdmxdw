package de.sdmxdw.process;

public enum ProcessRunStatus {
  QUEUED,
  RUNNING,
  SUCCESS,
  FAILURE,
  CANCELLED;

  public boolean finished() {
    return this == SUCCESS || this == FAILURE || this == CANCELLED;
  }
}
