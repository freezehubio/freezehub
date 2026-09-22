package com.freezhub.shared.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserResolvingJwtAuthenticationConverter converter)
            throws Exception {
        http
                // Enabled so preflight is answered before authorization runs; an OPTIONS
                // request carries no Authorization header and would otherwise be a 401,
                // making every browser call fail before it was ever sent.
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // An error is already the outcome of a request that was authorized
                        // (or refused) on its way in; re-authorizing the forward to /error
                        // only replaces that outcome with a worse one. It broke the machine
                        // chain in particular: /error does not match /api/policy/**, so a
                        // 400 from a policy call fell through to this chain, which found no
                        // JWT and answered 401 — telling CI its credential was bad when the
                        // request was. Verified live; MockMvc does not forward to /error, so
                        // no controller test could have shown it (FZ-052).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // The sub-paths matter: with probes enabled the load balancer
                        // reads /actuator/health/readiness, and an exact match on
                        // /actuator/health would answer it with a 401 (FZ-062).
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Everything else under /actuator is administrator-only (`FZ-065`).
                        // `metrics` and `info` are aggregate across every tenant — a member
                        // of one organization could read how many deployment checks all of
                        // them make. Authentication alone was never the right bar for that.
                        // The real fix is a separate management port the internet cannot
                        // reach at all, which is deployment work (`OI-21`); this is the part
                        // that costs nothing and shrinks the audience today.
                        .requestMatchers("/actuator/**").hasRole("ADMINISTRATOR")
                        // "Book a demo" (FZ-083). Unauthenticated by necessity — the
                        // person filling it in has no account yet, which is the point.
                        //
                        // POST only, and no read anywhere: the table sits outside the
                        // tenant boundary, so there is no organization to scope a read to.
                        // Rate limited by FZ-087, which is why that story came first.
                        .requestMatchers(HttpMethod.POST, "/api/demo-requests").permitAll()
                        // "Start free trial" (FZ-082). Unauthenticated for the same reason
                        // and with the same shape: POST only, no read anywhere, rate
                        // limited by FZ-087 — which already lists this path.
                        //
                        // It answers 202 identically whether or not anything was created,
                        // so permitting it reveals nothing that refusing it would hide.
                        .requestMatchers(HttpMethod.POST, "/api/signup").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));

        return http.build();
    }

}
