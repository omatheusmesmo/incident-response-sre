import { GROUPS, SCENARIOS, WORKFLOW_STEPS, STEP_KINDS } from './scenarios.js';
import { RENDERERS, severityChip, remediationBlock, diagnosisBlock } from './renderers.js';
import { h, highlightJava, formatSeconds, humanize, richText } from './dom.js';

const DEV_UI_WORKFLOWS = '/q/dev-ui/quarkus-flow/workflows';
const EARLY_EVENT_TTL_MS = 60_000;

const rail = document.getElementById('rail-scenarios');
const stage = document.getElementById('stage');
const runs = document.getElementById('runs');
const connDot = document.getElementById('conn-dot');
const connText = document.getElementById('conn-text');

const workflows = new Map();
const earlyEvents = new Map();

function scenarioById(id) {
  return SCENARIOS.find(s => s.id === id) ?? SCENARIOS[0];
}

function renderRail(selectedId) {
  rail.replaceChildren(...GROUPS.map(group => {
    const items = SCENARIOS.filter(s => s.group === group.id);
    return h('section', { class: `rail-group rail-${group.id}` },
      h('div', { class: 'rail-title' },
        h('span', { class: 'rail-index' }, group.index),
        group.title,
        group.note ? h('span', { class: 'rail-note' }, group.note) : null),
      items.map(s => h('a', {
        class: `rail-item ${s.id === selectedId ? 'is-active' : ''}`,
        href: `#${s.id}`,
        'aria-current': s.id === selectedId ? 'page' : null,
        'data-scenario': s.id,
      },
      h('span', {}, s.title),
      s.annotation ? h('code', { class: 'rail-annotation' }, s.annotation) : null,
      s.path ? h('span', { class: `pill pill-${s.path}` }, s.path) : null,
      s.path === 'approval' ? h('span', { class: 'rail-alert', hidden: true, 'data-awaiting': '' }) : null)));
  }));
  updateAwaitingBadge();
}

function renderStage(scenario) {
  const [plain, highlighted] = scenario.headline;
  const button = h('button', { class: 'run-button', type: 'button', onclick: () => run(scenario, button) },
    scenario.layer === 3 ? 'Start workflow' : 'Run scenario', h('span', { 'aria-hidden': 'true' }, ' ▶'));

  stage.replaceChildren(
    h('div', { class: 'kicker' }, scenario.kicker),
    h('h1', { class: 'headline' }, plain, h('span', { class: 'grad-text' }, highlighted)),
    h('p', { class: 'lede' }, scenario.lede),
    h('div', { class: 'stage-grid' },
      h('pre', { class: 'snippet' }, highlightJava(scenario.snippet)),
      h('div', { class: 'stage-actions' },
        button,
        h('div', { class: 'endpoint' }, h('span', { class: 'verb' }, 'POST'), ' ', scenario.endpoint),
        h('details', { class: 'payload' },
          h('summary', {}, 'Alert payload'),
          h('pre', {}, JSON.stringify(scenario.payload, null, 2))))));
}

function select(id) {
  const scenario = scenarioById(id);
  renderRail(scenario.id);
  renderStage(scenario);
  document.title = `${scenario.title} · Incident Response console`;
}

const emptyState = document.getElementById('runs-empty');

function clearEmptyState() {
  emptyState.remove();
}

function runCard(scenario) {
  const status = h('span', { class: 'status status-running' }, h('span', { class: 'spinner' }), 'running');
  const timer = h('span', { class: 'timer' }, '0.0s');
  const severitySlot = h('span', { class: 'sev-slot' });
  const body = h('div', { class: 'run-body' });
  const raw = h('pre', {}, '(waiting for response)');
  const card = h('article', { class: `run run-l${scenario.layer}` },
    h('header', { class: 'run-head' },
      h('span', { class: 'layer' }, `L${scenario.layer}`),
      h('span', { class: 'run-service' }, scenario.payload.service),
      h('span', { class: 'run-scenario' }, scenario.annotation ?? scenario.title),
      severitySlot,
      h('span', { class: 'run-meta' }, status, timer)),
    body,
    h('details', { class: 'raw' }, h('summary', {}, 'Raw JSON'), raw));
  clearEmptyState();
  runs.prepend(card);

  const started = performance.now();
  const tick = setInterval(() => { timer.textContent = formatSeconds(performance.now() - started); }, 100);
  return {
    card, body, raw,
    setSeverity(severity) { severitySlot.replaceChildren(severityChip(severity) ?? ''); },
    setStatus(text, kind) {
      status.className = `status status-${kind}`;
      status.replaceChildren(kind === 'running' ? h('span', { class: 'spinner' }) : '', text);
    },
    stopTimer() { clearInterval(tick); timer.textContent = formatSeconds(performance.now() - started); },
  };
}

