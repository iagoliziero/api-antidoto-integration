package br.com.fiap.antidoto.service;

import br.com.fiap.antidoto.dto.conteudo.PrimeirosSocorrosResponse;
import br.com.fiap.antidoto.model.TipoAnimal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Primeiros socorros por animal (MVP-02 §6). O texto saiu do app para a API, para o
 * app mostrar só dado que vem do back end.
 *
 * ⚠️ Revisar com o material do Ministério da Saúde antes da apresentação.
 * NUNCA escrever dose, remédio ou tratamento aqui (MVP-00 §1).
 */
@Service
public class ConteudoService {

    private static final List<String> FACA = List.of(
            "Afaste-se do animal.",
            "Lave o local com água e sabão.",
            "Mantenha a vítima em repouso.",
            "Tire anéis, pulseiras e calçados apertados.",
            "Vá já para a unidade de saúde indicada.");

    private static final List<String> NAO_FACA = List.of(
            "Amarrar o local ou fazer torniquete.",
            "Cortar, furar ou espremer o local.",
            "Sugar o veneno.",
            "Colocar folhas, pó de café, terra, álcool ou qualquer outra coisa.",
            "Dar bebida alcoólica ou remédio caseiro.",
            "Esperar os sintomas aparecerem.");

    private static final String NAO_CAPTURAR = "Não tente capturar o animal. Se for seguro, tire uma foto de longe.";

    private static final Map<TipoAnimal, List<String>> ATENCAO = Map.of(
            TipoAnimal.ESCORPIAO, List.of(
                    "Compressa morna pode aliviar a dor no caminho.",
                    "Crianças e idosos podem piorar rápido.",
                    "Prevenção: sacuda roupas, toalhas e calçados; vede os ralos; não acumule entulho."),
            TipoAnimal.SERPENTE, List.of(
                    "Mantenha o membro picado parado e a vítima deitada, se possível.",
                    "Pálpebras caídas, visão turva ou sangramento: é emergência, ligue 192.",
                    "Prevenção: use botas e luvas no trabalho rural; não ponha a mão em buracos."),
            TipoAnimal.ARANHA, List.of(
                    "A aranha-marrom quase não dói, mas a lesão aparece horas depois: vá mesmo sem dor.",
                    "A armadeira causa dor forte na hora."),
            TipoAnimal.LAGARTA, List.of(
                    "Lave com água fria e faça compressas frias.",
                    "A taturana pode causar sangramento horas depois: procure atendimento."),
            TipoAnimal.ABELHA, List.of(
                    "Tire os ferrões raspando com um cartão, sem pinça.",
                    "Muitas picadas, inchaço no rosto ou na garganta ou alergia conhecida: ligue 192."),
            TipoAnimal.NAO_SEI, List.of(
                    "Siga as orientações gerais e vá à unidade de saúde indicada."));

    public PrimeirosSocorrosResponse primeirosSocorros(TipoAnimal tipoAnimal) {
        List<String> atencao = new ArrayList<>(ATENCAO.get(tipoAnimal));
        atencao.add(NAO_CAPTURAR);
        return new PrimeirosSocorrosResponse(tipoAnimal, FACA, NAO_FACA, List.copyOf(atencao));
    }
}
