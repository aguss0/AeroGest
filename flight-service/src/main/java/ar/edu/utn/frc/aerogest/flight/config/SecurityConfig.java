package ar.edu.utn.frc.aerogest.flight.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                                    "UNAUTHENTICATED", "Falta un token válido o el token expiró");
                        })
                        .accessDeniedHandler((request, response, ex) ->
                                writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
                                        "ACCESS_DENIED", "No tenés permisos para realizar esta operación")));
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /** Respuestas 401/403 con el mismo formato (RFC 7807 + code) que el resto de los errores. */
    private static void writeProblem(HttpServletResponse response, int status, String title,
                                     String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status
                + ",\"detail\":\"" + detail
                + "\",\"code\":\"" + code + "\"}");
    }
}
