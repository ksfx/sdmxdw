package de.sdmxdw.security;

import de.sdmxdw.user.AppUser;
import de.sdmxdw.user.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

  private final AppUserRepository appUserRepository;

  public AppUserDetailsService(AppUserRepository appUserRepository) {
    this.appUserRepository = appUserRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    AppUser appUser =
        appUserRepository
            .findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown email: " + email));
    return new AppUserPrincipal(appUser);
  }
}
