package ar.edu.utn.frc.aerogest.gateway.config;

import ar.edu.utn.frc.aerogest.common.security.KeycloakRealmRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Security Filter del gateway (ADR 0005). Es la primera barrera: valida el JWT de Keycloak
 * (firma con el JWKS, issuer y vencimiento) y autoriza por rol y ruta antes de reenviar.
 * Los servicios vuelven a validar el token y deciden sobre los datos (dueño por "sub"):
 * el gateway no reemplaza esa segunda barrera.
 *
 *  GET  /api/v1/flights/**                        -> USER o ADMIN
 *  resto de /api/v1/flights/**                    -> ADMIN
 *  /api/v1/passengers/**, /api/v1/reservations/** -> USER o ADMIN
 *  documentación y health                         -> públicos
 *  /internal/** y cualquier otra ruta             -> denegado: el gateway solo deja pasar lo
 *                                                    que publica (el rol SERVICE no entra)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)   // API sin sesión ni cookies: no aplica CSRF
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())   // sin sesión
                .authorizeExchange(auth -> auth
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/webjars/**", "/v3/api-docs/**",
                                "/api-docs/**", "/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/flights/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/api/v1/flights/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/passengers/**", "/api/v1/reservations/**").hasAnyRole("USER", "ADMIN")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                            return GatewayProblemWriter.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                    "Falta un token válido o el token expiró");
                        })
                        .accessDeniedHandler((exchange, ex) ->
                                GatewayProblemWriter.write(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                        "No tenés permisos para realizar esta operación")))
                // Mismo formato cuando se deniega sin haber llegado a validar un token
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, e) -> {
                            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                            return GatewayProblemWriter.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                    "Falta un token válido o el token expiró");
                        })
                        .accessDeniedHandler((exchange, e) ->
                                GatewayProblemWriter.write(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                        "No tenés permisos para realizar esta operación")));
        return http.build();
    }

    /** Los mismos roles que en los servicios: el conversor viene del componente común. */
    @Bean
    public ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
