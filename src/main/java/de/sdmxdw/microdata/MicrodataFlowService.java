package de.sdmxdw.microdata;

import de.sdmxdw.structure.codelist.CodelistRepository;
import de.sdmxdw.structure.dataflow.DataflowRepository;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class MicrodataFlowService {

  private final MicrodataFlowRepository microdataFlowRepository;
  private final FieldDefinitionRepository fieldDefinitionRepository;
  private final CodelistRepository codelistRepository;
  private final DataflowRepository dataflowRepository;

  public MicrodataFlowService(
      MicrodataFlowRepository microdataFlowRepository,
      FieldDefinitionRepository fieldDefinitionRepository,
      CodelistRepository codelistRepository,
      DataflowRepository dataflowRepository) {
    this.microdataFlowRepository = microdataFlowRepository;
    this.fieldDefinitionRepository = fieldDefinitionRepository;
    this.codelistRepository = codelistRepository;
    this.dataflowRepository = dataflowRepository;
  }

  public List<MicrodataFlow> findAll() {
    return microdataFlowRepository.findAll();
  }

  public MicrodataFlow findById(Long id) {
    return microdataFlowRepository.findById(id).orElseThrow();
  }

  public List<FieldDefinition> findFields(Long microdataFlowId) {
    return fieldDefinitionRepository.findByMicrodataFlowIdOrderByPosition(microdataFlowId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public MicrodataFlow createFlow(
      String agencyId, String artefactId, String version, String name, String description) {
    return microdataFlowRepository.save(
        MicrodataFlow.createNew(agencyId, artefactId, version, name, description));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public FieldDefinition addField(
      Long microdataFlowId,
      String fieldCode,
      String label,
      FieldDataType dataType,
      Long codelistId,
      boolean required,
      FacetSlot facetSlot) {
    return addFieldInternal(
        microdataFlowId, fieldCode, label, dataType, codelistId, required, facetSlot, null, null);
  }

  /**
   * Adds a field whose value is expected to be the entity_id of a current record in another
   * microdata flow, instead of a value from a fixed codelist. This is how a multi-entity model
   * (several linked flows, one per entity type - including junction-style flows that only hold two
   * reference fields) is expressed without any new tables: a reference field is just a field whose
   * validation target is another flow's records instead of a Codelist's codes.
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public FieldDefinition addReferenceField(
      Long microdataFlowId,
      String fieldCode,
      String label,
      Long referencedFlowId,
      boolean required,
      FacetSlot facetSlot) {
    if (microdataFlowRepository.findById(referencedFlowId).isEmpty()) {
      throw new IllegalArgumentException("Unknown microdata flow id: " + referencedFlowId);
    }
    return addFieldInternal(
        microdataFlowId,
        fieldCode,
        label,
        FieldDataType.STRING,
        null,
        required,
        facetSlot,
        null,
        referencedFlowId);
  }

  /**
   * Adds a field that belongs to a repeating group (e.g. one of several collaterals on a loan)
   * instead of being a single scalar value on the record. Survey-agnostic: the group mechanism
   * itself is generic, only the group code and its fields differ per microdata flow. Group fields
   * never get a facet slot - they have no single value to project into a filter column - which is
   * why this is a separate method rather than an extra parameter on {@link #addField}.
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public FieldDefinition addGroupField(
      Long microdataFlowId,
      String groupCode,
      String fieldCode,
      String label,
      FieldDataType dataType,
      Long codelistId,
      boolean required) {
    return addFieldInternal(
        microdataFlowId, fieldCode, label, dataType, codelistId, required, null, groupCode, null);
  }

  private FieldDefinition addFieldInternal(
      Long microdataFlowId,
      String fieldCode,
      String label,
      FieldDataType dataType,
      Long codelistId,
      boolean required,
      FacetSlot facetSlot,
      String groupCode,
      Long referencedFlowId) {
    findById(microdataFlowId);
    if (codelistId != null && codelistRepository.findById(codelistId).isEmpty()) {
      throw new IllegalArgumentException("Unknown codelist id: " + codelistId);
    }
    int nextPosition = findFields(microdataFlowId).size();
    return fieldDefinitionRepository.save(
        FieldDefinition.createNew(
            microdataFlowId,
            fieldCode,
            label,
            dataType,
            codelistId,
            required,
            facetSlot,
            groupCode,
            referencedFlowId,
            nextPosition));
  }

  /**
   * Records how the source data model specifies a field: its published obligation (M, M*, O) and,
   * for fields that only exist on a subtype (XOR specialisation) of the entity, the subtype and the
   * rule that selects it. Subtypes are flattened into their base entity, so this is what keeps the
   * original structure visible.
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public FieldDefinition documentField(
      Long fieldId, String obligation, String subtype, String subtypeRule) {
    String normalizedObligation = blankToNull(obligation);
    if (normalizedObligation != null && !Set.of("M", "M*", "O").contains(normalizedObligation)) {
      throw new IllegalArgumentException("Obligation must be M, M* or O: " + obligation);
    }
    FieldDefinition field = fieldDefinitionRepository.findById(fieldId).orElseThrow();
    return fieldDefinitionRepository.save(
        field.withDocumentation(
            normalizedObligation, blankToNull(subtype), blankToNull(subtypeRule)));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public MicrodataFlow linkDataflow(Long microdataFlowId, Long dataflowId) {
    if (dataflowRepository.findById(dataflowId).isEmpty()) {
      throw new IllegalArgumentException("Unknown dataflow id: " + dataflowId);
    }
    MicrodataFlow updated = findById(microdataFlowId).withLinkedDataflowId(dataflowId);
    return microdataFlowRepository.save(updated);
  }
}
