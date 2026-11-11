package br.com.fiap.antidoto.ia;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O que a IA responde só vai para a recepção se passar nesta conferência (MVP-01 §8). */
class ResumoIaServiceTest {

    @Test
    void respostaNormalEAceitaSemEspacosNasPontas() {
        assertThat(ResumoIaService.validar("  Animal: escorpião\nUrgência: EMERGÊNCIA  "))
                .contains("Animal: escorpião\nUrgência: EMERGÊNCIA");
    }

    @Test
    void vaziaOuLongaDemaisEDescartada() {
        assertThat(ResumoIaService.validar(null)).isEmpty();
        assertThat(ResumoIaService.validar("   ")).isEmpty();
        assertThat(ResumoIaService.validar("a".repeat(1201))).isEmpty();
        assertThat(ResumoIaService.validar("a".repeat(1200))).isPresent();
    }

    @Test
    void falarDeDoseOuRemedioEDescartado() {
        assertThat(ResumoIaService.validar("Aplicar uma dose de soro")).isEmpty();
        assertThat(ResumoIaService.validar("Dar 10 mg de analgésico")).isEmpty();
        assertThat(ResumoIaService.validar("5 ml na veia")).isEmpty();
        assertThat(ResumoIaService.validar("Separar 2 AMPOLAS")).isEmpty();
        assertThat(ResumoIaService.validar("Medicamento para dor")).isEmpty();
    }

    @Test
    void palavrasQueSoContemAsLetrasNaoSaoBarradas() {
        // "ml" dentro de outra palavra, "dosagem" não é "dose"
        assertThat(ResumoIaService.validar("Ver o html do sistema; dosagem não informada")).isPresent();
    }
}
