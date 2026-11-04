package ar.edu.utn.frc.aerogest.reservation.config;

import ar.edu.utn.frc.aerogest.common.security.KeycloakRealmRoleConverter;
import ar.edu.utn.frc.aerogest.common.web.ServletProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de Reservation Service (RN-09). Es un "resource server": valida los JWT de Keycloak
 * y autoriza por rol.
 *
 *  /api/v1/passengers/**, /api/v1/reservations/** -> USER o ADMIN
 *  documentación y health                          -> públicos
 *  cualquier otra ruta                             -> autenticado
 *
 * El rol alcanza para entrar; qué pasajeros y reservas puede tocar cada usuario (las suyas, o
 * todas si es ADMIN) lo deciden los servicios, porque depende de los datos (claim "sub").
 * El rol SERVICE no tiene acceso a la API pública.
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
                        .requestMatchers("/api/v1/passengers/**", "/api/v1/reservations/**")
                        .hasAnyRole("USER", "ADMIN")
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
