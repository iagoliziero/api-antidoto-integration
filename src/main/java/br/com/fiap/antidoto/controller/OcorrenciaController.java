package br.com.fiap.antidoto.controller;

import br.com.fiap.antidoto.dto.PaginaResponse;
import br.com.fiap.antidoto.dto.ocorrencia.OcorrenciaRequest;
import br.com.fiap.antidoto.dto.ocorrencia.OcorrenciaResponse;
import br.com.fiap.antidoto.security.UsuarioAutenticado;
import br.com.fiap.antidoto.service.OcorrenciaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ocorrências (logado; só as do próprio usuário). */
@RestController
@RequestMapping("/api/ocorrencias")
@Tag(name = "Ocorrencias")
public class OcorrenciaController {

    private final OcorrenciaService ocorrenciaService;

    public OcorrenciaController(OcorrenciaService ocorrenciaService) {
        this.ocorrenciaService = ocorrenciaService;
    }

    @PostMapping
    @Operation(summary = "Registra a ocorrência. Calcula a urgência (RN-U) e gera o resumo em segundo plano")
    public ResponseEntity<OcorrenciaResponse> criar(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario,
            @Valid @RequestBody OcorrenciaRequest requisicao) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ocorrenciaService.criar(usuario.id(), requisicao));
    }

    @GetMapping("/minhas")
    @Operation(summary = "Histórico de ocorrências do usuário (mais recentes primeiro)")
    public ResponseEntity<PaginaResponse<OcorrenciaResponse>> listarMinhas(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "A página começa em 0") int pagina,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "O tamanho vai de 1 a 50")
            @Max(value = 50, message = "O tamanho vai de 1 a 50")
            int tamanho) {
        return ResponseEntity.ok(ocorrenciaService.listarMinhas(usuario.id(), pagina, tamanho));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalhe (somente o dono; outro usuário → 404)")
    public ResponseEntity<OcorrenciaResponse> buscar(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario,
            @PathVariable Long id) {
        return ResponseEntity.ok(ocorrenciaService.buscar(usuario.id(), id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza a triagem (ex.: sintomas novos ou outra unidade). Recalcula a urgência e gera um resumo novo (somente o dono)")
    public ResponseEntity<OcorrenciaResponse> atualizar(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario,
            @PathVariable Long id,
            @Valid @RequestBody OcorrenciaRequest requisicao) {
        return ResponseEntity.ok(ocorrenciaService.atualizar(usuario.id(), id, requisicao));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove do histórico (somente o dono)")
    public ResponseEntity<Void> excluir(
            @Parameter(hidden = true) @AuthenticationPrincipal UsuarioAutenticado usuario,
            @PathVariable Long id) {
        ocorrenciaService.excluir(usuario.id(), id);
        return ResponseEntity.noContent().build();
    }
}
