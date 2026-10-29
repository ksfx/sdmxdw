package de.sdmxdw.structure.codelist;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class CodelistService {

  private final CodelistRepository codelistRepository;

  public CodelistService(CodelistRepository codelistRepository) {
    this.codelistRepository = codelistRepository;
  }

  public List<Codelist> findAll() {
    return codelistRepository.findAll();
  }

  public Codelist findById(Long id) {
    return codelistRepository.findById(id).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public Codelist createCodelist(String agencyId, String artefactId, String version, String name) {
    return codelistRepository.save(Codelist.createNew(agencyId, artefactId, version, name));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public Codelist addCode(Long codelistId, String code, String name) {
    Codelist codelist = findById(codelistId);
    Set<Code> codes = new HashSet<>(codelist.codes());
    codes.add(new Code(null, code, name));
    return codelistRepository.save(codelist.withCodes(codes));
  }
}
