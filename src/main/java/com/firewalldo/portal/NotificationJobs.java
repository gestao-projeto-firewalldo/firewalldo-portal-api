package com.firewalldo.portal;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** In-app reminders. Writes without an authenticated request are audited as Sistema. */
@Component
class NotificationJobs {
    private final Store db;
    NotificationJobs(Store db) {this.db=db;}
    static String dueText(String title,String date,java.time.LocalDate today) {
        long days=java.time.temporal.ChronoUnit.DAYS.between(today,java.time.LocalDate.parse(date));
        return "A tarefa \""+title+"\" vence "+(days<=0?"hoje":days==1?"amanhã":"em "+days+" dias")+".";
    }
    @Scheduled(initialDelayString="${NOTIFICATION_INITIAL_DELAY_MS:30000}",fixedDelayString="${NOTIFICATION_INTERVAL_MS:3600000}")
    @Transactional
    public void deliver() {
        var today=Input.today();
        var tasks=db.list("""
          SELECT jsonb_build_object('memberId',m.id,'taskId',t.id,'title',t.title,'dueDate',t.due_date)
          FROM tasks t JOIN members m ON m.id=ANY(t.assignee_ids) AND m.active
          LEFT JOIN member_preferences p ON p.member_id=m.id
          WHERE t.status<>'concluido' AND t.due_date BETWEEN ? AND ?
          AND COALESCE((p.preferences#>>'{notifications,taskDue}')::boolean,true)
          """,today,today.plusDays(2));
        for (var task:tasks) db.sql.update("""
          INSERT INTO notifications(id,member_id,kind,title,description,dedup_key)
          VALUES(?,?,'tarefa','Prazo próximo',?,?) ON CONFLICT(dedup_key) DO NOTHING
          """,UUID.randomUUID(),Input.id(task.path("memberId").asText()),dueText(task.path("title").asText(),task.path("dueDate").asText(),today),
          "due:"+task.path("taskId").asText()+":"+task.path("memberId").asText()+":"+task.path("dueDate").asText());
        if (LocalTime.now(ZoneId.of("America/Sao_Paulo")).isBefore(LocalTime.of(8,0))) return;
        Integer count=db.sql.queryForObject("SELECT count(*) FROM news n WHERE n.slot IS NOT NULL AND n.published_at=? AND n.publication_status='published'",Integer.class,today);
        if (count==null || count==0) return;
        var members=db.sql.queryForList("SELECT m.id FROM members m JOIN member_preferences p ON p.member_id=m.id WHERE m.active AND (p.preferences#>>'{notifications,newsDigest}')::boolean",UUID.class);
        for(UUID member:members) db.sql.update("""
          INSERT INTO notifications(id,member_id,kind,title,description,dedup_key)
          VALUES(?,?,'noticia','Resumo do Radar',?,?) ON CONFLICT(dedup_key) DO NOTHING
          """,UUID.randomUUID(),member,count+" notícia(s) publicada(s) hoje. Abra o Radar para conferir.","digest:"+member+":"+today);
    }
}
