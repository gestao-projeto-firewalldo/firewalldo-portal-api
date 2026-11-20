package com.firewalldo.portal;

import java.net.*;

/**
 * Validação de endereços públicos (usada pelo envio de notificações push).
 * V1.9: a coleta automática do Radar pela OpenAI foi removida; restaram só estas regras.
 */
final class PublicRadarNetwork {
    private PublicRadarNetwork() {}

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b=address.getAddress(); int a=Byte.toUnsignedInt(b[0]), c=Byte.toUnsignedInt(b[1]);
        if (b.length==4) return a!=0 && a!=10 && a!=127 && a<224 && !(a==100 && c>=64 && c<=127)
            && !(a==169 && c==254) && !(a==172 && c>=16 && c<=31) && !(a==192 && (c==168 || c==0))
            && !(a==198 && (c==18 || c==19 || c==51)) && !(a==203 && c==0);
        // Restrict IPv6 to global unicast; deny documentation, transition and mapped ranges.
        return (a&0xe0)==0x20 && !(a==0x20 && c==1 && (b[2]==0 || (Byte.toUnsignedInt(b[2])==0x0d && Byte.toUnsignedInt(b[3])==0xb8)))
            && !(a==0x20 && c==2);
    }
    static String publicUrl(String value) {
        try {
            URI uri=URI.create(value);
            String host=uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
            if (host==null || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=80 && uri.getPort()!=443)
                || host.equalsIgnoreCase("localhost") || host.endsWith(".internal") || host.endsWith(".local") || value.length()>2048) throw new IllegalArgumentException();
            if (host.matches("[0-9.]+") || host.contains(":")) {
                if (!isPublic(InetAddress.getByName(host))) throw new IllegalArgumentException();
            } else if (!host.contains(".")) throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch(Exception e) { throw ApiError.invalid("Use um endereço público HTTP/HTTPS válido, sem credenciais ou portas especiais."); }
    }
}
