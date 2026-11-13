package br.com.fiap.antidoto.dto.usuario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PUT /api/usuarios/me/senha. A nova senha segue a mesma regra do cadastro. */
public record AlterarSenhaRequest(

        @NotBlank(message = "Informe a senha atual")
        String senhaAtual,

        @NotBlank(message = "Informe a nova senha")
        @Size(min = 8, max = 72, message = "A senha precisa ter de 8 a 72 caracteres")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "A senha precisa ter pelo menos uma letra e um número")
        String novaSenha

) {
}
