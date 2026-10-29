package de.sdmxdw.microdata;

import java.util.List;
import java.util.Map;

/**
 * Parsed shape of {@code microdata_record.payload_json}: plain scalar field values plus, per
 * repeating group code, the list of instances submitted for that group (e.g. several collaterals,
 * each its own map of field code to value).
 */
public record MicrodataPayload(
    Map<String, String> scalars, Map<String, List<Map<String, String>>> groups) {}
