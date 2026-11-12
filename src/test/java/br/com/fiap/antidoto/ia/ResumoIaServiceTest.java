package br.com.fiap.antidoto.ia;

import br.com.fiap.antidoto.model.Urgencia;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O que a IA responde só vai para a recepção se passar nesta conferência (MVP-01 §8). */
class ResumoIaServiceTest {

    private static final String RESPOSTA_BOA = """
            Animal: Escorpião
            Picada: 20:33 (há 20 min)
            Faixa etária: criança
            Sintomas: Vômitos repetidos (ALERTA)
            Urgência: EMERGÊNCIA — Sinal de alerta; Escorpião em criança
            Destino: Hospital Exemplo Centro""";

    private static boolean aceita(String resposta, Urgencia urgencia, boolean temAlerta) {
        return ResumoIaService.validar(resposta, urgencia, temAlerta).isPresent();
    }

    @Test
    void respostaFielEAceitaSemEspacosNemLinhasEmBranco() {
        String comSobras = "  Animal: Escorpião  \n\nPicada: 20:33 (há 20 min)  \nUrgência: EMERGÊNCIA — Sinal de alerta  ";

        assertThat(ResumoIaService.validar(comSobras, Urgencia.EMERGENCIA, true))
                .contains("Animal: Escorpião\nPicada: 20:33 (há 20 min)\nUrgência: EMERGÊNCIA — Sinal de alerta");
        assertThat(aceita(RESPOSTA_BOA, Urgencia.EMERGENCIA, true)).isTrue();
    }

    @Test
    void vaziaOuLongaDemaisEDescartada() {
        assertThat(aceita(null, Urgencia.ALTA, false)).isFalse();
        assertThat(aceita("   ", Urgencia.ALTA, false)).isFalse();
        assertThat(aceita("Urgência: ALTA " + "a".repeat(1200), Urgencia.ALTA, false)).isFalse();
    }

    @Test
    void falarDeDoseOuRemedioEDescartado() {
        assertThat(aceita("Urgência: ALTA. Aplicar uma dose de soro", Urgencia.ALTA, false)).isFalse();
        assertThat(aceita("Urgência: ALTA. Dar 10 mg de analgésico", Urgencia.ALTA, false)).isFalse();
        assertThat(aceita("Urgência: ALTA. 5 ml na veia", Urgencia.ALTA, false)).isFalse();
        assertThat(aceita("Urgência: ALTA. Separar 2 AMPOLAS", Urgencia.ALTA, false)).isFalse();
        assertThat(aceita("Urgência: ALTA. Medicamento para dor", Urgencia.ALTA, false)).isFalse();
    }

    @Test
    void palavrasQueSoContemAsLetrasNaoSaoBarradas() {
        // "ml" dentro de outra palavra, "dosagem" não é "dose", "falta" não é "ALTA"
        assertThat(aceita("Urgência: MODERADA. Ver o html; dosagem e falta de dados", Urgencia.MODERADA, false)).isTrue();
    }

    @Test
    void outroNivelDeUrgenciaEDescartado() {
        // A IA nunca decide a urgência (MVP-01 §8)
        assertThat(aceita(RESPOSTA_BOA, Urgencia.ALTA, true)).isFalse();
        assertThat(aceita("Urgência: alta", Urgencia.MODERADA, false)).isFalse();
        assertThat(aceita("Urgência: EMERGENCIA, talvez ALTA", Urgencia.EMERGENCIA, true)).isFalse();
        assertThat(aceita("Animal: Cobra\nDestino: UPA", Urgencia.ALTA, false)).isFalse();
        assertThat(aceita("Urgência: Emergencia", Urgencia.EMERGENCIA, true)).isTrue();
    }

    @Test
    void alertaSemSintomaDeAlertaEDescartado() {
        // Caso visto no teste com o Groq: dor e inchaço viraram "sinais de alerta"
        String inventada = "Sintomas: dor e inchaço no local (sinais de alerta)\nUrgência: ALTA";

        assertThat(aceita(inventada, Urgencia.ALTA, false)).isFalse();
        assertThat(aceita(inventada, Urgencia.ALTA, true)).isTrue();
    }

    @Test
    void campoVazioEDescartado() {
        assertThat(aceita("Urgência: EMERGÊNCIA — Sinal de alerta\nDestino:", Urgencia.EMERGENCIA, true)).isFalse();
        assertThat(aceita("Urgência: EMERGÊNCIA — Sinal de alerta\nDestino: não escolhido", Urgencia.EMERGENCIA, true)).isTrue();
    }
}
