package ar.edu.utn.frc.aerogest.flight.config;

import ar.edu.utn.frc.aerogest.common.security.KeycloakRealmRoleConverter;
import ar.edu.utn.frc.aerogest.common.web.ServletProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de Flight Service (RN-09). Es un "resource server": no emite tokens, valida los
 * JWT que emite Keycloak y autoriza por rol.
 *
 *  /internal/**                -> rol SERVICE (credencial de Reservation Service)
 *  GET  /api/v1/flights/**     -> USER o ADMIN
 *  resto de /api/v1/flights/** -> ADMIN
 *  documentación y health      -> públicos
 *  cualquier otra ruta         -> autenticado
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())   // API sin sesión ni cookies: no aplica CSRF
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/flights/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/v1/flights/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            ServletProblemWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                    "Falta un token válido o el token expiró");
                        })
                        .accessDeniedHandler((request, response, ex) ->
                                ServletProblemWriter.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                        "No tenés permisos para realizar esta operación")));
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
