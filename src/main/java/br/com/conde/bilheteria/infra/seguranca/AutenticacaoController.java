package br.com.conde.bilheteria.infra.seguranca;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Emite tokens para os usuários de demonstração configurados em bilheteria.usuarios. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação")
public class AutenticacaoController {

    @ConfigurationProperties(prefix = "bilheteria")
    public record Usuarios(List<Usuario> usuarios) {
        public record Usuario(String nome, String senha, List<String> papeis) {
        }
    }

    public record Credenciais(@NotBlank String usuario, @NotBlank String senha) {
    }

    public record Token(String token, Instant expiraEm, List<String> papeis) {
    }

    private final JwtEncoder encoder;
    private final Usuarios usuarios;
    private final Clock relogio;

    public AutenticacaoController(JwtEncoder encoder, Usuarios usuarios, Clock relogio) {
        this.encoder = encoder;
        this.usuarios = usuarios;
        this.relogio = relogio;
    }

    @PostMapping("/token")
    @Operation(summary = "Emite um JWT (HS256, 8 horas) para um usuário de demonstração")
    public ResponseEntity<Token> token(@RequestBody Credenciais credenciais) {
        var usuario = usuarios.usuarios().stream()
            .filter(u -> u.nome().equals(credenciais.usuario()) && iguais(u.senha(), credenciais.senha()))
            .findFirst();
        if (usuario.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        var agora = Instant.now(relogio);
        var expira = agora.plus(Duration.ofHours(8));
        var claims = JwtClaimsSet.builder()
            .issuer("bilheteria")
            .subject(usuario.get().nome())
            .issuedAt(agora)
            .expiresAt(expira)
            .claim("papeis", usuario.get().papeis())
            .build();
        var jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return ResponseEntity.ok(new Token(jwt.getTokenValue(), expira, usuario.get().papeis()));
    }

    private static boolean iguais(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
