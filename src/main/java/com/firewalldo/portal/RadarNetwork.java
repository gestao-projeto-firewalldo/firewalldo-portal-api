package com.firewalldo.portal;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Narrow transport boundary: test fixtures never contact paid providers. */
interface RadarNetwork {
    record Resource(byte[] bytes, String type, String url) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }
    Resource get(String url, int limit);
    String openai(String key, String body);
}

@Component
class PublicRadarNetwork implements RadarNetwork {
    private final OkHttpClient client = new OkHttpClient.Builder()
        .dns(host -> {
            var addresses = Arrays.asList(InetAddress.getAllByName(host));
            if (addresses.isEmpty() || addresses.stream().anyMatch(a -> !isPublic(a))) throw new UnknownHostException("Endereço não público.");
            return addresses;
        })
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(120)).callTimeout(Duration.ofSeconds(150)).build();

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
    @Override public Resource get(String input,int limit) {
        String url=publicUrl(input);
        var fast=client.newBuilder().callTimeout(Duration.ofSeconds(20)).readTimeout(Duration.ofSeconds(15)).build();
        for(int redirect=0;redirect<4;redirect++) {
            try (Response response=fast.newCall(new Request.Builder().url(url).header("User-Agent","Firewalldo-Radar/1.6").header("Accept","text/html,application/json,image/*;q=0.8").get().build()).execute()) {
                if (response.isRedirect()) { String target=response.header("Location"); if(target==null)break;url=publicUrl(URI.create(url).resolve(target).toString());continue; }
                if (!response.isSuccessful() || response.body()==null) throw ApiError.invalid("A fonte não respondeu com conteúdo disponível.");
                if (response.body().contentLength()>limit) throw ApiError.invalid("O conteúdo da fonte excede o limite permitido.");
                byte[] bytes=response.body().byteStream().readNBytes(limit+1);
                if(bytes.length>limit)throw ApiError.invalid("O conteúdo da fonte excede o limite permitido.");
                return new Resource(bytes,response.header("Content-Type","application/octet-stream"),url);
            } catch (ApiError e) { throw e; }
            catch(Exception e) { throw ApiError.invalid("Não foi possível acessar a fonte pública (conexão, tempo limite ou endereço bloqueado)."); }
        }
        throw ApiError.invalid("A fonte redirecionou muitas vezes.");
    }
    @Override public String openai(String key,String body) {
        var request=new Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization","Bearer "+key).post(RequestBody.create(body,MediaType.get("application/json"))).build();
        try(Response response=client.newCall(request).execute()) {
            if(!response.isSuccessful()) {
                String details="";
                if(response.body()!=null)details=new String(response.body().byteStream().readNBytes(64_000),StandardCharsets.UTF_8);
                throw OpenAiErrors.failure(response.code(),details,response.header("Retry-After"));
            }
            if(response.body()==null || response.body().contentLength()>2_000_000) throw ApiError.invalid("Resposta da OpenAI inválida.");
            byte[] bytes=response.body().byteStream().readNBytes(2_000_001);
            if(bytes.length>2_000_000)throw ApiError.invalid("Resposta da OpenAI acima do limite.");
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(ApiError e) {throw e;} catch(Exception e) {throw ApiError.invalid("A pesquisa na OpenAI excedeu o tempo de espera ou falhou na conexão.");}
    }
}
