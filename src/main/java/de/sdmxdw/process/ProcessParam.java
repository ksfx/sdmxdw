package de.sdmxdw.process;

/** A run parameter of a process, passed to steps as SDMXDW_PARAM_<NAME> and ${name} in args. */
public record ProcessParam(String name, String label, String defaultValue, boolean required) {}
