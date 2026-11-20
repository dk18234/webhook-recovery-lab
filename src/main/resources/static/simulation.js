// Browser-only companion. It mirrors the domain transitions, not PostgreSQL's concurrency guarantees.
export class Simulation {
  constructor(storage=null,now=()=>Date.now()) {
    this.storage=storage;this.now=now;this.key='webhook-recovery-lab-v1';
    try { this.state=JSON.parse(storage?.getItem(this.key)||'null'); } catch { this.state=null; }
    if(!this.state || this.state.version!==1 || !Array.isArray(this.state.events)) this.state={version:1,events:[],paused:false};
  }
  save() { try { this.storage?.setItem(this.key,JSON.stringify(this.state)); } catch { /* A private browser can still run an in-memory demo. */ } }
  audit(event,action,detail) { event.activity.push({id:event.activity.length+1,event_id:event.id,action,detail,created_at:this.now()}); }
  create(scenario='normal') {
    if(!['normal','duplicates','transient','poison','crash-before','crash-after'].includes(scenario)) throw Error('Unknown scenario.');
    if(this.state.events.length>=200) throw Error('Sandbox limit reached. Export the evidence, then reset the sandbox.');
    const event={id:'evt_'+crypto.randomUUID().replaceAll('-','').slice(0,12),account_id:'acct_demo',credits:10,status:'RECEIVED',delivery_count:scenario==='duplicates'?10:1,attempt_count:0,cycle_attempt:0,max_attempts:5,created_at:this.now(),updated_at:this.now(),next_attempt_at:this.now(),lease_until:null,last_error:null,fault_mode:({'transient':'TRANSIENT','poison':'POISON','crash-before':'CRASH_BEFORE_COMMIT','crash-after':'CRASH_AFTER_COMMIT'})[scenario]||'NONE',failures_remaining:scenario==='transient'?2:0,attempts:[],activity:[],ledger:[]};
    this.audit(event,'ACCEPTED','Validated synthetic event saved in this browser sandbox.');
    for(let i=1;i<event.delivery_count;i++) this.audit(event,'DUPLICATE_RECEIVED','Repeat delivery acknowledged; existing event retained.');
    if(event.fault_mode!=='NONE') this.audit(event,'FAULT_CONFIGURED',event.fault_mode);
    this.state.events.unshift(event);this.save();return {id:event.id,scenario,deliveries:event.delivery_count};
  }
  recover(event) {
    if(event.status!=='PROCESSING' || event.lease_until>this.now()) return;
    const committed=event.ledger.length>0;
    const attempt=event.attempts.at(-1);
    attempt.outcome=committed?'RECOVERED':'LEASE_EXPIRED';attempt.finished_at=this.now();
    event.status=committed?'SUCCEEDED':event.cycle_attempt>=event.max_attempts?'DEAD_LETTER':'RETRY_WAIT';
    event.lease_until=null;event.updated_at=this.now();event.next_attempt_at=this.now();
    event.last_error=committed?null:'Worker lease expired before a business commit.';
    this.audit(event,committed?'RECONCILED':'LEASE_RECOVERED',committed?'Ledger proves the credit was committed. Completion recovered without another credit.':'No committed effect found. Expired worker lease recovered.');
  }
  process(event) {
    event.status='PROCESSING';event.attempt_count++;event.cycle_attempt++;event.lease_until=this.now()+8000;event.updated_at=this.now();
    const attempt={number:event.attempt_count,worker_id:'browser-worker',outcome:'RUNNING',started_at:this.now(),finished_at:null,message:null};event.attempts.push(attempt);
    this.audit(event,'CLAIMED',`Attempt ${attempt.number} acquired a simulated 8-second lease.`);
    const mode=event.fault_mode;
    if(mode.startsWith('CRASH_')) event.fault_mode='NONE';
    if(mode==='POISON' || (mode==='TRANSIENT' && event.failures_remaining>0)) {
      const terminal=mode==='POISON';event.failures_remaining=Math.max(0,event.failures_remaining-1);
      const dead=terminal || event.cycle_attempt>=event.max_attempts;
      event.status=dead?'DEAD_LETTER':'RETRY_WAIT';event.lease_until=null;
      event.last_error=terminal?'Synthetic validation failure: account mapping is invalid.':'Synthetic dependency timeout; retry is permitted.';
      event.next_attempt_at=this.now()+2000*2**(event.cycle_attempt-1);
      attempt.outcome=terminal?'TERMINAL':dead?'EXHAUSTED':'RETRYABLE';attempt.message=event.last_error;attempt.finished_at=this.now();
      this.audit(event,dead?'DEAD_LETTERED':'RETRY_SCHEDULED',event.last_error);return;
    }
    if(mode==='CRASH_BEFORE_COMMIT') { this.audit(event,'SIMULATED_CRASH','Business transaction rolled back. Waiting for lease recovery.');return; }
    if(!event.ledger.length) { event.ledger.push({event_id:event.id,account_id:event.account_id,credits:event.credits,applied_at:this.now()});this.audit(event,'EFFECT_COMMITTED','One ledger entry and the account balance committed together.'); }
    if(mode==='CRASH_AFTER_COMMIT') { this.audit(event,'SIMULATED_CRASH','Business commit exists; completion acknowledgement was lost.');return; }
    event.status='SUCCEEDED';event.lease_until=null;event.last_error=null;attempt.outcome='SUCCEEDED';attempt.finished_at=this.now();
    this.audit(event,'SUCCEEDED','Processing completed. One credit effect is recorded.');
  }
  tick(limit=8,manual=false) {
    if(this.state.paused && !manual) return;
    for(const e of this.state.events) this.recover(e);
    const ready=this.state.events.filter(e=>['RECEIVED','RETRY_WAIT'].includes(e.status)&&e.next_attempt_at<=this.now()).sort((a,b)=>a.next_attempt_at-b.next_attempt_at||a.created_at-b.created_at);
    ready.slice(0,limit).forEach(e=>this.process(e));this.save();return Math.min(ready.length,limit);
  }
  get(id) { const e=this.state.events.find(e=>e.id===id);if(!e) throw Error('Event not found.');return e; }
  replay(id) {
    const e=this.get(id);if(e.status==='SUCCEEDED') return {replayed:false};
    if(e.status!=='DEAD_LETTER') throw Error('Only dead-letter events can be replayed.');
    e.status='RECEIVED';e.cycle_attempt=0;e.next_attempt_at=this.now();e.updated_at=this.now();e.last_error=null;
    this.audit(e,'MANUAL_REPLAY','New retry cycle requested. Event identity, attempts, and ledger preserved.');this.save();return {replayed:true};
  }
  stats() {
    const all=this.state.events;const entries=all.flatMap(e=>e.ledger);const counts={};
    for(const a of all.flatMap(e=>e.attempts)) counts[a.outcome]=(counts[a.outcome]||0)+1;
    const balances={};for(const l of entries) balances[l.account_id]=(balances[l.account_id]||0)+l.credits;
    return {totals:{total:all.length,duplicates:all.reduce((s,e)=>s+e.delivery_count-1,0),succeeded:all.filter(e=>e.status==='SUCCEEDED').length,dead_letter:all.filter(e=>e.status==='DEAD_LETTER').length,pending:all.filter(e=>!['SUCCEEDED','DEAD_LETTER'].includes(e.status)).length},ledger:{effects:entries.length,credits:entries.reduce((s,l)=>s+l.credits,0)},accounts:Object.entries(balances).map(([id,balance])=>({id,balance})),attempts:Object.entries(counts).map(([outcome,count])=>({outcome,count}))};
  }
  async request(path,method='GET',body) {
    const url=new URL(path,'https://sandbox.invalid');const parts=url.pathname.split('/').filter(Boolean);
    if(url.pathname==='/api/config') return {mode:'simulation',demoEnabled:true,workerEnabled:true,maxAttempts:5,leaseSeconds:8,paused:this.state.paused};
    if(url.pathname==='/api/stats') return this.stats();
    if(url.pathname==='/api/events') {
      const status=url.searchParams.get('status'),q=(url.searchParams.get('q')||'').toLowerCase();const limit=Number(url.searchParams.get('limit')||50),offset=Number(url.searchParams.get('offset')||0);
      return this.state.events.filter(e=>(!status||e.status===status)&&(!q||e.id.toLowerCase().includes(q)||e.account_id.toLowerCase().includes(q))).slice(offset,offset+limit);
    }
    if(parts[1]==='events' && parts[2]) {
      const e=this.get(decodeURIComponent(parts[2]));
      if(parts[3]==='replay') return this.replay(e.id);
      if(parts[3]==='fault') { e.fault_mode=body.mode;e.failures_remaining=body.remaining;this.audit(e,'FAULT_CONFIGURED',body.mode);this.save();return {}; }
      return {event:e,attempts:e.attempts,activity:e.activity,ledger:e.ledger};
    }
    if(parts[1]==='worker') { if(parts[2]==='tick') this.tick(1,true);else this.state.paused=parts[2]==='pause';this.save();return {paused:this.state.paused}; }
    if(url.pathname==='/api/demo') return this.create(body.scenario);
    throw Error('Unsupported sandbox operation.');
  }
  seed() {
    if(this.state.events.length) return;
    this.create('normal');this.create('duplicates');this.tick();this.create('poison');this.tick();this.create('transient');this.tick();
  }
  reset() { this.state={version:1,events:[],paused:false};this.save(); }
}
