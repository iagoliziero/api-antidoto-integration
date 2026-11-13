package br.com.fiap.antidoto.service;

import br.com.fiap.antidoto.dto.usuario.AlterarSenhaRequest;
import br.com.fiap.antidoto.dto.usuario.AtualizarUsuarioRequest;
import br.com.fiap.antidoto.dto.usuario.UsuarioResponse;
import br.com.fiap.antidoto.exception.RecursoNaoEncontradoException;
import br.com.fiap.antidoto.exception.RegraDeNegocioException;
import br.com.fiap.antidoto.mapper.UsuarioMapper;
import br.com.fiap.antidoto.model.Usuario;
import br.com.fiap.antidoto.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dados de quem está logado (GET e PUT /api/usuarios/me) e a troca de senha. */
@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final UsuarioMapper usuarioMapper;
    private final PasswordEncoder codificadorDeSenha;

    public UsuarioService(
            UsuarioRepository usuarioRepository,
            UsuarioMapper usuarioMapper,
            PasswordEncoder codificadorDeSenha
    ) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioMapper = usuarioMapper;
        this.codificadorDeSenha = codificadorDeSenha;
    }

    @Transactional(readOnly = true)
    public UsuarioResponse buscar(Long idUsuario) {
        return usuarioMapper.paraResponse(carregar(idUsuario));
    }

    /** Só nome e telefone (MVP-01 §6.1); telefone null apaga. */
    @Transactional
    public UsuarioResponse atualizar(Long idUsuario, AtualizarUsuarioRequest requisicao) {
        Usuario usuario = carregar(idUsuario);
        usuario.setNome(requisicao.nome());
        usuario.setTelefone(requisicao.telefone());
        return usuarioMapper.paraResponse(usuario);
    }

    /**
     * Troca a senha conferindo a atual. Senha atual errada é 400 no campo, não 401:
     * o 401 faria o app encerrar a sessão de quem só digitou errado.
     */
    @Transactional
    public void alterarSenha(Long idUsuario, AlterarSenhaRequest requisicao) {
        Usuario usuario = carregar(idUsuario);
        if (!codificadorDeSenha.matches(requisicao.senhaAtual(), usuario.getSenhaHash())) {
            throw new RegraDeNegocioException("senhaAtual", "Senha atual incorreta.");
        }
        if (requisicao.novaSenha().equals(requisicao.senhaAtual())) {
            throw new RegraDeNegocioException("novaSenha", "A nova senha precisa ser diferente da atual.");
        }
        usuario.setSenhaHash(codificadorDeSenha.encode(requisicao.novaSenha()));
    }

    private Usuario carregar(Long idUsuario) {
        return usuarioRepository.findById(idUsuario)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário não encontrado."));
    }
}
