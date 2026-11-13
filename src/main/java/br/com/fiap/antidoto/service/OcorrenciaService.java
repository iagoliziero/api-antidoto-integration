package br.com.fiap.antidoto.service;

import br.com.fiap.antidoto.dto.PaginaResponse;
import br.com.fiap.antidoto.dto.ocorrencia.OcorrenciaRequest;
import br.com.fiap.antidoto.dto.ocorrencia.OcorrenciaResponse;
import br.com.fiap.antidoto.dto.ocorrencia.ResultadoUrgencia;
import br.com.fiap.antidoto.exception.ConflitoException;
import br.com.fiap.antidoto.exception.RecursoNaoEncontradoException;
import br.com.fiap.antidoto.exception.RegraDeNegocioException;
import br.com.fiap.antidoto.mapper.OcorrenciaMapper;
import br.com.fiap.antidoto.mensageria.OcorrenciaAtualizadaEvento;
import br.com.fiap.antidoto.mensageria.OcorrenciaCriadaEvento;
import br.com.fiap.antidoto.model.Ocorrencia;
import br.com.fiap.antidoto.model.Sintoma;
import br.com.fiap.antidoto.model.StatusResumo;
import br.com.fiap.antidoto.model.UnidadeSaude;
import br.com.fiap.antidoto.repository.OcorrenciaRepository;
import br.com.fiap.antidoto.repository.UnidadeSaudeRepository;
import br.com.fiap.antidoto.repository.UsuarioRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

/** Ocorrências (MVP-01 §6.3): registrar, histórico, detalhe, atualizar e remover. Só do dono. */
@Service
public class OcorrenciaService {

    /** RN-G4: sem futuro (5 min de tolerância) e até 72 h atrás. */
    private static final Duration TOLERANCIA_DO_FUTURO = Duration.ofMinutes(5);
    private static final Duration LIMITE_DO_PASSADO = Duration.ofHours(72);

    private final OcorrenciaRepository ocorrenciaRepository;
    private final UnidadeSaudeRepository unidadeRepository;
    private final UsuarioRepository usuarioRepository;
    private final UrgenciaService urgenciaService;
    private final OcorrenciaMapper ocorrenciaMapper;
    private final ApplicationEventPublisher publicadorDeEventos;
    private final Clock relogio;

    public OcorrenciaService(
            OcorrenciaRepository ocorrenciaRepository,
            UnidadeSaudeRepository unidadeRepository,
            UsuarioRepository usuarioRepository,
            UrgenciaService urgenciaService,
            OcorrenciaMapper ocorrenciaMapper,
            ApplicationEventPublisher publicadorDeEventos,
            Clock relogio
    ) {
        this.ocorrenciaRepository = ocorrenciaRepository;
        this.unidadeRepository = unidadeRepository;
        this.usuarioRepository = usuarioRepository;
        this.urgenciaService = urgenciaService;
        this.ocorrenciaMapper = ocorrenciaMapper;
        this.publicadorDeEventos = publicadorDeEventos;
        this.relogio = relogio;
    }

    /**
     * Confere a RN-G4 e a unidade, calcula a urgência (RN-U) e salva com o resumo
     * PENDENTE. O resumo é gerado depois do commit, em segundo plano
     * (ResumoOcorrenciaConsumer): o 201 sai na hora.
     */
    @Transactional
    public OcorrenciaResponse criar(Long idUsuario, OcorrenciaRequest requisicao) {
        Instant agora = Instant.now(relogio).truncatedTo(ChronoUnit.MILLIS);

        Ocorrencia ocorrencia = new Ocorrencia();
        ocorrencia.setUsuario(usuarioRepository.getReferenceById(idUsuario));
        preencherTriagem(ocorrencia, requisicao, agora);
        ocorrencia.setCriadoEm(agora);
        ocorrenciaRepository.save(ocorrencia);

        publicadorDeEventos.publishEvent(new OcorrenciaCriadaEvento(ocorrencia.getId()));
        return ocorrenciaMapper.paraResponse(ocorrencia);
    }

