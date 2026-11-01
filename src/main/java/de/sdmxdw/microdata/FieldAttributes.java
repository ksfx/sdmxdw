package de.sdmxdw.microdata;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Source attributes of a field ({@link FieldDefinition#attributes()}): a JSON object of strings in
 * display order. In the UI they are edited as "Key: Value" lines.
 */
public final class FieldAttributes {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {};

  private FieldAttributes() {}

  /** Attributes of the stored JSON; empty for null or unreadable JSON. */
  public static Map<String, String> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(json, TYPE);
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  /** JSON for storage, null when there are no attributes. */
  public static String toJson(Map<String, String> attributes) {
    return attributes == null || attributes.isEmpty()
        ? null
        : MAPPER.writeValueAsString(attributes);
  }

  /** Parses "Key: Value" lines; lines without a colon are rejected. */
  public static Map<String, String> parseLines(String text) {
    Map<String, String> result = new LinkedHashMap<>();
    if (text == null) {
      return result;
    }
    for (String line : text.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw new IllegalArgumentException("Attribute lines must look like 'Key: Value': " + line);
      }
      result.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
    }
    return result;
  }

  /** "Key: Value" lines for editing. */
  public static String toLines(Map<String, String> attributes) {
    StringBuilder text = new StringBuilder();
    attributes.forEach((k, v) -> text.append(k).append(": ").append(v).append('\n'));
    return text.toString();
  }
}
