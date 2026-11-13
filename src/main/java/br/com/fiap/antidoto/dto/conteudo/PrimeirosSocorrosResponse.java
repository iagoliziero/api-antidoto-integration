package br.com.fiap.antidoto.dto.conteudo;

import br.com.fiap.antidoto.model.TipoAnimal;

import java.util.List;

/** Schema PrimeirosSocorros do contrato: o que fazer, o que não fazer e os cuidados do animal. */
public record PrimeirosSocorrosResponse(
        TipoAnimal tipoAnimal,
        List<String> faca,
        List<String> naoFaca,
        List<String> atencao
) {
}
