package dev.dineshrama.webhook;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DemoScenarios {
    private final Inbox inbox;
    private final TransactionTemplate tx;
    public DemoScenarios(Inbox inbox,TransactionTemplate tx) { this.inbox=inbox;this.tx=tx; }
    public Map<String,Object> create(CreditEvent event,String scenario) {
        return tx.execute(s -> {
            inbox.accept(event);
            switch(scenario) {
                case "duplicates" -> { for(int i=0;i<9;i++) inbox.accept(event); }
                case "transient" -> inbox.fault(event.id(),"TRANSIENT",2);
                case "poison" -> inbox.fault(event.id(),"POISON",0);
                case "crash-before" -> inbox.fault(event.id(),"CRASH_BEFORE_COMMIT",0);
                case "crash-after" -> inbox.fault(event.id(),"CRASH_AFTER_COMMIT",0);
                default -> { }
            }
            return Map.of("id",event.id(),"scenario",scenario,"deliveries",scenario.equals("duplicates")?10:1);
        });
    }
}
