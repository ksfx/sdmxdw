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
    String subtypeRule,
    String description,
    String attributes) {

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
        newSubtypeRule,
        description,
        attributes);
  }

  /**
   * @param newDescription definition of the field as Markdown
   * @param newAttributes further source attributes as a JSON object in display order
   */
  public FieldDefinition withDescription(String newDescription, String newAttributes) {
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
        obligation,
        subtype,
        subtypeRule,
        newDescription,
        newAttributes);
  }
}
