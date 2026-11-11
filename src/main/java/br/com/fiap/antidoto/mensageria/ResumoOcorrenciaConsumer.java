package br.com.fiap.antidoto.mensageria;

import br.com.fiap.antidoto.ia.ResumoIaService;
import br.com.fiap.antidoto.ia.ResumoModeloPadrao;
import br.com.fiap.antidoto.model.Ocorrencia;
import br.com.fiap.antidoto.model.StatusResumo;
import br.com.fiap.antidoto.repository.OcorrenciaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Gera o resumo em segundo plano (MVP-01 §9, modo sem RabbitMQ): roda depois do
 * commit do POST, em outra thread, então o app recebe o 201 com PENDENTE na hora
 * e consulta até o status mudar. Com a IA ligada (IA_HABILITADA=true), tenta a IA
 * (PRONTO); se ela falhar ou estiver desligada, vale o modelo padrão (MODELO_PADRAO):
 * a ocorrência nunca fica sem resumo. Idempotente: se já não está PENDENTE, não faz nada.
 */
@Component
public class ResumoOcorrenciaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResumoOcorrenciaConsumer.class);

    private final OcorrenciaRepository ocorrenciaRepository;
    private final ResumoIaService resumoIaService;
    private final ResumoModeloPadrao resumoModeloPadrao;
    private final boolean iaHabilitada;
    private final Clock relogio;

    public ResumoOcorrenciaConsumer(
            OcorrenciaRepository ocorrenciaRepository,
            ResumoIaService resumoIaService,
            ResumoModeloPadrao resumoModeloPadrao,
            @Value("${antidoto.ia.habilitada}") boolean iaHabilitada,
            Clock relogio
    ) {
        this.ocorrenciaRepository = ocorrenciaRepository;
        this.resumoIaService = resumoIaService;
        this.resumoModeloPadrao = resumoModeloPadrao;
        this.iaHabilitada = iaHabilitada;
        this.relogio = relogio;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void gerarResumo(OcorrenciaCriadaEvento evento) {
        ocorrenciaRepository.findById(evento.ocorrenciaId())
                .filter(ocorrencia -> ocorrencia.getStatusResumo() == StatusResumo.PENDENTE)
                .ifPresent(this::preencherResumo);
    }

    private void preencherResumo(Ocorrencia ocorrencia) {
        long inicio = System.nanoTime();
        Instant agora = Instant.now(relogio);
        Optional<String> daIa = iaHabilitada ? resumoIaService.gerar(ocorrencia, agora) : Optional.empty();
        if (daIa.isPresent()) {
            ocorrencia.setResumo(daIa.get());
            ocorrencia.setStatusResumo(StatusResumo.PRONTO);
        } else {
            ocorrencia.setResumo(resumoModeloPadrao.montar(ocorrencia, agora));
            ocorrencia.setStatusResumo(StatusResumo.MODELO_PADRAO);
        }
        // Nunca logar o texto do resumo: só o id, o status e o tempo (MVP-01 §8)
        log.info("Resumo da ocorrência {}: {} em {} ms", ocorrencia.getId(), ocorrencia.getStatusResumo(),
                (System.nanoTime() - inicio) / 1_000_000);
    }
}
