package dev.dineshrama.webhook;

import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class LabController {
    private final Inbox inbox;
    private final QueueWorker worker;
    private final Signatures signatures;
    private final ObjectMapper json;
    private final Validator validator;
    private final LabProperties config;
    private final DemoScenarios demoScenarios;
    public LabController(Inbox inbox,QueueWorker worker,Signatures signatures,ObjectMapper json,Validator validator,LabProperties config,DemoScenarios demoScenarios) {
        this.inbox=inbox;this.worker=worker;this.signatures=signatures;this.json=json;this.validator=validator;this.config=config;this.demoScenarios=demoScenarios;
    }
    @PostMapping(value="/webhooks/credits",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Inbox.Receipt> receive(@RequestBody byte[] body,@RequestHeader(value="X-Webhook-Timestamp",required=false) String timestamp,@RequestHeader(value="X-Webhook-Signature",required=false) String signature) {
        signatures.verify(body,timestamp,signature);
        CreditEvent event=parse(body);
        var receipt=inbox.accept(event);
        return ResponseEntity.status(receipt.duplicate()?200:202).body(receipt);
    }
    private CreditEvent parse(byte[] body) {
        try {
            JsonNode tree=json.readTree(body);
            if(!tree.isObject() || !tree.path("id").isTextual() || !tree.path("accountId").isTextual() || !tree.path("credits").isIntegralNumber()) throw new IllegalArgumentException();
            // Explicitly reject unknown fields and fractional numbers instead of silently coercing them.
            var reader=json.readerFor(CreditEvent.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            CreditEvent event=reader.readValue(body);
            if(event==null || !validator.validate(event).isEmpty()) throw new IllegalArgumentException();
            return event;
        } catch(Exception e) { throw new ApiProblem(400,"INVALID_EVENT","Expected id, accountId (letters, digits, _ or -; at most 80 characters), and integer credits between 1 and 1000000."); }
    }
    @GetMapping("/api/events") public Object events(@RequestParam(required=false) String status,@RequestParam(required=false) String q,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) { return inbox.events(status,q,limit,offset); }
    @GetMapping("/api/events/{id}") public Object detail(@PathVariable String id) { return inbox.detail(id); }
    @PostMapping("/api/events/{id}/replay") public Object replay(@PathVariable String id) { return inbox.replay(id); }
    @GetMapping("/api/stats") public Object stats() { return inbox.stats(); }
    @GetMapping("/api/config") public Object config() { return Map.of("mode","backend","demoEnabled",config.demoEnabled(),"maxAttempts",config.maxAttempts(),"leaseSeconds",config.leaseSeconds(),"workerEnabled",config.workerEnabled(),"workerId",worker.workerId(),"paused",worker.paused()); }
    @PostMapping("/api/worker/{action}") public Object worker(@PathVariable String action) {
        if(!Set.of("pause","resume","tick").contains(action)) throw new ApiProblem(400,"INVALID_ACTION","Use pause, resume, or tick.");
        int processed=0;
        if(action.equals("tick")) processed=worker.tick(1);else worker.pause(action.equals("pause"));
        return Map.of("paused",worker.paused(),"processed",processed);
    }
    public record FaultRequest(String mode,int remaining) {}
    @PostMapping("/api/events/{id}/fault") public Object fault(@PathVariable String id,@RequestBody FaultRequest request) {
        requireDemo();inbox.fault(id,request.mode(),request.remaining());return Map.of("id",id,"mode",request.mode());
    }
    public record DemoRequest(String scenario) {}
    @PostMapping("/api/demo") public Object demo(@RequestBody DemoRequest request) {
        requireDemo();
        if(request.scenario()==null || !Set.of("normal","duplicates","transient","poison","crash-before","crash-after").contains(request.scenario())) throw new ApiProblem(400,"INVALID_SCENARIO","Unknown failure scenario.");
        String id="evt_"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        var event=new CreditEvent(id,"acct_demo",10);
        // Accept and configure while holding the event's transaction lock so scheduled workers cannot win the race.
        return demoScenarios.create(event,request.scenario());
    }
    private void requireDemo() { if(!config.demoEnabled()) throw new ApiProblem(404,"DEMO_DISABLED","Failure injection is disabled on this deployment."); }
}
