package dev.dineshrama.webhook;

import static org.assertj.core.api.Assertions.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class RestartRecoveryTest {
    ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(LabApplication.class).run("--spring.main.web-application-type=none","--spring.datasource.url="+url,"--spring.datasource.username=postgres","--spring.datasource.password=","--lab.worker-enabled=false","--spring.main.banner-mode=off");
    }
    @Test void acceptedAndAbandonedWorkSurviveApplicationContextRestart() throws Exception {
        try(var pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("unix_socket_directories", "").start()) {
            String url=pg.getJdbcUrl("postgres","postgres");
            try(var first=start(url)) {
                var inbox=first.getBean(Inbox.class);var worker=first.getBean(QueueWorker.class);
                inbox.accept(new CreditEvent("evt_pending","acct_restart",10));
                inbox.accept(new CreditEvent("evt_abandoned","acct_restart",20));
                inbox.fault("evt_abandoned","CRASH_AFTER_COMMIT",0);
                // Select a committed event while leaving a separate event unclaimed across restart.
                first.getBean(JdbcTemplate.class).update("UPDATE events SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id='evt_pending'");
                worker.tick(1);
                first.getBean(JdbcTemplate.class).update("UPDATE events SET lease_until=clock_timestamp()-interval '1 second' WHERE status='PROCESSING'");
            }
            try(var second=start(url)) {
                var db=second.getBean(JdbcTemplate.class);db.update("UPDATE events SET next_attempt_at=clock_timestamp() WHERE id='evt_pending'");
                second.getBean(QueueWorker.class).tick(10);
                assertThat(db.queryForObject("SELECT count(*) FROM events WHERE status='SUCCEEDED'",Integer.class)).isEqualTo(2);
                assertThat(db.queryForObject("SELECT balance FROM accounts WHERE id='acct_restart'",Long.class)).isEqualTo(30);
                assertThat(db.queryForObject("SELECT count(*) FROM credit_ledger",Integer.class)).isEqualTo(2);
            }
        }
    }
}
