package de.sdmxdw.structure.annotation;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SDMX annotations of structure artefacts (#15). Everyone may read them (they are part of the
 * published structures); Datenmanager maintain them.
 */
@Service
public class AnnotationService {

  /** One SDMX annotation key per target: kind and id of the annotated artefact. */
  public record Key(AnnotationTarget type, Long id) {}

  private final AnnotationRepository repository;

  public AnnotationService(AnnotationRepository repository) {
    this.repository = repository;
  }

  public List<Annotation> find(AnnotationTarget type, Long id) {
    return repository.findByTarget(type.name(), id);
  }

  /** Annotations of several artefacts of one kind, by artefact id. */
  public Map<Long, List<Annotation>> find(AnnotationTarget type, Collection<Long> ids) {
    Map<Long, List<Annotation>> result = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return result;
    }
    for (Annotation a : repository.findByTargets(type.name(), ids)) {
      result.computeIfAbsent(a.targetId(), k -> new java.util.ArrayList<>()).add(a);
    }
    return result;
  }

  /** All annotations, for the SDMX structure output. */
  public Map<Key, List<Annotation>> findAll() {
    Map<Key, List<Annotation>> result = new LinkedHashMap<>();
    for (Annotation a : repository.findAllOrdered()) {
      result
          .computeIfAbsent(new Key(a.targetType(), a.targetId()), k -> new java.util.ArrayList<>())
          .add(a);
    }
    return result;
  }

  public Annotation findById(Long id) {
    return repository.findById(id).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Annotation add(
      AnnotationTarget type,
      Long targetId,
      String annotationId,
      String annotationType,
      String title,
      String text,
      String url,
      String user) {
    requireContent(annotationType, title, text, url);
    int position = find(type, targetId).size();
    return repository.save(
        new Annotation(
            null,
            type,
            targetId,
            position,
            clean(annotationId),
            clean(annotationType),
            clean(title),
            clean(text),
            clean(url),
            user,
            Instant.now()));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Annotation update(
      Long id,
      String annotationId,
      String annotationType,
      String title,
      String text,
      String url,
      String user) {
    requireContent(annotationType, title, text, url);
    Annotation a = findById(id);
    return repository.save(
        new Annotation(
            a.id(),
            a.targetType(),
            a.targetId(),
            a.position(),
            clean(annotationId),
            clean(annotationType),
            clean(title),
            clean(text),
            clean(url),
            user,
            Instant.now()));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public void delete(Long id) {
    repository.deleteById(id);
  }

  private static void requireContent(String type, String title, String text, String url) {
    if (clean(type) == null && clean(title) == null && clean(text) == null && clean(url) == null) {
      throw new IllegalArgumentException("An annotation needs a type, title, text or URL");
    }
    if (clean(url) != null && !clean(url).matches("(?i)https?://\\S+")) {
      throw new IllegalArgumentException("The URL must start with http:// or https://");
    }
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
