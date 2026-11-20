import { Simulation } from './simulation.js';
const $=s=>document.querySelector(s);
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const scenarios=[
  {id:'normal',symbol:'✓',title:'Clean delivery',description:'A synthetic event enters the inbox and completes normally.',expectation:'1 delivery · 1 attempt · 10 credits'},
  {id:'duplicates',symbol:'⧉',title:'Duplicate storm',description:'Deliver the same logical event 10 times. Watch the ledger protect its business effect.',expectation:'10 deliveries · 1 event · 10 credits'},
  {id:'transient',symbol:'↻',title:'Transient failure',description:'A dependency times out twice. Exponential backoff gives it room to recover.',expectation:'2 retries · eventual success · 10 credits'},
  {id:'poison',symbol:'!',title:'Poison event',description:'A terminal validation failure sends the event directly to the dead-letter inbox.',expectation:'No credits until corrected and replayed'},
  {id:'crash-before',symbol:'⏸',title:'Crash before commit',description:'The worker disappears inside the business transaction. The ledger and balance roll back.',expectation:'Lease expires · retry · one effect'},
  {id:'crash-after',symbol:'⌁',title:'Crash after commit',description:'Credits commit, then the completion acknowledgement is lost. Recover using ledger evidence.',expectation:'Lease expires · reconcile · no extra credit'},
];
const simulationMode=document.documentElement.dataset.runtime==='simulation';
let storage=null;try{storage=localStorage;}catch{}
const simulation=simulationMode?new Simulation(storage):null;
let token='';try{token=sessionStorage.getItem('webhook-admin-token')||'';}catch{}
let config={},view='overview',page=0,selected=null,busy=false,refreshing=false,toastTimer,lastDetail='';
const pageSize=15;
async function request(path,method='GET',body) {
  if(simulation) return simulation.request(path,method,body);
  const response=await fetch(path,{method,headers:{'X-Admin-Token':token,...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
  if(response.status===401) { $('#login-dialog').showModal();throw Error('Enter your admin token to connect.'); }
  const data=await response.json();if(!response.ok) throw Error(data.message||`Request failed (${response.status}).`);return data;
}
function toast(message,error=false) { clearTimeout(toastTimer);$('#toast').textContent=message;$('#toast').className='toast show'+(error?' error':'');toastTimer=setTimeout(()=>$('#toast').className='toast',4500); }
function badge(status) {
  const tone={SUCCEEDED:'mint',RETRY_WAIT:'amber',PROCESSING:'blue',DEAD_LETTER:'red',RECEIVED:'neutral'}[status]||'neutral';
  return `<span class="pill ${tone}">${esc(status.replaceAll('_',' '))}</span>`;
}
function time(value) { if(!value) return '—';return new Date(value).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit',second:'2-digit'}); }
function age(value) { const sec=Math.max(0,Math.floor((Date.now()-new Date(value).getTime())/1000));return sec<5?'Just now':sec<60?`${sec}s ago`:sec<3600?`${Math.floor(sec/60)}m ago`:`${Math.floor(sec/3600)}h ago`; }
function eventTable(events) {
  if(!events.length) return '<div class="empty">No events here yet. Run a scenario to create one.</div>';
  return `<table><thead><tr><th>EVENT / ACCOUNT</th><th>STATUS</th><th>DELIVERIES</th><th>ATTEMPTS</th><th>RECEIVED</th><th><span class="sr-only">Inspect event</span></th></tr></thead><tbody>${events.map(e=>`<tr><td class="event-id"><button data-inspect="${esc(e.id)}">${esc(e.id)}</button><span class="account-id">${esc(e.account_id)} · ${esc(e.credits)} credits</span></td><td class="status-cell">${badge(e.status)}</td><td>${esc(e.delivery_count)}${e.delivery_count>1?' <span class="cell-muted">/ deduped</span>':''}</td><td>${esc(e.attempt_count)} <span class="cell-muted">/ ${esc(e.max_attempts)} per cycle</span></td><td class="cell-muted" title="${esc(new Date(e.created_at).toLocaleString())}">${age(e.created_at)}</td><td><button class="inspect" data-inspect="${esc(e.id)}" aria-label="Inspect ${esc(e.id)}">Inspect</button></td></tr>`).join('')}</tbody></table>`;
}
function updateStats(stats) {
  const t=stats.totals,l=stats.ledger;
  const values=[['Accepted events',t.total,`${t.pending} pending · ${t.succeeded} completed`,'▤'],['Duplicate deliveries',t.duplicates,'Acknowledged without a new event','⧉'],['Dead-letter events',t.dead_letter,'Ready for inspection and recovery','!'],['Credits applied',l.credits,`${l.effects} unique ledger entries`,'✓']];
  $('#stats').innerHTML=values.map(([label,value,note,icon])=>`<article class="stat"><div class="stat-label">${label}<span class="stat-icon" aria-hidden="true">${icon}</span></div><div class="stat-value">${Number(value).toLocaleString()}</div><div class="stat-note">${note}</div></article>`).join('');
  $('#nav-count').textContent=t.total;
}
function updateWorker() {
  const paused=config.paused||!config.workerEnabled;
  $('#worker-state').textContent=paused?'Paused':'Running';$('#worker-state').className='pill '+(paused?'amber':'mint');
  $('#worker-toggle').textContent=paused?'Resume worker':'Pause worker';
  $('#worker-toggle').disabled=!config.workerEnabled;
  $('#worker-description').textContent=paused?'Automatic polling is paused. You can still process one ready event.':`Claims ready work. Expired leases recover after ${config.leaseSeconds||15} seconds.`;
}
async function refresh() {
  if(refreshing) return;refreshing=true;
  try {
    const params=new URLSearchParams({limit:String(pageSize),offset:String(page*pageSize)});
    if($('#search').value) params.set('q',$('#search').value);if($('#status-filter').value) params.set('status',$('#status-filter').value);
    const [stats,recent,events,current]=await Promise.all([request('/api/stats'),request('/api/events?limit=6'),request('/api/events?'+params),request('/api/config')]);
    config=current;updateStats(stats);updateWorker();$('#recent-events').innerHTML=eventTable(recent);$('#all-events').innerHTML=eventTable(events);
    $('#previous').disabled=page===0;$('#next').disabled=events.length<pageSize;$('#page-number').textContent=`Page ${page+1}`;
    if(selected&&$('#event-dialog').open) await renderDetail(selected);
  }catch(e){if(!$('#login-dialog').open)toast(e.message,true);}finally{refreshing=false;}
}
function changeView(next) {
  view=next;const titles={overview:['Recovery console','Events can arrive twice. Their effects shouldn’t.'],inbox:['Event inbox','Every accepted event, delivery, and processing outcome.'],scenarios:['Failure lab','Break the flow. Watch the system find its way back.'],architecture:['Inside the system','Small transactions. Explicit guarantees. Visible recovery.'],guide:['Learn from the inside','Trace a request, question a decision, and make it your own.']};
  document.querySelectorAll('.view-panel').forEach(p=>p.hidden=p.id!==next+'-panel');
  document.querySelectorAll('.nav-item').forEach(b=>{b.classList.toggle('active',b.dataset.view===next);if(b.dataset.view===next)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');});
  $('#page-title').innerHTML=esc(titles[next][0])+'<span>.</span>';$('#page-subtitle').textContent=titles[next][1];
  $('#open-scenarios').hidden=next==='scenarios';history.replaceState(null,'','#'+next);window.scrollTo({top:0,behavior:'instant'});
}
async function renderDetail(id) {
  const detail=await request('/api/events/'+encodeURIComponent(id));if(selected!==id)return;
  const fingerprint=JSON.stringify(detail);if(lastDetail===fingerprint)return;lastDetail=fingerprint;
  const e=detail.event;$('#detail-title').textContent=e.id;
  $('#detail-content').innerHTML=`<div class="detail-summary">${badge(e.status)}<span class="cell-muted">${esc(e.account_id)} · ${esc(e.credits)} credits</span></div><div class="detail-grid"><div class="detail-metric"><strong>${esc(e.delivery_count)}</strong><span>Deliveries</span></div><div class="detail-metric"><strong>${esc(e.attempt_count)}</strong><span>Processing attempts</span></div><div class="detail-metric"><strong>${detail.ledger.length}</strong><span>Ledger entries</span></div></div>${e.last_error?`<p class="error-text">${esc(e.last_error)}</p>`:''}${e.status==='PROCESSING'?`<p>Lease expires at ${time(e.lease_until)}. The recovery loop will reconcile the ledger before deciding whether to retry.</p>`:''}<div class="detail-actions">${e.status==='DEAD_LETTER'?`<button class="button primary" data-replay="${esc(e.id)}">Replay event</button>`:''}${config.demoEnabled&&e.fault_mode!=='NONE'&&e.status!=='PROCESSING'?`<button class="button secondary" data-clear-fault="${esc(e.id)}">Clear injected fault</button>`:''}<button class="button subtle" data-export-event="${esc(e.id)}">Export event evidence</button></div><div class="ledger-proof ${detail.ledger.length?'':'pending'}">${detail.ledger.length?`✓ One committed ledger entry · ${esc(detail.ledger[0].credits)} credits applied at ${time(detail.ledger[0].applied_at)}.`:'No business effect has been committed for this event.'}</div><h3 class="detail-section-title">Processing attempts</h3><div class="attempt-list">${detail.attempts.length?detail.attempts.map(a=>`#${esc(a.number)} · ${esc(a.outcome)} · ${esc(a.worker_id)} · ${time(a.started_at)}`).join('<br>'):'Waiting for the first worker claim.'}</div><h3 class="detail-section-title">Audit timeline</h3><div class="timeline">${detail.activity.map(a=>`<div class="timeline-item"><strong>${esc(a.action.replaceAll('_',' '))}</strong><p>${esc(a.detail)}</p><time>${time(a.created_at)}</time></div>`).join('')}</div>`;
}
async function inspect(id) { selected=id;await renderDetail(id);if(!$('#event-dialog').open)$('#event-dialog').showModal(); }
function downloadJson(name,data) { const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
async function action(fn) { if(busy)return;busy=true;try{await fn();await refresh();}catch(e){toast(e.message,true);}finally{busy=false;} }
$('#scenario-grid').innerHTML=scenarios.map(s=>`<article class="scenario-card"><span class="scenario-symbol" aria-hidden="true">${s.symbol}</span><h2>${s.title}</h2><p>${s.description}</p><div class="scenario-expectation">${s.expectation}</div><button class="button secondary" data-scenario="${s.id}">Run scenario</button></article>`).join('');
document.addEventListener('click',event=>{
  const b=event.target.closest('button');if(!b)return;
  if(b.dataset.view)changeView(b.dataset.view);
  if(b.dataset.inspect)action(()=>inspect(b.dataset.inspect));
  if(b.dataset.scenario)action(async()=>{const r=await request('/api/demo','POST',{scenario:b.dataset.scenario});toast('Scenario created. Inspect the event as it moves through recovery.');await inspect(r.id);});
  if(b.dataset.replay)action(async()=>{await request(`/api/events/${encodeURIComponent(b.dataset.replay)}/replay`,'POST');toast('Event queued for a new processing cycle.');});
  if(b.dataset.clearFault)action(async()=>{await request(`/api/events/${encodeURIComponent(b.dataset.clearFault)}/fault`,'POST',{mode:'NONE',remaining:0});toast('Injected fault cleared. You can now replay the event.');});
  if(b.dataset.exportEvent)action(async()=>downloadJson(b.dataset.exportEvent+'.json',{mode:config.mode,...await request('/api/events/'+encodeURIComponent(b.dataset.exportEvent))}));
});
$('#open-scenarios').addEventListener('click',()=>changeView('scenarios'));
$('#close-detail').addEventListener('click',()=>$('#event-dialog').close());
$('#event-dialog').addEventListener('close',()=>{selected=null;lastDetail='';});
$('#event-dialog').addEventListener('click',e=>{if(e.target===$('#event-dialog')&&e.offsetX<0)$('#event-dialog').close();});
$('#worker-toggle').addEventListener('click',()=>action(async()=>{await request('/api/worker/'+(config.paused?'resume':'pause'),'POST');toast(config.paused?'Worker resumed.':'Worker paused. Events remain stored.');}));
$('#worker-tick').addEventListener('click',()=>action(()=>request('/api/worker/tick','POST')));
$('#refresh').addEventListener('click',refresh);
let searchTimer;$('#search').addEventListener('input',()=>{page=0;clearTimeout(searchTimer);searchTimer=setTimeout(refresh,180);});
$('#status-filter').addEventListener('change',()=>{page=0;refresh();});
$('#previous').addEventListener('click',()=>{page=Math.max(0,page-1);refresh();});$('#next').addEventListener('click',()=>{page++;refresh();});
$('#export').addEventListener('click',()=>action(async()=>{
  const stats=await request('/api/stats');const events=await request('/api/events?limit=100');
  const evidence=await Promise.all(events.map(e=>request('/api/events/'+encodeURIComponent(e.id))));
  downloadJson('webhook-lab-evidence.json',{exportedAt:new Date().toISOString(),mode:config.mode,note:'Export includes the most recent 100 events; totals cover all events.',stats,events:evidence});toast('Evidence exported.');
}));
$('#reset').addEventListener('click',()=>{if(!simulation)return;if(!confirm('Clear the events in this browser sandbox? Export evidence first if you want to keep it.'))return;simulation.reset();page=0;refresh();toast('Browser sandbox reset.');});
$('#login-dialog').addEventListener('cancel',e=>e.preventDefault());
$('#login-form').addEventListener('submit',async e=>{
  e.preventDefault();token=$('#admin-token').value;try{
    const response=await fetch('/api/config',{headers:{'X-Admin-Token':token}});if(!response.ok)throw Error('Token was not accepted. Check ADMIN_TOKEN in your environment.');
    config=await response.json();try{sessionStorage.setItem('webhook-admin-token',token);}catch{}
    $('#login-dialog').close();$('#login-error').textContent='';await refresh();
  }catch(error){$('#login-error').textContent=error.message;}
});
async function start() {
  if(simulation){simulation.seed();config=await request('/api/config');$('#mode-badge').textContent='Browser simulation';$('#mode-notice').textContent='Interactive browser simulation · Data stays on this device. Download the project to run the tested Java + PostgreSQL backend.';}
  else {$('#mode-badge').textContent='Java + PostgreSQL';$('#mode-notice').textContent='Connected backend · Events and audit history are persisted in PostgreSQL.';$('#download-link').hidden=true;$('#portfolio-link').href='https://dinesh-rama-portfolio.blushcocoa19.chatgpt.site';try{config=await request('/api/config');}catch{} }
  $('#reset').hidden=!simulation;
  const hash=location.hash.slice(1);changeView(['overview','inbox','scenarios','architecture','guide'].includes(hash)?hash:'overview');
  await refresh();
  setInterval(async()=>{if(document.hidden||busy||$('#login-dialog').open)return;simulation?.tick();await refresh();},1000);
}
start();
