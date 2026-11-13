package br.com.fiap.antidoto.mensageria;

/** A triagem de uma ocorrência mudou (PUT) e o resumo de pré-atendimento precisa ser gerado de novo. */
public record OcorrenciaAtualizadaEvento(
        Long ocorrenciaId
) {
}
