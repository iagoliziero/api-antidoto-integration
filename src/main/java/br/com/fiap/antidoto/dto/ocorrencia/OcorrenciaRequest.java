package br.com.fiap.antidoto.dto.ocorrencia;

import br.com.fiap.antidoto.model.FaixaEtaria;
import br.com.fiap.antidoto.model.Sintoma;
import br.com.fiap.antidoto.model.TipoAnimal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * POST /api/ocorrencias e PUT /api/ocorrencias/{id}: as respostas da triagem, as coordenadas do GPS e, se a
 * pessoa escolheu, a unidade de destino. A RN-G4 (hora da picada) é conferida no
 * service, com o relógio da API.
 */
public record OcorrenciaRequest(

        @NotNull(message = "Informe o animal")
        TipoAnimal tipoAnimal,

        @NotNull(message = "Informe a faixa etária")
        FaixaEtaria faixaEtaria,

        /** Pode ser vazia. */
        @NotNull(message = "Informe os sintomas (a lista pode ser vazia)")
        List<@NotNull(message = "Sintoma inválido") Sintoma> sintomas,

        @NotNull(message = "Informe a hora da picada")
        Instant horaPicada,

        @NotNull(message = "Informe a localização")
        @DecimalMin(value = "-34", message = "A localização precisa ser no Brasil")
        @DecimalMax(value = "6", message = "A localização precisa ser no Brasil")
        Double latitude,

        @NotNull(message = "Informe a localização")
        @DecimalMin(value = "-74", message = "A localização precisa ser no Brasil")
        @DecimalMax(value = "-34", message = "A localização precisa ser no Brasil")
        Double longitude,

        Long unidadeId

) {
}
