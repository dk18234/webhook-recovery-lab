export const DIAGNOSES = {
  ACK_LOST: 'Completion acknowledgement lost', TRANSIENT_TIMEOUT: 'Transient dependency timeout',
  POISON_EVENT: 'Terminal mapping failure', DUPLICATE_PROTECTED: 'Duplicate delivery protected',
  COMPLETED: 'Business effect completed', STALE_WORKER: 'Worker lease expired',
  PENDING_WORK: 'Waiting for the worker', INTEGRITY_CONFLICT: 'Conflicting evidence',
  INSUFFICIENT_EVIDENCE: 'More evidence required',
};
export const STEPS = {COMPARE_LEDGER:'Compare event and ledger',CHECK_DEPENDENCY:'Check dependency availability',CORRECT_MAPPING:'Correct account mapping',WAIT_FOR_RETRY:'Wait for the scheduled retry',REQUEST_LEDGER:'Request an authoritative ledger snapshot',CONTACT_OPERATOR:'Ask an operator to review'};
export function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
export function filteredCases(cases, query, severity) {
  const text=query.toLowerCase().trim();
  return cases.filter(c=>(severity==='all'||c.severity===severity)&&(!text||`${c.title} ${c.summary} ${c.id}`.toLowerCase().includes(text)));
}
export function reportMarkdown(incident, job) {
  const r=job.result;
  if(!r) return `# ${incident.title}\n\nStatus: ${job.status}\n${job.error_code??''}: ${job.error_message??''}\n`;
  return `# ${incident.title}\n\nIncident revision: ${job.incident_revision}\nModel: ${r.usage.chatModel}\n\n${r.report.summary}\n\n`+
    r.report.findings.map(f=>`- ${f.text} [${f.evidenceIds.join(', ')}]`).join('\n')+
    `\n\n## Backend recovery policy\n${r.policy.action}: ${r.policy.explanation}\nReplay eligible: ${r.policy.replayEligible}\n\n`+
    `## Missing evidence\n${r.report.missingEvidence.join('\n')||'None reported.'}\n\n`+
    `## Evidence\n${r.evidence.map(e=>`### ${e.id}: ${e.label}\n${typeof e.value==='string'?e.value:JSON.stringify(e.value,null,2)}`).join('\n\n')}\n\n`+
    `Reference integrity checked; semantic entailment is not proven. No recovery action was executed.\n`;
}