    /**
     * Atualiza a triagem (ex.: sintomas novos ou outra unidade de destino): confere de
     * novo a RN-G4 e a unidade, recalcula a urgência (RN-U) com a hora de agora e pede
     * um resumo novo (PENDENTE, gerado depois do commit). Enquanto o resumo anterior
     * ainda está sendo gerado, recusa (409): o resumo antigo poderia ficar por cima do novo.
     */
    @Transactional
    public OcorrenciaResponse atualizar(Long idUsuario, Long id, OcorrenciaRequest requisicao) {
        Ocorrencia ocorrencia = carregarDoDono(idUsuario, id);
        if (ocorrencia.getStatusResumo() == StatusResumo.PENDENTE) {
            throw new ConflitoException("O resumo ainda está sendo gerado. Tente de novo em alguns segundos.");
        }
        preencherTriagem(ocorrencia, requisicao, Instant.now(relogio).truncatedTo(ChronoUnit.MILLIS));
        ocorrencia.setResumo(null);

        publicadorDeEventos.publishEvent(new OcorrenciaAtualizadaEvento(ocorrencia.getId()));
        return ocorrenciaMapper.paraResponse(ocorrencia);
    }

    /** As do usuário logado, mais recentes primeiro. */
    @Transactional(readOnly = true)
    public PaginaResponse<OcorrenciaResponse> listarMinhas(Long idUsuario, int pagina, int tamanho) {
        var pageable = PageRequest.of(pagina, tamanho,
                Sort.by(Sort.Order.desc("criadoEm"), Sort.Order.desc("id")));
        return PaginaResponse.de(ocorrenciaRepository.findByUsuarioId(idUsuario, pageable)
                .map(ocorrenciaMapper::paraResponse));
    }

    /** O endpoint que o app consulta a cada 3 s até o resumo ficar pronto. */
    @Transactional(readOnly = true)
    public OcorrenciaResponse buscar(Long idUsuario, Long id) {
        return ocorrenciaMapper.paraResponse(carregarDoDono(idUsuario, id));
    }

    @Transactional
    public void excluir(Long idUsuario, Long id) {
        ocorrenciaRepository.delete(carregarDoDono(idUsuario, id));
    }

    /** O que o registro e a atualização têm em comum: as regras, a urgência e os campos da triagem. */
    private void preencherTriagem(Ocorrencia ocorrencia, OcorrenciaRequest requisicao, Instant agora) {
        conferirHoraPicada(requisicao.horaPicada(), agora);
        UnidadeSaude unidade = unidadeDeDestino(requisicao.unidadeId());

        Set<Sintoma> sintomas = EnumSet.noneOf(Sintoma.class);
        sintomas.addAll(requisicao.sintomas());
        ResultadoUrgencia resultado = urgenciaService.calcular(
                requisicao.tipoAnimal(), requisicao.faixaEtaria(), sintomas, requisicao.horaPicada(), agora);

        ocorrencia.setUnidade(unidade);
        ocorrencia.setTipoAnimal(requisicao.tipoAnimal());
        ocorrencia.setFaixaEtaria(requisicao.faixaEtaria());
        ocorrencia.setSintomas(sintomas);
        ocorrencia.setHoraPicada(requisicao.horaPicada().truncatedTo(ChronoUnit.MILLIS));
        ocorrencia.setLatitude(requisicao.latitude());
        ocorrencia.setLongitude(requisicao.longitude());
        ocorrencia.setUrgencia(resultado.urgencia());
        ocorrencia.setMotivos(OcorrenciaMapper.motivosParaColuna(resultado.motivos()));
        ocorrencia.setStatusResumo(StatusResumo.PENDENTE);
    }

    private Ocorrencia carregarDoDono(Long idUsuario, Long id) {
        return ocorrenciaRepository.findByIdAndUsuarioId(id, idUsuario)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ocorrência não encontrada."));
    }

    private static void conferirHoraPicada(Instant horaPicada, Instant agora) {
        boolean noFuturo = horaPicada.isAfter(agora.plus(TOLERANCIA_DO_FUTURO));
        boolean antigaDemais = horaPicada.isBefore(agora.minus(LIMITE_DO_PASSADO));
        if (noFuturo || antigaDemais) {
            throw new RegraDeNegocioException("horaPicada", "A hora da picada precisa estar entre agora e 72 horas atrás");
        }
    }

    /** A unidade escolhida no resultado precisa existir e estar ativa (senão, 404). */
    private UnidadeSaude unidadeDeDestino(Long unidadeId) {
        if (unidadeId == null) {
            return null;
        }
        return unidadeRepository.findByIdAndAtivaTrue(unidadeId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Unidade não encontrada."));
    }
}
