package br.com.conde.bilheteria.aplicacao.suporte;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Código do ingresso: reserva.lugar.assinatura, em Base64 URL-safe, pronto para virar QR code. A assinatura é um
 * HMAC-SHA256 truncado, então a portaria rejeita códigos inventados sem consultar o banco.
 */
@Component
public class AssinadorDeIngressos {

    private final byte[] chave;

    public AssinadorDeIngressos(@Value("${bilheteria.ingressos.chave}") String chave) {
        if (chave == null || chave.length() < 16) throw new IllegalStateException("bilheteria.ingressos.chave precisa ter ao menos 16 caracteres");
        this.chave = chave.getBytes(StandardCharsets.UTF_8);
    }

    public String assinar(UUID reservaId, UUID lugarId) {
        var dados = reservaId + "." + lugarId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString((dados + "." + hmac(dados)).getBytes(StandardCharsets.UTF_8));
    }

    public boolean valido(String codigo) {
        try {
            var texto = new String(Base64.getUrlDecoder().decode(codigo), StandardCharsets.UTF_8);
            var partes = texto.split("\\.");
            if (partes.length != 3) return false;
            var esperado = hmac(partes[0] + "." + partes[1]);
            return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8), partes[2].getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String hmac(String dados) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(chave, "HmacSHA256"));
            var assinatura = mac.doFinal(dados.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(assinatura).substring(0, 22);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
