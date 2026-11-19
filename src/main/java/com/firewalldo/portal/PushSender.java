package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import nl.martijndwars.webpush.*;
import okhttp3.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

interface PushSender { int send(JsonNode subscription,String payload); }

@Component
class EncryptedPushSender implements PushSender {
    static { if(Security.getProvider("BC")==null)Security.addProvider(new BouncyCastleProvider()); }
    private final Store db;
    private final Environment env;
    private final OkHttpClient client=new OkHttpClient.Builder().dns(host->{var ips=Arrays.asList(InetAddress.getAllByName(host));if(ips.stream().anyMatch(ip->!PublicRadarNetwork.isPublic(ip)))throw new UnknownHostException("Endereço bloqueado");return ips;})
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(Duration.ofSeconds(5)).callTimeout(Duration.ofSeconds(12)).build();
    EncryptedPushSender(Store db,Environment env){this.db=db;this.env=env;}
    static String endpoint(String url) {
        try {
            URI uri=URI.create(PublicRadarNetwork.publicUrl(url));String host=uri.getHost().toLowerCase(Locale.ROOT);
            if(!uri.getScheme().equals("https") || uri.getPort()!=-1 || !(host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com") || host.endsWith(".push.apple.com") || host.endsWith(".notify.windows.com")))throw new IllegalArgumentException();
            return url;
        }catch(Exception e){throw ApiError.invalid("Endpoint de push não reconhecido. Use um navegador compatível com Web Push.");}
    }
    static class Encoder extends AbstractPushService<Encoder> {
        Encoder(String publicKey,String privateKey,String subject)throws Exception{super(publicKey,privateKey,subject);}
        nl.martijndwars.webpush.HttpRequest prepare(Notification notification)throws Exception{return prepareRequest(notification,Encoding.AES128GCM);}
    }
    nl.martijndwars.webpush.HttpRequest prepare(JsonNode subscription,String payload)throws Exception {
        String address=endpoint(subscription.path("endpoint").asText());
        var config=db.require("Push não configurado.","SELECT to_jsonb(c) FROM push_config c WHERE id='webpush'");
        String subject=env.getProperty("WEB_PUSH_SUBJECT","https://gestao-production-fir.up.railway.app");
        if(!subject.startsWith("mailto:") && !subject.startsWith("https://"))throw ApiError.invalid("WEB_PUSH_SUBJECT deve ser um contato mailto: ou URL https://.");
        return new Encoder(config.path("public_key").asText(),config.path("private_key").asText(),subject).prepare(Notification.builder().endpoint(address).userPublicKey(subscription.path("p256dh").asText()).userAuth(subscription.path("auth").asText()).payload(payload).ttl(86400).urgency(Urgency.HIGH).build());
    }
    @Override public int send(JsonNode subscription,String payload) {
        try {
            var encrypted=prepare(subscription,payload);
            var request=new Request.Builder().url(encrypted.getUrl()).post(RequestBody.create(encrypted.getBody(),MediaType.get("application/octet-stream")));
            encrypted.getHeaders().forEach(request::header);
            try(var response=client.newCall(request.build()).execute()){return response.code();}
        }catch(Exception e){return 0;} // Delivery worker stores a safe, actionable status, never provider bodies or secrets.
    }
}
