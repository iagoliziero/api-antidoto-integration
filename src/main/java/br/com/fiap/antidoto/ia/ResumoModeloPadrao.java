package br.com.fiap.antidoto.ia;

import br.com.fiap.antidoto.model.Ocorrencia;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Resumo de pré-atendimento montado por modelo de texto (MVP-01 §8, MODELO_PADRAO).
 * Só com os dados da triagem: nunca nome, e-mail, telefone ou coordenadas (RN-G6),
 * e nunca dose ou remédio. Exemplo:
 * <pre>
 * PRÉ-ATENDIMENTO — Antídoto
 * Animal: Escorpião · Faixa etária: criança
 * Picada: 22:15 (há 25 min)
 * Sintomas: Vômitos repetidos (ALERTA)
 * Urgência: EMERGÊNCIA — Sinal de alerta; Escorpião em criança
 * Destino: Hospital Exemplo Centro
 * </pre>
 */
@Component
public class ResumoModeloPadrao {

    /** O app é do Brasil: a hora da picada sai no horário de Brasília. */
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter SO_HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    public String montar(Ocorrencia ocorrencia, Instant agora) {
        return "PRÉ-ATENDIMENTO — Antídoto\n" + dadosDaTriagem(ocorrencia, agora);
    }

    /**
     * As 5 linhas de dados (sem o cabeçalho). É também o que vai para a IA
     * (ResumoIaService): só dados da triagem, nada que identifique a pessoa.
     */
    public String dadosDaTriagem(Ocorrencia ocorrencia, Instant agora) {
        return String.join("\n",
                "Animal: " + ocorrencia.getTipoAnimal().getRotulo()
                        + " · Faixa etária: " + ocorrencia.getFaixaEtaria().getRotulo(),
                "Picada: " + horaDaPicada(ocorrencia.getHoraPicada(), agora)
                        + " (" + tempoDecorrido(Duration.between(ocorrencia.getHoraPicada(), agora)) + ")",
                "Sintomas: " + sintomas(ocorrencia),
                "Urgência: " + ocorrencia.getUrgencia().getRotulo() + " — "
                        + ocorrencia.getMotivos().replace(";", "; "),
                "Destino: " + (ocorrencia.getUnidade() == null ? "não escolhido" : ocorrencia.getUnidade().getNome())
        );
    }

    private static String horaDaPicada(Instant horaPicada, Instant agora) {
        var picada = horaPicada.atZone(FUSO);
        boolean mesmoDia = picada.toLocalDate().equals(agora.atZone(FUSO).toLocalDate());
        return (mesmoDia ? SO_HORA : DIA_E_HORA).format(picada);
    }

    /** "há 25 min", "há 3 h", "há 4 h 10 min". */
    static String tempoDecorrido(Duration decorrido) {
        long minutos = Math.max(0, decorrido.toMinutes());
        if (minutos < 60) {
            return "há " + minutos + " min";
        }
        long horas = minutos / 60;
        long resto = minutos % 60;
        return resto == 0 ? "há " + horas + " h" : "há " + horas + " h " + resto + " min";
    }

    private static String sintomas(Ocorrencia ocorrencia) {
        if (ocorrencia.getSintomas().isEmpty()) {
            return "nenhum informado";
        }
        return ocorrencia.getSintomas().stream()
                .sorted()
                .map(sintoma -> sintoma.getTexto() + (sintoma.isAlerta() ? " (ALERTA)" : ""))
                .collect(Collectors.joining("; "));
    }
}