async function readError(response) {
  try {
    const json = await response.json();
    return json.error ?? JSON.stringify(json);
  } catch {
    return `${response.status} ${response.statusText}`;
  }
}

function showError(run, message) {
  run.body.replaceChildren(h('div', { class: 'error' }, h('strong', {}, 'Request failed. '), message));
  run.setStatus('error', 'error');
}

function run(scenario, button) {
  return scenario.layer === 3 ? startWorkflow(scenario, button) : runSync(scenario, button);
}

async function runSync(scenario, button) {
  button.disabled = true;
  const run = runCard(scenario);
  try {
    const response = await fetch(scenario.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(scenario.payload),
    });
    if (!response.ok) {
      showError(run, await readError(response));
      return;
    }
    const json = await response.json();
    run.raw.textContent = JSON.stringify(json, null, 2);
    const rendered = RENDERERS[scenario.renderer](json);
    run.setSeverity(rendered.severity);
    run.body.replaceChildren(rendered.body);
    run.setStatus('done', 'done');
  } catch (e) {
    showError(run, e.message);
  } finally {
    run.stopTimer();
    button.disabled = false;
  }
}

async function startWorkflow(scenario, button) {
  button.disabled = true;
  const run = runCard(scenario);
  run.setStatus('starting', 'running');
  try {
    const response = await fetch(scenario.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(scenario.payload),
    });
    if (!response.ok) {
      showError(run, await readError(response));
      run.stopTimer();
      return;
    }
    const json = await response.json();
    run.raw.textContent = JSON.stringify(json, null, 2);
    attachWorkflow(run, json);
  } catch (e) {
    showError(run, e.message);
    run.stopTimer();
  } finally {
    button.disabled = false;
  }
}

function attachWorkflow(run, started) {
  const steps = h('ol', { class: 'steps' });
  const decision = h('div', { class: 'decision', hidden: true });
  const outcome = h('div', { class: 'outcome', hidden: true });
  const events = h('pre', {}, '(waiting for events)');
  run.body.replaceChildren(
    h('div', { class: 'wf-ids' },
      h('span', {}, 'workflow ', h('code', {}, started.workflowInstanceId)),
      h('span', {}, 'incident ', h('code', {}, started.incidentId.slice(0, 8))),
      h('a', { href: DEV_UI_WORKFLOWS, target: '_blank', rel: 'noopener' }, 'Open in Dev UI ↗')),
    steps,
    h('div', { class: 'step-legend' }, Object.entries(STEP_KINDS).map(([kind, label]) =>
      h('span', { class: `legend legend-${kind}` }, label))),
    decision,
    outcome,
    h('details', { class: 'raw' }, h('summary', {}, 'Workflow events (live)'), events));
  run.setStatus('triaging', 'running');

  const ctx = {
    run, steps, decision, outcome, events,
    incidentId: started.incidentId,
    reached: new Set(),
    current: -1,
    finished: false,
  };
  workflows.set(started.workflowInstanceId, ctx);
  renderSteps(ctx);
  for (const ev of earlyEvents.get(started.workflowInstanceId) ?? []) handleEvent(ev);
  earlyEvents.delete(started.workflowInstanceId);
}

function renderSteps(ctx) {
  const approvalIndex = WORKFLOW_STEPS.findIndex(s => s.id === 'approval');
  ctx.steps.replaceChildren(...WORKFLOW_STEPS.map((step, i) => {
    const skipped = i === approvalIndex && !ctx.reached.has(i) && ctx.current > i;
    const state = skipped ? 'skipped'
      : i === ctx.current && !ctx.finished ? 'active'
      : ctx.reached.has(i) ? 'done' : 'todo';
    const kind = `${STEP_KINDS[step.kind]}${skipped ? ', skipped: safe remediation' : ''}`;
    return h('li', { class: `step step-${state} kind-${step.kind}`, title: kind, 'aria-label': `${step.label}: ${state} (${kind})` },
      h('span', { class: 'step-dot' }), h('span', { class: 'step-label' }, step.label));
  }));
}

