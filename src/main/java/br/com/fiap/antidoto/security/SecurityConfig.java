package br.com.fiap.antidoto.security;

import br.com.fiap.antidoto.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Segurança (MVP-01 §5): stateless, JWT, CSRF desligado (não há cookie de sessão).
 * Públicas: cadastro, login, unidades (o SOS funciona sem login, RN-G1), saúde e Swagger.
 * O resto exige login. 401 e 403 saem no formato do contrato (RespostasDeSeguranca).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain cadeiaDeSeguranca(
            HttpSecurity http,
            JwtService jwtService,
            UsuarioRepository usuarioRepository,
            RespostasDeSeguranca respostasDeSeguranca
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(erros -> erros
                        .authenticationEntryPoint(respostasDeSeguranca)
                        .accessDeniedHandler(respostasDeSeguranca))
                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.POST, "/api/auth/cadastro", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/unidades", "/api/unidades/proximas", "/api/unidades/{id}",
                                "/api/conteudo/primeiros-socorros/{tipoAnimal}").permitAll()
                        .requestMatchers("/actuator/health", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtFiltro(jwtService, usuarioRepository),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Senhas com BCrypt (MVP-01 §5). */
    @Bean
    public PasswordEncoder codificadorDeSenha() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS só para o app no navegador (Expo Web) e o Swagger; o app no celular não usa.
     * As origens vêm de CORS_ORIGENS, separadas por vírgula. Nunca "*".
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${antidoto.cors.origens}") String origens) {
        CorsConfiguration configuracao = new CorsConfiguration();
        configuracao.setAllowedOrigins(Arrays.stream(origens.split(","))
                .map(String::trim)
                .filter(origem -> !origem.isEmpty())
                .toList());
        configuracao.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuracao.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource fonte = new UrlBasedCorsConfigurationSource();
        fonte.registerCorsConfiguration("/**", configuracao);
        return fonte;
    }
}
