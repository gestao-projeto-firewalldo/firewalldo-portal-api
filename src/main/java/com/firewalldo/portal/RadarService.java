package com.firewalldo.portal;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Component
class RadarService {
    private final Store db;
    private final ObjectMapper json;
    private final RadarProvider provider;
    private final RadarImages images;
    private final TransactionTemplate tx;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"firewalldo-radar");t.setDaemon(true);return t;});
    RadarService(Store db,ObjectMapper json,RadarProvider provider,RadarImages images,PlatformTransactionManager manager) {
        this.db=db;this.json=json;this.provider=provider;this.images=images;this.tx=new TransactionTemplate(manager);
    }
    @PreDestroy void stop(){executor.shutdownNow();}
    JsonNode config() {return db.first("""
        SELECT jsonb_build_object('enabled',enabled,'intervalMinutes',interval_minutes,'lookbackHours',lookback_hours,
          'maxItems',max_items,'criteria',criteria,'autoPublish',auto_publish,'fetchImages',fetch_images,'nextRunAt',next_run_at)
        FROM radar_settings WHERE id='radar'
        """);}
    Map<String,Object> status() {
        return Map.of("config",config(),"keyConfigured",provider.configured(),"model",provider.model(),"runs",db.list("""
          SELECT jsonb_build_object('id',id,'startedAt',started_at,'finishedAt',finished_at,'status',status,
            'imported',imported,'skipped',skipped,'message',message) FROM radar_runs ORDER BY started_at DESC LIMIT 10
          """));
    }
    void save(JsonNode body) {
        var form=new Input(body);form.only("enabled","intervalMinutes","lookbackHours","maxItems","criteria","autoPublish","fetchImages");
        boolean enabled=form.bool("enabled");
        if(enabled)requireReady();
        db.sql.update("""
          UPDATE radar_settings SET enabled=?,interval_minutes=?,lookback_hours=?,max_items=?,criteria=?,auto_publish=?,fetch_images=?,
            next_run_at=CASE WHEN ? AND NOT enabled THEN now() ELSE now()+(? * interval '1 minute') END WHERE id='radar'
          """,enabled,form.number("intervalMinutes",15,1440),form.number("lookbackHours",1,168),form.number("maxItems",1,10),
          form.text("criteria",10,4000),form.bool("autoPublish"),form.bool("fetchImages"),enabled,form.number("intervalMinutes",15,1440));
    }
    void requireReady() {
        if(!provider.configured())throw ApiError.invalid("Configure OPENAI_API_KEY nas variáveis do serviço api. A chave não deve ser colocada no navegador.");
        if(sources().isEmpty())throw ApiError.invalid("Cadastre e salve pelo menos uma fonte ativa com endereço público.");
    }
    private List<JsonNode> sources() {
        var sources=db.list("SELECT to_jsonb(s)-'position' FROM radar_sources s WHERE active ORDER BY position,id");
        // Domain filters accept at most 100 entries, as does the source editor.
        for(var source:sources)PublicRadarNetwork.publicUrl(source.path("url").asText());
        return sources;
    }
    @Scheduled(initialDelayString="${RADAR_INITIAL_DELAY_MS:60000}",fixedDelayString="${RADAR_POLL_MS:60000}")
    public void tick() {
        try {
            boolean due=Boolean.TRUE.equals(db.sql.queryForObject("SELECT enabled AND next_run_at<=now() FROM radar_settings WHERE id='radar'",Boolean.class));
            if(due && provider.configured())submit(false);
        }catch(RuntimeException ignored){ /* Failure details are persisted by execute. Never log keys or provider bodies. */ }
    }
    UUID submit(boolean manual) {
        requireReady();
        UUID id=tx.execute(state->{
            db.lock(470006L);
            db.sql.update("UPDATE radar_runs SET status='failed',finished_at=now(),message='A execução foi interrompida. Execute novamente.' WHERE status='running' AND started_at<now()-interval '20 minutes'");
            if(Boolean.TRUE.equals(db.sql.queryForObject("SELECT EXISTS(SELECT 1 FROM radar_runs WHERE status='running')",Boolean.class))) {
                if(manual)throw ApiError.conflict("Já há uma coleta em andamento. Acompanhe o histórico abaixo.");
                return null;
            }
            if(!manual && !Boolean.TRUE.equals(db.sql.queryForObject("SELECT enabled AND next_run_at<=now() FROM radar_settings WHERE id='radar'",Boolean.class)))return null;
            if(manual && Boolean.TRUE.equals(db.sql.queryForObject("SELECT EXISTS(SELECT 1 FROM radar_runs WHERE started_at>now()-interval '1 minute')",Boolean.class)))throw ApiError.conflict("Aguarde um minuto antes de iniciar outra pesquisa.");
            UUID run=UUID.randomUUID();
            db.sql.update("INSERT INTO radar_runs(id,status,title) VALUES(?,'running',?)",run,manual?"Coleta manual do Radar":"Coleta agendada do Radar");
            db.sql.update("UPDATE radar_settings SET next_run_at=now()+(interval_minutes*interval '1 minute') WHERE id='radar'");
            return run;
        });
        if(id!=null) {
            Runnable enqueue=()->executor.execute(()->execute(id));
            if(TransactionSynchronizationManager.isActualTransactionActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){enqueue.run();}});
            else enqueue.run();
        }
        return id;
    }
    void execute(UUID run) {
        int imported=0,skipped=0,missingImages=0;
        try {
            JsonNode config=config();
            var sources=sources();
            var recent=db.list("SELECT jsonb_build_object('title',title,'url',url) FROM news ORDER BY published_at DESC,id LIMIT 100");
            var articles=provider.search(config,sources,recent);
            for(var article:articles) {
                var source=sources.stream().filter(s->RadarProvider.matches(s.path("url").asText(),article.url())).max(Comparator.comparingInt(s->RadarProvider.host(s.path("url").asText()).length())).orElseThrow();
                String hash=hash(article.title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""));
                if(Boolean.TRUE.equals(db.sql.queryForObject("SELECT EXISTS(SELECT 1 FROM news WHERE url=? OR dedup_key=?)",Boolean.class,article.url(),hash))) {skipped++;continue;}
                Optional<RadarImages.Image> image=config.path("fetchImages").asBoolean()?images.find(article.url(),article.imageQuery(),source.path("name").asText()):Optional.empty();
                if(config.path("fetchImages").asBoolean() && image.isEmpty())missingImages++;
                Integer written=tx.execute(state->{
                    // Recheck source state at commit: pausing a source during a long search prevents publication.
                    if(!Boolean.TRUE.equals(db.sql.queryForObject("SELECT EXISTS(SELECT 1 FROM radar_sources WHERE id=? AND active)",Boolean.class,source.path("id").asText())))return 0;
                    var picture=image.orElse(null);
                    return db.sql.update("""
                      INSERT INTO news(id,source_id,title,summary,body,category,url,published_at,read_minutes,featured,cover,
                        publication_status,imported_at,dedup_key,references_json,image_content,image_type,image_credit,image_source_url,image_illustrative)
                      VALUES(?,?,?,?,?,?,?,?,?,false,?,?,now(),?,?::jsonb,?,?,?,?,?) ON CONFLICT DO NOTHING
                      """,UUID.randomUUID(),source.path("id").asText(),article.title(),article.summary(),article.body().toArray(String[]::new),article.category(),article.url(),
                      article.publishedAt().atZoneSameInstant(ZoneId.of("America/Sao_Paulo")).toLocalDate(),Math.max(1,(article.summary()+String.join(" ",article.body())).split("\\s+").length/200+1),cover(article.category()),
                      config.path("autoPublish").asBoolean()?"published":"draft",hash,json.valueToTree(article.references()).toString(),
                      picture==null?null:picture.bytes(),picture==null?null:picture.type(),picture==null?"":picture.credit(),picture==null?"":picture.sourceUrl(),picture!=null&&picture.illustrative());
                });
                if(written!=null && written>0)imported++;else skipped++;
            }
            String message=imported+" notícia(s) "+(config.path("autoPublish").asBoolean()?"publicada(s)":"enviada(s) para aprovação")+"; "+skipped+" repetida(s) ou de fonte pausada ignorada(s).";
            if(articles.isEmpty())message="Nenhuma novidade atendeu aos critérios e à janela de tempo. Nenhuma notícia foi criada.";
            if(missingImages>0)message+=" "+missingImages+" matéria(s) sem imagem disponível; capa de categoria preservada.";
            finish(run,"success",imported,skipped,message);
        }catch(Exception e) {
            String message=e instanceof ApiError?e.getMessage():"A coleta falhou. Confira a configuração e tente novamente. Nenhum detalhe sensível foi registrado.";
            finish(run,imported>0?"partial":"failed",imported,skipped,message);
        }
    }
    private void finish(UUID id,String status,int imported,int skipped,String message) {
        tx.executeWithoutResult(state->db.sql.update("UPDATE radar_runs SET status=?,finished_at=now(),imported=?,skipped=?,message=? WHERE id=? AND status='running'",status,imported,skipped,RadarImages.trim(message,1000),id));
    }
    static String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String cover(String category) {return switch(category){case "ia"->"ai";case "redes"->"router";case "programacao"->"code";case "privacidade"->"eye";case "informatica"->"chip";default->"lock";};}
}