function stepIndexFor(task) {
  if (!task) return -1;
  const name = task.toLowerCase();
  return WORKFLOW_STEPS.findIndex(s => s.match.some(m => name.includes(m)));
}

function advance(ctx, index) {
  if (index < 0) return;
  ctx.reached.add(index);
  ctx.current = Math.max(ctx.current, index);
  renderSteps(ctx);
}

function logEvent(ctx, ev) {
  const line = ev.kind === 'lifecycle'
    ? `· ${shortType(ev.type)}${ev.task ? `  task=${ev.task}` : ''}\n`
    : `▼ ${shortType(ev.type)}\n${JSON.stringify(ev.data, null, 2)}\n`;
  ctx.events.textContent = (ctx.events.textContent.startsWith('(') ? '' : ctx.events.textContent) + line;
}

function shortType(type) {
  return String(type ?? '').replace('com.acme.sre.', '').replace('io.serverlessworkflow.', '');
}

function handleEvent(ev) {
  const ctx = workflows.get(ev.flowInstanceId);
  if (!ctx) {
    bufferEarlyEvent(ev);
    return;
  }
  logEvent(ctx, ev);
  if (ev.kind === 'lifecycle') {
    onLifecycle(ctx, ev);
    return;
  }
  const data = ev.data ?? {};
  if (ev.type?.endsWith('approval.required')) onApprovalRequired(ctx, data);
  else if (ev.type?.endsWith('resolved')) onResolved(ctx, data);
}

function bufferEarlyEvent(ev) {
  if (!ev.flowInstanceId) return;
  const list = earlyEvents.get(ev.flowInstanceId) ?? [];
  list.push(ev);
  earlyEvents.set(ev.flowInstanceId, list);
  setTimeout(() => earlyEvents.delete(ev.flowInstanceId), EARLY_EVENT_TTL_MS);
}

function onLifecycle(ctx, ev) {
  const task = ev.task?.toLowerCase() ?? '';
  if (task.includes('rejected')) {
    finish(ctx, 'rejected', 'rejected');
    return;
  }
  const type = String(ev.type ?? '');
  if (type.includes('workflow.faulted')) {
    finish(ctx, 'faulted', 'error');
    return;
  }
  const index = stepIndexFor(ev.task);
  advance(ctx, index);
  const step = WORKFLOW_STEPS[index];
  if (step && !ctx.finished && step.id !== 'approval') ctx.run.setStatus(step.label, 'running');
}

function onApprovalRequired(ctx, result) {
  ctx.run.setSeverity(result.severity);
  ctx.run.setStatus('waiting for you', 'awaiting');
  ctx.run.card.classList.add('is-awaiting');
  advance(ctx, WORKFLOW_STEPS.findIndex(s => s.id === 'approval'));
  runs.prepend(ctx.run.card);

  const approve = h('button', { class: 'btn btn-primary', type: 'button', onclick: () => decide(ctx, 'approve') }, `Approve ${humanize(result.remediation?.type ?? 'remediation')}`);
  const reject = h('button', { class: 'btn', type: 'button', onclick: () => decide(ctx, 'reject') }, 'Reject');
  ctx.decision.hidden = false;
  ctx.decision.replaceChildren(...[
    h('div', { class: 'decision-title' }, 'The workflow is paused on ', h('code', {}, 'listen("waitSREApproval")'), '. It survives restarts while it waits.'),
    remediationBlock(result.remediation),
    diagnosisBlock(result.diagnosis),
    h('div', { class: 'decision-actions' }, approve, reject),
  ].filter(Boolean));
  updateAwaitingBadge();
}

