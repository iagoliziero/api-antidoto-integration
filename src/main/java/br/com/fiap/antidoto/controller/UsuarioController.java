package br.com.fiap.antidoto.controller;

import br.com.fiap.antidoto.dto.usuario.AlterarSenhaRequest;
import br.com.fiap.antidoto.dto.usuario.AtualizarUsuarioRequest;
import br.com.fiap.antidoto.dto.usuario.UsuarioResponse;
import br.com.fiap.antidoto.security.UsuarioAutenticado;
import br.com.fiap.antidoto.service.UsuarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usuarios")
@Tag(name = "Usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping("/me")
    @Operation(summary = "Dados do usuário logado")
    public ResponseEntity<UsuarioResponse> buscarLogado(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario) {
        return ResponseEntity.ok(usuarioService.buscar(usuario.id()));
    }

    @PutMapping("/me")
    @Operation(summary = "Atualiza nome e telefone")
    public ResponseEntity<UsuarioResponse> atualizarLogado(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario,
            @Valid @RequestBody AtualizarUsuarioRequest requisicao) {
        return ResponseEntity.ok(usuarioService.atualizar(usuario.id(), requisicao));
    }

    @PutMapping("/me/senha")
    @Operation(summary = "Troca a senha (confere a senha atual). 204 sem corpo")
    public ResponseEntity<Void> alterarSenha(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario,
            @Valid @RequestBody AlterarSenhaRequest requisicao) {
        usuarioService.alterarSenha(usuario.id(), requisicao);
        return ResponseEntity.noContent().build();
    }
}
