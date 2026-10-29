package de.sdmxdw.process;

import de.sdmxdw.user.AppUser;
import de.sdmxdw.user.AppUserRepository;
import de.sdmxdw.user.AppUserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** E-mail on failed runs: to the process' address, otherwise to all Datenmanager. */
@Service
public class ProcessNotificationService {

  private static final Logger log = LoggerFactory.getLogger(ProcessNotificationService.class);

  private final JavaMailSender mailSender;
  private final AppUserRepository appUserRepository;

  public ProcessNotificationService(
      JavaMailSender mailSender, AppUserRepository appUserRepository) {
    this.mailSender = mailSender;
    this.appUserRepository = appUserRepository;
  }

  public void notifyFailure(Process process, ProcessRun run, String summary) {
    String[] recipients =
        process.notifyEmail() != null && !process.notifyEmail().isBlank()
            ? process.notifyEmail().split("\\s*,\\s*")
            : appUserRepository.findAll().stream()
                .filter(u -> u.role() == AppUserRole.DATENMANAGER)
                .map(AppUser::email)
                .toArray(String[]::new);
    if (recipients.length == 0) {
      return;
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setTo(recipients);
    message.setSubject("SDMxDW: process '" + process.name() + "' failed (run #" + run.id() + ")");
    message.setText(
        "The process '"
            + process.name()
            + "' failed (run #"
            + run.id()
            + ", "
            + run.trigger()
            + ", started "
            + run.startedAt()
            + ").\n\n"
            + summary);
    try {
      mailSender.send(message);
    } catch (MailException e) {
      log.warn("Could not send failure notification for run {}: {}", run.id(), e.getMessage());
    }
  }
}
