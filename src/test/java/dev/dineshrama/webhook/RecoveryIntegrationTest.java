package dev.dineshrama.webhook;

import static org.assertj.core.api.Assertions.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"lab.worker-enabled=false","lab.retry-base-ms=10"})
class RecoveryIntegrationTest {
    static final EmbeddedPostgres pg=startPostgres();
    static EmbeddedPostgres startPostgres() { try { return EmbeddedPostgres.builder().setPort(0).setServerConfig("unix_socket_directories", "").start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->pg.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
    }
    @Autowired Inbox inbox;
    @Autowired QueueWorker worker;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired LabProperties config;
    @Autowired MeterRegistry metrics;
    @Autowired Signatures signatures;
    @Autowired TestRestTemplate http;
    @BeforeEach void reset() { db.execute("TRUNCATE activity,attempts,credit_ledger,accounts,events RESTART IDENTITY CASCADE");worker.pause(false); }
    @AfterAll static void stop() throws Exception { pg.close(); }
    CreditEvent event(String id) { return new CreditEvent(id,"acct_test",10); }
    String status(String id) { return db.queryForObject("SELECT status FROM events WHERE id=?",String.class,id); }
    long credits() { return db.queryForObject("SELECT COALESCE(sum(balance),0) FROM accounts",Long.class); }
    long ledger() { return db.queryForObject("SELECT count(*) FROM credit_ledger",Long.class); }
    void due() { db.update("UPDATE events SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE status='RETRY_WAIT'"); }
    void expire() { db.update("UPDATE events SET lease_until=clock_timestamp()-interval '1 second' WHERE status='PROCESSING'"); }
    HttpHeaders headers(String body) {
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);
        long timestamp=Instant.now().getEpochSecond();h.set("X-Webhook-Timestamp",String.valueOf(timestamp));h.set("X-Webhook-Signature",signatures.sign(body.getBytes(StandardCharsets.UTF_8),timestamp));return h;
    }
    @Test void signedAcceptanceIsDurableAndAsync() {
        String body="{\"id\":\"evt_http\",\"accountId\":\"acct_test\",\"credits\":10}";
        var response=http.postForEntity("/webhooks/credits",new HttpEntity<>(body,headers(body)),Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(status("evt_http")).isEqualTo("RECEIVED");assertThat(ledger()).isZero();
        worker.tick(1);assertThat(credits()).isEqualTo(10);assertThat(status("evt_http")).isEqualTo("SUCCEEDED");
    }
    @Test void concurrentDuplicateDeliveriesProduceOneEffect() throws Exception {
        try(var pool=Executors.newFixedThreadPool(12)) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<60;i++) futures.add(pool.submit(()->{gate.await();return inbox.accept(event("evt_same"));}));
            gate.countDown();for(var f:futures) f.get(20,TimeUnit.SECONDS);
        }
        worker.tick(100);
        assertThat(credits()).isEqualTo(10);assertThat(ledger()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT delivery_count FROM events",Integer.class)).isEqualTo(60);
    }
    @Test void concurrentWorkersDoNotLoseAccountUpdates() throws Exception {
        for(int i=0;i<40;i++) inbox.accept(event("evt_"+i));
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<?>>();for(int i=0;i<8;i++) futures.add(pool.submit(()->worker.tick(100)));
            for(var f:futures) f.get(20,TimeUnit.SECONDS);
        }
        assertThat(ledger()).isEqualTo(40);assertThat(credits()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT count(*) FROM events WHERE status='SUCCEEDED'",Integer.class)).isEqualTo(40);
    }
    @Test void sameIdWithChangedBusinessPayloadIsConflict() {
        inbox.accept(event("evt_conflict"));
        assertThatThrownBy(()->inbox.accept(new CreditEvent("evt_conflict","acct_other",99))).isInstanceOf(ApiProblem.class).hasMessageContaining("different payload");
        assertThat(db.queryForObject("SELECT delivery_count FROM events",Integer.class)).isOne();
    }
    @Test void duplicateJsonFormattingIsAcceptedButChangedPayloadReturns409() {
        String first="{\"id\":\"evt_format\",\"accountId\":\"acct_test\",\"credits\":10}";
        String second="{ \"credits\": 10, \"accountId\": \"acct_test\", \"id\": \"evt_format\" }";
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(first,headers(first)),String.class).getStatusCode().value()).isEqualTo(202);
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(second,headers(second)),String.class).getStatusCode().value()).isEqualTo(200);
        String changed=first.replace("10","11");
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(changed,headers(changed)),String.class).getStatusCode().value()).isEqualTo(409);
    }
    @Test void invalidSignatureDoesNotPersistWork() {
        String body="{\"id\":\"evt_bad_sig\",\"accountId\":\"acct_test\",\"credits\":10}";
        var h=headers(body);h.set("X-Webhook-Signature","v1=invalid");
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,h),String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(inbox.events(null,null,100,0)).isEmpty();
    }
    @Test void expiredTimestampAndTamperedBodyAreRejected() {
        String body="{\"id\":\"evt_expired\",\"accountId\":\"acct_test\",\"credits\":10}";
        long old=Instant.now().getEpochSecond()-301;var h=headers(body);
        h.set("X-Webhook-Timestamp",String.valueOf(old));h.set("X-Webhook-Signature",signatures.sign(body.getBytes(StandardCharsets.UTF_8),old));
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,h),String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body.replace("10","11"),headers(body)),String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(ledger()).isZero();
    }
    @Test void invalidAndFractionalEventsAreRejected() {
        for(String body:List.of("{}","{broken", "{\"id\":\"evt_bad\",\"accountId\":\"acct_test\",\"credits\":0}","{\"id\":\"evt_bad\",\"accountId\":\"acct_test\",\"credits\":1.9}"))
            assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,headers(body)),String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(inbox.events(null,null,100,0)).isEmpty();
    }
    @Test void oversizedBodyIsRejectedBeforeParsing() {
        String body="x".repeat(16385);
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,headers(body)),String.class).getStatusCode().value()).isEqualTo(413);
    }
    @Test void transientFailuresBackOffThenSucceed() {
        inbox.accept(event("evt_retry"));inbox.fault("evt_retry","TRANSIENT",2);
        worker.tick(1);assertThat(status("evt_retry")).isEqualTo("RETRY_WAIT");assertThat(ledger()).isZero();
        assertThat(db.queryForObject("SELECT next_attempt_at>updated_at FROM events",Boolean.class)).isTrue();
        due();worker.tick(1);due();worker.tick(1);
        assertThat(status("evt_retry")).isEqualTo("SUCCEEDED");assertThat(credits()).isEqualTo(10);
        assertThat(db.queryForObject("SELECT attempt_count FROM events",Integer.class)).isEqualTo(3);
    }
    @Test void exhaustedRetriesCanBeCorrectedAndReplayedWithoutLosingHistory() {
        inbox.accept(event("evt_exhaust"));inbox.fault("evt_exhaust","TRANSIENT",20);
        for(int i=0;i<config.maxAttempts();i++) { due();worker.tick(1); }
        assertThat(status("evt_exhaust")).isEqualTo("DEAD_LETTER");assertThat(ledger()).isZero();
        inbox.fault("evt_exhaust","NONE",0);inbox.replay("evt_exhaust");worker.tick(1);
        assertThat(status("evt_exhaust")).isEqualTo("SUCCEEDED");assertThat(credits()).isEqualTo(10);
        assertThat(db.queryForObject("SELECT count(*) FROM attempts",Integer.class)).isEqualTo(config.maxAttempts()+1);
    }
    @Test void poisonEventIsDeadLetteredImmediately() {
        inbox.accept(event("evt_poison"));inbox.fault("evt_poison","POISON",0);worker.tick(1);
        assertThat(status("evt_poison")).isEqualTo("DEAD_LETTER");assertThat(ledger()).isZero();
        assertThat(db.queryForObject("SELECT outcome FROM attempts",String.class)).isEqualTo("TERMINAL");
    }
    @Test void crashBeforeCommitRollsBackLedgerAndBalanceThenRecovers() {
        inbox.accept(event("evt_before"));inbox.fault("evt_before","CRASH_BEFORE_COMMIT",0);worker.tick(1);
        assertThat(status("evt_before")).isEqualTo("PROCESSING");assertThat(credits()).isZero();assertThat(ledger()).isZero();
        expire();worker.tick(1);assertThat(credits()).isEqualTo(10);assertThat(ledger()).isEqualTo(1);assertThat(status("evt_before")).isEqualTo("SUCCEEDED");
    }
    @Test void crashAfterCommitIsReconciledWithoutAnotherCredit() {
        inbox.accept(event("evt_after"));inbox.fault("evt_after","CRASH_AFTER_COMMIT",0);worker.tick(1);
        assertThat(status("evt_after")).isEqualTo("PROCESSING");assertThat(credits()).isEqualTo(10);
        expire();assertThat(worker.recoverExpired()).isEqualTo(1);
        assertThat(status("evt_after")).isEqualTo("SUCCEEDED");assertThat(credits()).isEqualTo(10);assertThat(ledger()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT outcome FROM attempts",String.class)).isEqualTo("RECOVERED");
    }
    @Test void staleWorkerCannotCommitAfterReassignment() {
        inbox.accept(event("evt_fenced"));var stale=worker.claim();expire();worker.recoverExpired();
        var other=new QueueWorker(db,tx,inbox,config,metrics);var fresh=other.claim();
        worker.process(stale);assertThat(ledger()).isZero();other.process(fresh);
        assertThat(ledger()).isEqualTo(1);assertThat(credits()).isEqualTo(10);assertThat(status("evt_fenced")).isEqualTo("SUCCEEDED");
    }
    @Test void completedReplayIsANoopAndPendingReplayIsRejected() {
        inbox.accept(event("evt_replay"));assertThatThrownBy(()->inbox.replay("evt_replay")).isInstanceOf(ApiProblem.class);
        worker.tick(1);assertThat(inbox.replay("evt_replay").get("replayed")).isEqualTo(false);assertThat(credits()).isEqualTo(10);
    }
    @Test void expiredFinalAttemptDeadLettersUncommittedWork() {
        inbox.accept(event("evt_final"));db.update("UPDATE events SET cycle_attempt=max_attempts-1 WHERE id='evt_final'");
        worker.claim();expire();worker.recoverExpired();assertThat(status("evt_final")).isEqualTo("DEAD_LETTER");assertThat(ledger()).isZero();
    }
    @Test void auditIsAppendOnly() {
        inbox.accept(event("evt_audit"));
        assertThatThrownBy(()->db.update("UPDATE activity SET detail='changed' WHERE event_id='evt_audit'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("DELETE FROM activity WHERE event_id='evt_audit'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void adminEndpointsRequireTokenAndSupportFiltering() {
        assertThat(http.getForEntity("/api/events",String.class).getStatusCode().value()).isEqualTo(401);
        var h=new HttpHeaders();h.set("X-Admin-Token",config.adminToken());
        inbox.accept(event("evt_filter"));worker.tick(1);
        var result=http.exchange("/api/events?status=SUCCEEDED&q=filter&limit=1",HttpMethod.GET,new HttpEntity<>(h),List.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getBody()).hasSize(1);
        assertThat(http.getForEntity("/actuator/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/prometheus",String.class).getStatusCode().value()).isEqualTo(401);
    }
    @Test void demoScenarioIsConfiguredBeforeAWorkerCanClaimIt() {
        var h=new HttpHeaders();h.set("X-Admin-Token",config.adminToken());h.setContentType(MediaType.APPLICATION_JSON);
        var result=http.postForEntity("/api/demo",new HttpEntity<>("{\"scenario\":\"crash-after\"}",h),Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id=result.getBody().get("id").toString();worker.tick(1);
        assertThat(status(id)).isEqualTo("PROCESSING");assertThat(credits()).isEqualTo(10);
    }
    @Test void missingSignatureAndFutureTimestampAreRejected() {
        String body="{\"id\":\"evt_time\",\"accountId\":\"acct_test\",\"credits\":10}";
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,h),String.class).getStatusCode().value()).isEqualTo(401);
        long future=Instant.now().getEpochSecond()+3600;h.set("X-Webhook-Timestamp",String.valueOf(future));h.set("X-Webhook-Signature",signatures.sign(body.getBytes(StandardCharsets.UTF_8),future));
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,h),String.class).getStatusCode().value()).isEqualTo(401);
    }
    @Test void scalarCoercionUnknownFieldsAndTrailingJsonAreRejected() {
        String base="{\"id\":\"evt_strict\",\"accountId\":\"acct_test\",\"credits\":10}";
        for(String body:List.of(base.replace(":10",":\"10\""),base.replace("\"evt_strict\"","123"),base.replace("10}","10,\"extra\":true}"),base+" {}","null"))
            assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,headers(body)),String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(inbox.events(null,null,100,0)).isEmpty();
    }
    @Test void unsupportedContentTypeReturns415() {
        String body="{}";var h=headers(body);h.setContentType(MediaType.TEXT_PLAIN);
        assertThat(http.postForEntity("/webhooks/credits",new HttpEntity<>(body,h),String.class).getStatusCode().value()).isEqualTo(415);
    }
    @Test void unknownEventAndInvalidDemoInputReturnClientErrors() {
        var h=new HttpHeaders();h.set("X-Admin-Token",config.adminToken());h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange("/api/events/missing",HttpMethod.GET,new HttpEntity<>(h),String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.postForEntity("/api/demo",new HttpEntity<>("{}",h),String.class).getStatusCode().value()).isEqualTo(400);
        inbox.accept(event("evt_fault"));
        assertThat(http.postForEntity("/api/events/evt_fault/fault",new HttpEntity<>("{}",h),String.class).getStatusCode().value()).isEqualTo(400);
    }
    @Test void activeClaimCannotBeManuallyReplayedOrReconfigured() {
        inbox.accept(event("evt_busy"));worker.claim();
        assertThatThrownBy(()->inbox.replay("evt_busy")).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(()->inbox.fault("evt_busy","NONE",0)).isInstanceOf(ApiProblem.class);
    }
}
