package de.sdmxdw.process;

import de.sdmxdw.data.audit.AuditLogService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Editing the code of a process (or of {@code shared/}) from the UI. Without Git every change is
 * written to the audit log; with Git (#20 phase 2) every change becomes a commit that is pushed.
 */
@Service
public class ProcessCodeService {

  private final ProcessFiles files;
  private final ProcessGitService git;
  private final ProcessRepository processRepository;
  private final AuditLogService auditLogService;

  public ProcessCodeService(
      ProcessFiles files,
      ProcessGitService git,
      ProcessRepository processRepository,
      AuditLogService auditLogService) {
    this.files = files;
    this.git = git;
    this.processRepository = processRepository;
    this.auditLogService = auditLogService;
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<ProcessFiles.FileEntry> list(String folder) {
    return files.list(folder);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public String read(String folder, String path) {
    return files.read(folder, path);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public String save(String folder, String path, String content, String message, String user) {
    files.write(folder, path, content);
    return changed(
        folder,
        path,
        message == null || message.isBlank() ? "Update " + path : message,
        user,
        "EDIT_FILE");
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public String create(String folder, String path, String user) {
    if (files.exists(folder, path)) {
      throw new IllegalArgumentException(path + " already exists");
    }
    files.write(folder, path, template(path));
    return changed(folder, path, "Add " + path, user, "ADD_FILE");
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public String delete(String folder, String path, String user) {
    for (Process p : processRepository.findAll()) {
      if (p.folder().equals(folder)
          && p.steps() != null
          && p.steps().stream().anyMatch(s -> path.equals(s.scriptPath()))) {
        throw new IllegalArgumentException(path + " is used by a step of '" + p.name() + "'");
      }
    }
    files.delete(folder, path);
    return changed(folder, path, "Delete " + path, user, "DELETE_FILE");
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public String rename(String folder, String from, String to, String user) {
    for (Process p : processRepository.findAll()) {
      if (p.folder().equals(folder)
          && p.steps() != null
          && p.steps().stream().anyMatch(s -> from.equals(s.scriptPath()))) {
        throw new IllegalArgumentException(
            from + " is used by a step of '" + p.name() + "' - change the step first");
      }
    }
    files.rename(folder, from, to);
    return changed(folder, to, "Rename " + from + " to " + to, user, "RENAME_FILE");
  }

  /**
   * @return commit id when the change was committed to Git, otherwise null
   */
  private String changed(String folder, String path, String message, String user, String action) {
    String commit = git.commitAndPush(folder + "/" + path, message, user);
    processRepository
        .findByFolder(folder)
        .ifPresent(
            p ->
                auditLogService.recordProcess(
                    p.id(),
                    action,
                    user,
                    path + (commit == null ? "" : " (commit " + commit.substring(0, 8) + ")")));
    return commit;
  }

  private static String template(String path) {
    String lower = path.toLowerCase();
    if (lower.endsWith(".py")) {
      return "\"\"\"" + path + "\"\"\"\n\nfrom sdmxdw_sdk import param\n";
    }
    if (lower.endsWith(".r")) {
      return "# " + path + "\nsource(file.path(Sys.getenv(\"SDMXDW_SHARED_DIR\"), \"sdmxdw.R\"))\n";
    }
    return "";
  }
}
