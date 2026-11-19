package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.firewalldo.portal.SessionService.Actor;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/push")
class PushController {
    private final Store db;
    PushController(Store db){this.db=db;}
    @GetMapping Map<String,Object> status(@RequestAttribute Actor actor) {
        var key=db.require("Web Push não está pronto.","SELECT to_jsonb(public_key) FROM push_config WHERE id='webpush'");
        return Map.of("publicKey",key.asText(),"devices",db.list("SELECT jsonb_build_object('id',id,'device',device,'createdAt',created_at) FROM push_subscriptions WHERE member_id=? ORDER BY created_at DESC",actor.id()),
            "deliveries",db.list("SELECT jsonb_build_object('id',id,'title',title,'status',status,'attempts',attempts,'httpStatus',last_http_status,'message',message,'createdAt',created_at) FROM push_deliveries WHERE member_id=? ORDER BY created_at DESC LIMIT 10",actor.id()));
    }
    @PostMapping("/subscriptions") Map<String,Object> subscribe(@RequestAttribute Actor actor,@RequestBody JsonNode body) {
        var form=new Input(body);form.only("id","endpoint","keys","device");UUID id=Input.id(form.text("id",36,36));
        String endpoint=EncryptedPushSender.endpoint(form.url("endpoint",false)),label=form.text("device",1,100);
        var keys=new Input(body.path("keys"));String pub=keys.text("p256dh",86,90),auth=keys.text("auth",22,24);
        try {if(Base64.getUrlDecoder().decode(pub).length!=65 || Base64.getUrlDecoder().decode(auth).length!=16)throw new IllegalArgumentException();nl.martijndwars.webpush.Utils.loadPublicKey(pub);}catch(Exception e){throw ApiError.invalid("Chaves de inscrição inválidas. Reative as notificações no navegador.");}
        db.lock(470008L);
        var existing=db.first("SELECT jsonb_build_object('member',member_id,'id',id) FROM push_subscriptions WHERE id=? OR endpoint=?",id,endpoint);
        if(existing!=null && !existing.path("member").asText().equals(actor.id().toString()))throw ApiError.conflict("Esta inscrição pertence a outra conta. Desative a inscrição local e ative novamente nesta conta.");
        if(existing==null && db.sql.queryForObject("SELECT count(*) FROM push_subscriptions WHERE member_id=?",Integer.class,actor.id())>=10)throw ApiError.invalid("Limite de 10 aparelhos por conta. Remova um aparelho antigo.");
        if(existing!=null && !existing.path("id").asText().equals(id.toString()))throw ApiError.conflict("O navegador já está inscrito com outro identificador. Desative e ative novamente.");
        db.sql.update("INSERT INTO push_subscriptions(id,member_id,endpoint,p256dh,auth,device) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET endpoint=excluded.endpoint,p256dh=excluded.p256dh,auth=excluded.auth,device=excluded.device",id,actor.id(),endpoint,pub,auth,label);
        return Map.of("id",id,"subscribed",true);
    }
    @DeleteMapping("/subscriptions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@RequestAttribute Actor actor,@PathVariable String id) {
        UUID device=Input.id(id);var current=db.first("SELECT to_jsonb(device) FROM push_subscriptions WHERE id=? AND member_id=?",device,actor.id());
        if(current==null)return;
        db.sql.update("UPDATE push_deliveries SET status='expired',message='Aparelho desativado pelo usuário.' WHERE subscription_id=? AND status IN ('queued','sending')",device);
        db.sql.update("DELETE FROM push_subscriptions WHERE id=? AND member_id=?",device,actor.id());
    }
    @PostMapping("/test") @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> test(@RequestAttribute Actor actor) {
        if(db.sql.queryForObject("SELECT count(*) FROM push_subscriptions WHERE member_id=?",Integer.class,actor.id())==0)throw ApiError.invalid("Ative as notificações neste aparelho antes de testar.");
        if(db.sql.queryForObject("SELECT count(*) FROM notifications WHERE member_id=? AND title='Teste de notificação push' AND created_at>now()-interval '1 minute'",Integer.class,actor.id())>0)throw ApiError.conflict("Aguarde um minuto antes de enviar outro teste.");
        UUID id=UUID.randomUUID();db.sql.update("INSERT INTO notifications(id,member_id,kind,title,description) VALUES(?,?,'sistema','Teste de notificação push','As notificações do Firewalldo estão chegando neste aparelho.')",id,actor.id());
        return Map.of("notificationId",id,"queued",true);
    }
}
