package de.sdmxdw.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Read API for scripts: personal API token (Bearer or Basic password) or an existing browser
   * session. Answers 401 instead of redirecting to the login page, never creates a session, and
   * needs no CSRF protection because it only serves GET requests.
   */
  @Bean
  @Order(1)
  public SecurityFilterChain restApiFilterChain(
      HttpSecurity http, ApiTokenService apiTokenService, RunCredentialService runCredentialService)
      throws Exception {
    http.securityMatcher("/rest/**")
        .authorizeHttpRequests(
            authorize ->
                authorize.requestMatchers(HttpMethod.GET).authenticated().anyRequest().denyAll())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
        .csrf(csrf -> csrf.disable())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, e) ->
                            ApiTokenAuthenticationFilter.unauthorized(
                                response,
                                "Authentication required: send a personal API token as"
                                    + " 'Authorization: Bearer sdw_...'"))
                    .accessDeniedHandler(
                        (request, response, e) -> {
                          response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                          response.setContentType("text/plain;charset=UTF-8");
                          response.getWriter().write("Access denied: " + e.getMessage() + "\n");
                        }))
        .addFilterBefore(
            new ApiTokenAuthenticationFilter(apiTokenService, runCredentialService),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/login", "/css/**", "/js/**", "/img/**", "/actuator/health")
                    .permitAll()
                    .requestMatchers("/users/**", "/processes/**")
                    .hasRole("DATENMANAGER")
                    .anyRequest()
                    .authenticated())
        .formLogin(form -> form.loginPage("/login").permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
        .addFilterAfter(
            new ForcePasswordChangeFilter(), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
