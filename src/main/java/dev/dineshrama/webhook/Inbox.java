package dev.dineshrama.webhook;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Inbox {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final LabProperties config;
    public Inbox(JdbcTemplate db,TransactionTemplate tx,LabProperties config) { this.db=db; this.tx=tx; this.config=config; }
    public record Receipt(String id, boolean duplicate, String status) {}
    public Receipt accept(CreditEvent event) {
        // Hash the validated business fields, so whitespace / JSON key ordering do not change identity.
        String hash=Signatures.hash(event.id()+"\n"+event.accountId()+"\n"+event.credits());
        return tx.execute(s -> {
            int inserted=db.update("INSERT INTO events(id,account_id,credits,payload_hash,max_attempts) VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                event.id(),event.accountId(),event.credits(),hash,config.maxAttempts());
            Map<String,Object> row=db.queryForMap("SELECT payload_hash,status FROM events WHERE id=? FOR UPDATE",event.id());
            if (!hash.equals(row.get("payload_hash"))) throw new ApiProblem(409,"EVENT_ID_CONFLICT","This event ID already belongs to a different payload.");
            if (inserted==0) {
                db.update("UPDATE events SET delivery_count=delivery_count+1,updated_at=clock_timestamp() WHERE id=?",event.id());
                audit(event.id(),"DUPLICATE_RECEIVED","Delivery acknowledged; existing work and ledger retained.");
            } else audit(event.id(),"ACCEPTED","Validated event committed to the durable inbox.");
            return new Receipt(event.id(),inserted==0,row.get("status").toString());
        });
    }
    public Map<String,Object> replay(String id) {
        return tx.execute(s -> {
            var rows=db.queryForList("SELECT status FROM events WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw new ApiProblem(404,"EVENT_NOT_FOUND","Event not found.");
            String status=rows.getFirst().get("status").toString();
            if(status.equals("SUCCEEDED")) return Map.of("id",id,"replayed",false,"message","Already completed; credits will not be applied again.");
            if(!status.equals("DEAD_LETTER")) throw new ApiProblem(409,"REPLAY_NOT_ALLOWED","Only dead-letter events can be replayed.");
            db.update("UPDATE events SET status='RECEIVED',cycle_attempt=0,next_attempt_at=clock_timestamp(),last_error=NULL,updated_at=clock_timestamp() WHERE id=?",id);
            audit(id,"MANUAL_REPLAY","Operator started a new retry cycle; the event ID and ledger are preserved.");
            return Map.of("id",id,"replayed",true,"message","Event queued for another processing cycle.");
        });
    }
    public void fault(String id,String mode,int remaining) {
        if(mode==null || !Set.of("NONE","TRANSIENT","POISON","CRASH_BEFORE_COMMIT","CRASH_AFTER_COMMIT").contains(mode) || remaining<0 || remaining>20)
            throw new ApiProblem(400,"INVALID_FAULT","Unsupported fault configuration.");
        tx.executeWithoutResult(s -> {
            var rows=db.queryForList("SELECT status FROM events WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw new ApiProblem(404,"EVENT_NOT_FOUND","Event not found.");
            if(rows.getFirst().get("status").equals("PROCESSING")) throw new ApiProblem(409,"EVENT_BUSY","Wait for the active worker lease to finish.");
            db.update("UPDATE events SET fault_mode=?,failures_remaining=?,updated_at=clock_timestamp() WHERE id=?",mode,remaining,id);
            audit(id,"FAULT_CONFIGURED",mode+"; remaining failures="+remaining);
        });
    }
    public List<Map<String,Object>> events(String status,String query,int limit,int offset) {
        if(status!=null && !Set.of("RECEIVED","PROCESSING","RETRY_WAIT","SUCCEEDED","DEAD_LETTER").contains(status)) throw new ApiProblem(400,"INVALID_STATUS","Unknown event status.");
        String sql="SELECT id,account_id,credits,status,delivery_count,attempt_count,cycle_attempt,max_attempts,created_at,updated_at,next_attempt_at,lease_until,last_error,fault_mode FROM events WHERE 1=1";
        List<Object> args=new ArrayList<>();
        if(status!=null) { sql+=" AND status=?";args.add(status); }
        if(query!=null && !query.isBlank()) { sql+=" AND (id ILIKE ? OR account_id ILIKE ?)"; args.add("%"+query+"%");args.add("%"+query+"%"); }
        args.add(Math.clamp(limit,1,100));args.add(Math.clamp(offset,0,100000));
        return db.queryForList(sql+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",args.toArray());
    }
    public Map<String,Object> detail(String id) {
        var rows=db.queryForList("SELECT * FROM events WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiProblem(404,"EVENT_NOT_FOUND","Event not found.");
        return Map.of("event",rows.getFirst(),"attempts",db.queryForList("SELECT * FROM attempts WHERE event_id=? ORDER BY number",id),
            "activity",db.queryForList("SELECT * FROM activity WHERE event_id=? ORDER BY id",id),
            "ledger",db.queryForList("SELECT * FROM credit_ledger WHERE event_id=?",id));
    }
    public Map<String,Object> stats() {
        var totals=db.queryForMap("SELECT count(*) AS total,COALESCE(sum(delivery_count-1),0) AS duplicates,count(*) FILTER(WHERE status='SUCCEEDED') AS succeeded,count(*) FILTER(WHERE status='DEAD_LETTER') AS dead_letter,count(*) FILTER(WHERE status IN ('RECEIVED','RETRY_WAIT','PROCESSING')) AS pending FROM events");
        var credits=db.queryForMap("SELECT count(*) AS effects,COALESCE(sum(credits),0) AS credits FROM credit_ledger");
        return Map.of("totals",totals,"ledger",credits,"accounts",db.queryForList("SELECT * FROM accounts ORDER BY id"),
            "attempts",db.queryForList("SELECT outcome,count(*) AS count FROM attempts GROUP BY outcome"));
    }
    public void audit(String id,String action,String detail) { db.update("INSERT INTO activity(event_id,action,detail) VALUES(?,?,?)",id,action,detail); }
}
