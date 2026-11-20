package com.firewalldo.portal;

import com.fasterxml.jackson.databind.*;
import java.security.*;
import java.util.*;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class PushDeliveryService implements ApplicationRunner {
    private final Store db;private final ObjectMapper json;private final PushSender sender;private final TransactionTemplate tx;
    static String trim(String text,int max) {return text.substring(0,Math.min(text.length(),max));}
    PushDeliveryService(Store db,ObjectMapper json,PushSender sender,PlatformTransactionManager manager){this.db=db;this.json=json;this.sender=sender;this.tx=new TransactionTemplate(manager);}
    @Override public void run(ApplicationArguments args) {
        tx.executeWithoutResult(state->{db.lock(470007L);if(db.first("SELECT to_jsonb(id) FROM push_config WHERE id='webpush'")!=null)return;
            try {
                if(Security.getProvider("BC")==null)Security.addProvider(new BouncyCastleProvider());
                var generator=KeyPairGenerator.getInstance("ECDH","BC");generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));var pair=generator.generateKeyPair();
                String pub=Base64.getUrlEncoder().withoutPadding().encodeToString(((ECPublicKey)pair.getPublic()).getQ().getEncoded(false));
                byte[] raw=((ECPrivateKey)pair.getPrivate()).getD().toByteArray(),priv=new byte[32];System.arraycopy(raw,Math.max(0,raw.length-32),priv,Math.max(0,32-raw.length),Math.min(32,raw.length));
                db.sql.update("INSERT INTO push_config VALUES('webpush',?,?)",pub,Base64.getUrlEncoder().withoutPadding().encodeToString(priv));
            }catch(Exception e){throw new IllegalStateException("Não foi possível inicializar as chaves Web Push.");}
        });
    }
    @Scheduled(initialDelayString="${PUSH_INITIAL_DELAY_MS:15000}",fixedDelayString="${PUSH_POLL_MS:15000}")
    public void deliver() {
        var deliveries=tx.execute(state->{
            db.sql.update("UPDATE push_deliveries SET status='expired',message='Prazo de envio encerrado.' WHERE status IN ('queued','sending') AND created_at<now()-interval '24 hours'");
            db.sql.update("UPDATE push_deliveries SET status='queued' WHERE status='sending' AND claimed_at<now()-interval '5 minutes'");
            var rows=db.list("""
              SELECT to_jsonb(d) FROM push_deliveries d JOIN push_subscriptions s ON s.id=d.subscription_id JOIN members m ON m.id=s.member_id AND m.active
              WHERE d.status='queued' AND d.next_attempt_at<=now() ORDER BY d.created_at,d.id LIMIT 5 FOR UPDATE OF d SKIP LOCKED
              """);
            for(var row:rows)db.sql.update("UPDATE push_deliveries SET status='sending',attempts=attempts+1,claimed_at=now() WHERE id=?",Input.id(row.path("id").asText()));return rows;
        });
        if(deliveries==null)return;
        for(var row:deliveries) {
            UUID id=Input.id(row.path("id").asText());
            var message=db.first("""
              SELECT jsonb_build_object('subscription',jsonb_build_object('endpoint',s.endpoint,'p256dh',s.p256dh,'auth',s.auth),
                'title',n.title,'body',n.description,'kind',n.kind,'memberId',s.member_id,'notificationId',n.id)
              FROM push_deliveries d JOIN push_subscriptions s ON s.id=d.subscription_id JOIN members m ON m.id=s.member_id AND m.active
              JOIN notifications n ON n.id=d.notification_id AND n.member_id=s.member_id WHERE d.id=?
              """,id);
            if(message==null){tx.executeWithoutResult(state->db.sql.update("UPDATE push_deliveries SET status='expired',message='Inscrição removida ou conta inativa.' WHERE id=?",id));continue;}
            String kind=message.path("kind").asText();String url=kind.equals("noticia")?"/radar":kind.equals("projeto")?"/projetos":kind.equals("tarefa")?(message.path("title").asText().equals("Nova tarefa disponível")?"/tarefas":"/tarefas?meu=1"):"/";
            String payload=json.valueToTree(Map.of("title",trim(message.path("title").asText(),100),"body",trim(message.path("body").asText(),180),"url",url,"tag",message.path("notificationId").asText(),"accountId",message.path("memberId").asText())).toString();
            int status=sender.send(message.path("subscription"),payload);
            tx.executeWithoutResult(state->{
                if(status>=200 && status<300)db.sql.update("UPDATE push_deliveries SET status='accepted',last_http_status=?,message='Provedor aceitou o envio. A exibição depende da permissão e conexão do aparelho.' WHERE id=?",status,id);
                else if(status==404 || status==410){db.sql.update("UPDATE push_deliveries SET status='expired',last_http_status=?,message='Inscrição expirou. Reative as notificações no aparelho.' WHERE id=?",status,id);db.sql.update("UPDATE push_deliveries SET status='expired',message='Inscrição expirou. Reative no aparelho.' WHERE subscription_id=? AND status IN ('queued','sending')",Input.id(row.path("subscription_id").asText()));db.sql.update("DELETE FROM push_subscriptions WHERE id=?",Input.id(row.path("subscription_id").asText()));}
                else {
                    boolean retry=(status==0 || status==429 || status>=500) && row.path("attempts").asInt()<4;
                    int delay=new int[]{1,5,30,360,360}[Math.min(4,row.path("attempts").asInt())];
                    db.sql.update("UPDATE push_deliveries SET status=?,last_http_status=?,message=?,next_attempt_at=now()+(?*interval '1 minute') WHERE id=?",retry?"queued":"failed",status,status==0?"Falha de conexão ou configuração de envio.":"Provedor recusou o envio (HTTP "+status+").",delay,id);
                }
            });
        }
    }
}
