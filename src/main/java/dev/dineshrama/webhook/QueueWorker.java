package dev.dineshrama.webhook;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QueueWorker {
    private static final Logger log=LoggerFactory.getLogger(QueueWorker.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Inbox inbox;
    private final LabProperties config;
    private final MeterRegistry metrics;
    private final AtomicBoolean paused=new AtomicBoolean(false);
    private final String workerId="worker-"+UUID.randomUUID().toString().substring(0,8);
    public record Claim(String id,UUID token,int number,int cycleAttempt,int maxAttempts,String mode,int remaining) {}
    public QueueWorker(JdbcTemplate db,TransactionTemplate tx,Inbox inbox,LabProperties config,MeterRegistry metrics) {
        this.db=db;this.tx=tx;this.inbox=inbox;this.config=config;this.metrics=metrics;
    }
    public boolean paused() { return paused.get(); }
    public void pause(boolean value) { paused.set(value); }
    public String workerId() { return workerId; }

    @Scheduled(fixedDelayString="${lab.poll-ms:750}")
    public void scheduledPoll() {
        if(!config.workerEnabled() || paused()) return;
        try { tick(8); } catch(Exception e) { log.error("Worker poll failed; durable work remains in PostgreSQL",e); }
    }
    public int tick(int batchSize) {
        recoverExpired();
        int count=0;
        for(int i=0;i<Math.clamp(batchSize,1,100);i++) {
            Claim claim=claim();if(claim==null) break;
            process(claim);count++;
        }
        return count;
    }
    public Claim claim() {
        return tx.execute(s -> {
            var rows=db.queryForList("SELECT * FROM events WHERE status IN ('RECEIVED','RETRY_WAIT') AND next_attempt_at<=clock_timestamp() ORDER BY next_attempt_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(rows.isEmpty()) return null;
            var row=rows.getFirst();String id=row.get("id").toString();UUID token=UUID.randomUUID();
            int number=((Number)row.get("attempt_count")).intValue()+1;
            int cycle=((Number)row.get("cycle_attempt")).intValue()+1;
            String mode=row.get("fault_mode").toString();
            db.update("UPDATE events SET status='PROCESSING',attempt_count=?,cycle_attempt=?,lease_token=?,lease_until=clock_timestamp()+(? * interval '1 second'),fault_mode=?,updated_at=clock_timestamp() WHERE id=?",
                number,cycle,token,config.leaseSeconds(),mode.startsWith("CRASH_")?"NONE":mode,id);
            db.update("INSERT INTO attempts(event_id,number,lease_token,worker_id) VALUES(?,?,?,?)",id,number,token,workerId);
            inbox.audit(id,"CLAIMED","Attempt "+number+" acquired by "+workerId+" with a fenced lease.");
            return new Claim(id,token,number,cycle,((Number)row.get("max_attempts")).intValue(),mode,((Number)row.get("failures_remaining")).intValue());
        });
    }
    public void process(Claim claim) {
        var sample=io.micrometer.core.instrument.Timer.start(metrics);
        try {
            if(claim.mode().equals("POISON")) { fail(claim,true,"Synthetic validation failure: account mapping is invalid."); return; }
            if(claim.mode().equals("TRANSIENT") && claim.remaining()>0) { fail(claim,false,"Synthetic dependency timeout; retry is permitted."); return; }
            boolean applied=Boolean.TRUE.equals(tx.execute(s -> {
                var event=owned(claim);if(event==null) return false;
                String account=event.get("account_id").toString();int credits=((Number)event.get("credits")).intValue();
                db.update("INSERT INTO accounts(id) VALUES(?) ON CONFLICT DO NOTHING",account);
                int inserted=db.update("INSERT INTO credit_ledger(event_id,account_id,credits) VALUES(?,?,?) ON CONFLICT(event_id) DO NOTHING",claim.id(),account,credits);
                if(inserted==1) db.update("UPDATE accounts SET balance=balance+? WHERE id=?",credits,account);
                if(claim.mode().equals("CRASH_BEFORE_COMMIT")) throw new SimulatedCrash();
                inbox.audit(claim.id(),inserted==1?"EFFECT_COMMITTED":"EFFECT_ALREADY_EXISTS",inserted==1?"Credit ledger and account balance committed atomically.":"Existing ledger entry protected the account from another credit.");
                return true;
            }));
            if(!applied) { metrics.counter("lab.stale_claims").increment();return; }
            if(claim.mode().equals("CRASH_AFTER_COMMIT")) {
                metrics.counter("lab.simulated_crashes","point","after_commit").increment();
                log.info("Simulated loss of completion acknowledgement event={}",claim.id());return;
            }
            complete(claim);
        } catch(SimulatedCrash e) {
            metrics.counter("lab.simulated_crashes","point","before_commit").increment();
            log.info("Simulated transaction rollback event={}; lease recovery will retry",claim.id());
        } catch(Exception e) {
            log.warn("Processing failed event={}; scheduling recovery",claim.id(),e);
            // If the DB is unavailable, this can also fail. The durable lease is the final recovery path.
            fail(claim,false,"Processing failed; inspect server logs for the underlying database error.");
        } finally { sample.stop(metrics.timer("lab.processing")); }
    }
    private Map<String,Object> owned(Claim c) {
        var rows=db.queryForList("SELECT * FROM events WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp() FOR UPDATE",c.id(),c.token());
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void complete(Claim claim) {
        tx.executeWithoutResult(s -> {
            if(owned(claim)==null) return;
            db.update("UPDATE events SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=clock_timestamp() WHERE id=?",claim.id());
            db.update("UPDATE attempts SET outcome='SUCCEEDED',message='Ledger verified and completion acknowledged.',finished_at=clock_timestamp() WHERE lease_token=?",claim.token());
            inbox.audit(claim.id(),"SUCCEEDED","Processing completed; one credit effect is recorded.");
        });
        metrics.counter("lab.processing_results","outcome","succeeded").increment();
    }
    private void fail(Claim claim,boolean terminal,String message) {
        tx.executeWithoutResult(s -> {
            if(owned(claim)==null) return;
            boolean dead=terminal || claim.cycleAttempt()>=claim.maxAttempts();
            long base=Math.min(60000L,config.retryBaseMs()*(1L<<Math.min(20,claim.cycleAttempt()-1)));
            long delay=base+ThreadLocalRandom.current().nextLong(Math.max(1,base/5));
            db.update("UPDATE events SET status=?,failures_remaining=GREATEST(0,failures_remaining-1),next_attempt_at=clock_timestamp()+(? * interval '1 millisecond'),lease_token=NULL,lease_until=NULL,last_error=?,updated_at=clock_timestamp() WHERE id=?",dead?"DEAD_LETTER":"RETRY_WAIT",delay,message,claim.id());
            db.update("UPDATE attempts SET outcome=?,message=?,finished_at=clock_timestamp() WHERE lease_token=?",terminal?"TERMINAL":dead?"EXHAUSTED":"RETRYABLE",message,claim.token());
            inbox.audit(claim.id(),dead?"DEAD_LETTERED":"RETRY_SCHEDULED",message+(dead?"":" Next attempt in "+delay+" ms."));
            metrics.counter("lab.processing_results","outcome",dead?"dead_letter":"retry").increment();
        });
    }
    public int recoverExpired() {
        return Objects.requireNonNull(tx.execute(s -> {
            var rows=db.queryForList("SELECT id,lease_token,cycle_attempt,max_attempts FROM events WHERE status='PROCESSING' AND lease_until<=clock_timestamp() ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED");
            for(var row:rows) {
                String id=row.get("id").toString();
                boolean committed=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM credit_ledger WHERE event_id=?)",Boolean.class,id));
                boolean exhausted=((Number)row.get("cycle_attempt")).intValue()>=((Number)row.get("max_attempts")).intValue();
                String next=committed?"SUCCEEDED":exhausted?"DEAD_LETTER":"RETRY_WAIT";
                String message=committed?"Lease expired after the business commit; ledger reconciled without applying credits again.":exhausted?"Worker lease expired; retry budget exhausted.":"Worker lease expired before a business commit; work recovered for retry.";
                db.update("UPDATE attempts SET outcome=?,message=?,finished_at=clock_timestamp() WHERE lease_token=? AND outcome='RUNNING'",committed?"RECOVERED":"LEASE_EXPIRED",message,row.get("lease_token"));
                db.update("UPDATE events SET status=?,lease_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp(),last_error=?,updated_at=clock_timestamp() WHERE id=?",next,committed?null:message,id);
                inbox.audit(id,committed?"RECONCILED":"LEASE_RECOVERED",message);
                metrics.counter("lab.lease_recoveries","effect_committed",String.valueOf(committed)).increment();
            }
            return rows.size();
        }));
    }
    private static class SimulatedCrash extends RuntimeException {}
}
