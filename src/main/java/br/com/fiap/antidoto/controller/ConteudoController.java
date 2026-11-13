package br.com.fiap.antidoto.controller;

import br.com.fiap.antidoto.dto.conteudo.PrimeirosSocorrosResponse;
import br.com.fiap.antidoto.model.TipoAnimal;
import br.com.fiap.antidoto.service.ConteudoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Conteúdo público do SOS (sem login, RN-G1). */
@RestController
@RequestMapping("/api/conteudo")
@Tag(name = "Conteudo")
@SecurityRequirements
public class ConteudoController {

    private final ConteudoService conteudoService;

    public ConteudoController(ConteudoService conteudoService) {
        this.conteudoService = conteudoService;
    }

    @GetMapping("/primeiros-socorros/{tipoAnimal}")
    @Operation(summary = "Primeiros socorros do animal: o que fazer, o que não fazer e os cuidados (público)")
    public ResponseEntity<PrimeirosSocorrosResponse> primeirosSocorros(@PathVariable TipoAnimal tipoAnimal) {
        return ResponseEntity.ok(conteudoService.primeirosSocorros(tipoAnimal));
    }
}
