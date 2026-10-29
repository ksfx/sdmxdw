package de.sdmxdw.microdata;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("microdata_field_definition")
public record FieldDefinition(
    @Id Long id,
    Long microdataFlowId,
    String fieldCode,
    String label,
    FieldDataType dataType,
    Long codelistId,
    boolean required,
    FacetSlot facetSlot,
    String groupCode,
    Long referencedFlowId,
    int position,
    String obligation,
    String subtype,
    String subtypeRule) {

  public static FieldDefinition createNew(
      Long microdataFlowId,
      String fieldCode,
      String label,
      FieldDataType dataType,
      Long codelistId,
      boolean required,
      FacetSlot facetSlot,
      String groupCode,
      Long referencedFlowId,
      int position) {
    return new FieldDefinition(
        null,
        microdataFlowId,
        fieldCode,
        label,
        dataType,
        codelistId,
        required,
        facetSlot,
        groupCode,
        referencedFlowId,
        position,
        null,
        null,
        null);
  }

  public FieldDefinition withDocumentation(
      String newObligation, String newSubtype, String newSubtypeRule) {
    return new FieldDefinition(
        id,
        microdataFlowId,
        fieldCode,
        label,
        dataType,
        codelistId,
        required,
        facetSlot,
        groupCode,
        referencedFlowId,
        position,
        newObligation,
        newSubtype,
        newSubtypeRule);
  }
}