async function decide(ctx, action) {
  const buttons = ctx.decision.querySelectorAll('button');
  buttons.forEach(b => { b.disabled = true; });
  ctx.run.setStatus(action === 'approve' ? 'approved, remediating' : 'rejecting', 'running');
  try {
    const response = await fetch(`/incidents/${encodeURIComponent(ctx.incidentId)}/${action}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        reviewer: 'console-user',
        reason: action === 'approve' ? 'Approved via console' : 'Rejected via console',
      }),
    });
    if (!response.ok) {
      ctx.run.setStatus(`${action} failed`, 'error');
      ctx.decision.append(h('div', { class: 'error' }, await readError(response)));
      buttons.forEach(b => { b.disabled = false; });
      return;
    }
    ctx.run.card.classList.remove('is-awaiting');
    ctx.decision.querySelector('.decision-title')?.replaceChildren(
      action === 'approve' ? 'Approval published on ' : 'Rejection published on ',
      h('code', {}, 'flow-in'), '. The workflow resumes from ', h('code', {}, 'listen("waitSREApproval")'), '.');
    ctx.decision.querySelector('.decision-actions')?.replaceChildren(
      h('span', { class: `tag ${action === 'approve' ? 'tag-safe' : 'tag-danger'}` }, action === 'approve' ? 'APPROVED' : 'REJECTED'),
      h('span', { class: 'muted' }, ' by console-user'));
    if (action === 'reject') finish(ctx, 'rejected', 'rejected');
  } catch (e) {
    ctx.run.setStatus(`${action} failed`, 'error');
    buttons.forEach(b => { b.disabled = false; });
  } finally {
    updateAwaitingBadge();
  }
}

function onResolved(ctx, result) {
  ctx.run.setSeverity(result.severity);
  ctx.reached.add(WORKFLOW_STEPS.length - 1);
  ctx.current = WORKFLOW_STEPS.length - 1;
  ctx.outcome.hidden = false;
  const metrics = result.liveMetrics;
  ctx.outcome.replaceChildren(...[
    ctx.decision.hidden ? remediationBlock(result.remediation) : null,
    metrics ? h('div', { class: 'metrics' },
      h('div', { class: 'field-label' }, 'Live metrics (fetchMetrics, HTTP GET)'),
      h('div', { class: 'metric-chips' },
        chip('service', metrics.service),
        chip('cpu', pct(metrics.cpuUsage)),
        chip('memory', pct(metrics.memoryUsage)),
        chip('error rate', pct(metrics.errorRate)),
        chip('p99', metrics.latencyP99 != null ? `${metrics.latencyP99}ms` : null))) : null,
    result.postMortemSummary ? h('div', { class: 'postmortem' },
      h('div', { class: 'field-label' }, 'Post-mortem (postMortemAgent)'),
      h('div', { class: 'postmortem-text' }, richText(result.postMortemSummary))) : null,
  ].filter(Boolean));
  finish(ctx, 'resolved', 'done');
}

function chip(label, value) {
  if (value == null) return null;
  return h('span', { class: 'metric-chip' }, h('span', { class: 'muted' }, label), ' ', value);
}

function pct(value) {
  return value == null ? null : `${value}%`;
}

function finish(ctx, text, kind) {
  ctx.finished = true;
  ctx.run.card.classList.remove('is-awaiting');
  ctx.run.setStatus(text, kind);
  ctx.run.stopTimer();
  renderSteps(ctx);
  updateAwaitingBadge();
}

function updateAwaitingBadge() {
  const count = document.querySelectorAll('.run.is-awaiting').length;
  document.querySelectorAll('[data-awaiting]').forEach(badge => {
    badge.hidden = count === 0;
    badge.textContent = count;
  });
}

function setConnection(state) {
  connDot.dataset.state = state;
  connText.textContent = state === 'on' ? 'live · /ws/incidents' : 'reconnecting…';
}

function connect(delay = 1000) {
  const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/incidents`);
  ws.onopen = () => { setConnection('on'); delay = 1000; };
  ws.onclose = () => {
    setConnection('off');
    setTimeout(() => connect(Math.min(delay * 2, 15_000)), delay);
  };
  ws.onmessage = e => {
    try {
      handleEvent(JSON.parse(e.data));
    } catch (err) {
      console.warn('Ignoring malformed workflow event', err);
    }
  };
}

document.getElementById('clear-runs').addEventListener('click', () => {
  runs.querySelectorAll('.run:not(.is-awaiting)').forEach(card => card.remove());
  if (!runs.querySelector('.run')) runs.append(emptyState);
});

window.addEventListener('hashchange', () => select(location.hash.slice(1)));
select(location.hash.slice(1));
connect();
