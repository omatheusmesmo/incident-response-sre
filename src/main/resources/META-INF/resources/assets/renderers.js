import { h, humanize, richText } from './dom.js';

const EXIT_THRESHOLD = 0.8;

export function severityChip(severity) {
  if (!severity) return null;
  const level = String(severity).slice(0, 2);
  return h('span', { class: `sev sev-${level.toLowerCase()}`, title: humanize(severity) }, level);
}

export function remediationTag(remediation) {
  if (!remediation) return null;
  return remediation.destructive
    ? h('span', { class: 'tag tag-danger' }, 'DESTRUCTIVE')
    : h('span', { class: 'tag tag-safe' }, 'SAFE');
}

function field(label, value, extraClass = '') {
  if (value == null || value === '') return null;
  return h('div', { class: `field ${extraClass}` },
    h('div', { class: 'field-label' }, label),
    h('div', { class: 'field-value' }, typeof value === 'string' ? richText(value) : value));
}

function remediationBlock(remediation) {
  if (!remediation) return null;
  return h('div', { class: 'remediation' },
    h('div', { class: 'remediation-head' },
      h('strong', {}, humanize(remediation.type)),
      remediationTag(remediation),
      remediation.targetService ? h('span', { class: 'muted' }, `→ ${remediation.targetService}`) : null),
    remediation.description ? richText(remediation.description) : null);
}

function diagnosisBlock(diagnosis) {
  if (!diagnosis) return null;
  return h('div', { class: 'fields' },
    field('Root cause', diagnosis.rootCause, 'field-strong'),
    field('Explanation', diagnosis.explanation),
    field('Affected component', diagnosis.affectedComponent));
}

function confidenceMeter(confidence) {
  const score = Number(confidence?.score ?? confidence?.value);
  if (!Number.isFinite(score)) return null;
  const pct = Math.max(0, Math.min(1, score)) * 100;
  const passed = score >= EXIT_THRESHOLD;
  return h('div', { class: `meter ${passed ? 'meter-pass' : 'meter-fail'}` },
    h('div', { class: 'meter-head' },
      h('span', { class: 'field-label' }, 'Confidence (@ExitCondition)'),
      h('span', { class: 'meter-value' }, score.toFixed(2)),
      h('span', { class: 'muted' }, passed ? `≥ ${EXIT_THRESHOLD}, loop exits` : `< ${EXIT_THRESHOLD}, stopped by maxIterations`)),
    h('div', { class: 'meter-track' },
      h('div', { class: 'meter-fill', style: `width:${pct}%` }),
      h('div', { class: 'meter-threshold', style: `left:${EXIT_THRESHOLD * 100}%`, title: `threshold ${EXIT_THRESHOLD}` })),
    confidence?.reasoning ? h('p', { class: 'muted small' }, confidence.reasoning) : null);
}

function analysis(json) {
  const a = json.analysis ?? {};
  return {
    severity: a.severity,
    body: h('div', { class: 'fields' },
      field('Severity', humanize(a.severity)),
      field('Probable cause', a.probableCause, 'field-strong'),
      field('Suggested action', a.suggestedAction)),
  };
}

function evidence(json) {
  const e = json.evidence ?? {};
  const box = (key, title, text, tool) => h('div', { class: 'finding' },
    h('div', { class: 'finding-key' }, key, ' · ', h('span', { class: 'finding-tool' }, `@Tool ${tool}()`)),
    h('div', { class: 'finding-title' }, title),
    h('div', { class: 'finding-text' }, richText(text || 'No findings')));
  return {
    body: h('div', {},
      h('div', { class: 'findings' },
        box('logsFindings', 'Logs', e.logsFindings, 'searchLogs'),
        box('metricsFindings', 'Metrics', e.metricsFindings, 'queryMetrics'),
        box('deployFindings', 'Deploys', e.deployFindings, 'listDeployments')),
      h('div', { class: 'combiner' }, h('code', {}, '@Output aggregate(...)'), ' → ', h('strong', {}, 'Evidence'))),
  };
}

function branchLane(name, agents, active) {
  return h('div', { class: `lane ${active ? 'lane-active' : 'lane-idle'}` },
    h('div', { class: 'lane-head' },
      h('strong', {}, name),
      h('code', {}, '@SequenceAgent'),
      active ? h('span', { class: 'tag tag-info' }, 'activated') : h('span', { class: 'muted small' }, 'skipped')),
    h('div', { class: 'lane-steps' }, agents.map((agent, i) => [i ? h('span', { class: 'arrow' }, '→') : null, h('span', { class: 'lane-step' }, agent)])));
}

function conditional(json) {
  const r = json.result ?? {};
  const deep = json.branch === 'deep';
  return {
    severity: r.severity,
    body: h('div', {},
      h('div', { class: 'router' },
        h('div', { class: 'router-cond' },
          h('span', { class: 'field-label' }, '@ActivationCondition'),
          h('span', {}, 'severity = ', severityChip(r.severity) ?? '?', ' → ', h('strong', {}, deep ? 'deep' : 'light'))),
        h('div', { class: 'lanes' },
          branchLane('DeepDiagnosisAgent', ['Diagnostic', 'Remediation', 'ConfidenceScorer'], deep),
          branchLane('LightTriageAgent', ['LightDiagnosis', 'LightRemediation', 'ConfidenceScorer'], !deep))),
      diagnosisBlock(r.diagnosis),
      remediationBlock(r.remediation)),
  };
}

function loop(json) {
  const r = json.result ?? {};
  return {
    severity: r.severity,
    body: h('div', {},
      confidenceMeter(r.confidenceScore),
      diagnosisBlock(r.diagnosis),
      remediationBlock(r.remediation)),
  };
}

function commander(json) {
  return {
    body: h('div', { class: 'assessment' },
      h('div', { class: 'field-label' }, 'Commander assessment (SUMMARY)'),
      json.assessment ? richText(json.assessment) : h('p', { class: 'muted' }, 'Empty assessment')),
  };
}

export const RENDERERS = { analysis, evidence, conditional, loop, commander };

export { remediationBlock, diagnosisBlock };
