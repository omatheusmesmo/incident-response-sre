export const GROUPS = [
  { id: 'service', index: 1, title: 'AI Service' },
  { id: 'patterns', index: 2, title: 'Agentic patterns' },
  { id: 'agent', index: 2, title: 'Agent', note: 'the LLM decides the path' },
  { id: 'flow', index: 3, title: 'Quarkus Flow' },
];

const FLOW_SNIPPET = `workflow("incident-response").tasks(
    function("agenticDiagnosis", diagnosisService::diagnose, TriagePrompt.class),
    get("fetchMetrics", prometheusUrl),
    switchWhenOrElse(IncidentResult::isRemediationDestructive,
        "requestApproval", "executeRemediation"),
    emitJson("requestApproval", "com.acme.sre.incident.approval.required"),
    listen("waitSREApproval",
        toOne(consumed("com.acme.sre.incident.approval.done"))),
    post("executeRemediation", command, deploymentApiUrl),
    post("notifySlack", message, slackWebhookUrl),
    agent("postMortemAgent", postMortemAgent::generate),
    emitJson("incidentResolved", "com.acme.sre.incident.resolved"))`;

export const SCENARIOS = [
  {
    id: 'analyze',
    group: 'service',
    layer: 1,
    title: 'Analyze alert',
    kicker: 'Layer 1 · @RegisterAiService',
    headline: ['One interface, ', 'one structured answer'],
    lede: 'A single AI Service turns the raw alert into a typed IncidentAnalysis: severity, probable cause and the next action. Stateless, no orchestration.',
    snippet: `@RegisterAiService
public interface IncidentAnalyzer {
    @SystemMessage("You are an SRE AI assistant...")
    @UserMessage("Analyze this alert: {alert}")
    IncidentAnalysis analyze(@MemoryId String memoryId, @V("alert") String alert);
}`,
    endpoint: '/incidents/alert',
    renderer: 'analysis',
    payload: {
      source: 'prometheus', service: 'api-gateway', metric: 'error_rate', value: 15.5, threshold: 5.0,
      message: 'Error rate exceeded threshold: 15.5% > 5.0% on /api/v2/users',
    },
  },
  {
    id: 'parallel',
    group: 'patterns',
    layer: 2,
    title: 'Parallel',
    annotation: '@ParallelAgent',
    kicker: 'Layer 2 · @ParallelAgent',
    headline: ['Three agents, ', 'one evidence file'],
    lede: 'Logs, metrics and deploy history are investigated at the same time. Each agent calls its @Tool to read real telemetry instead of guessing, writes its own outputKey, and @Output combines the three into one Evidence record.',
    snippet: `@ParallelAgent(outputKey = "evidence",
    subAgents = { LogsAnalysisAgent.class, MetricsAnalysisAgent.class,
                  DeployHistoryAgent.class })
Evidence gather(@MemoryId String incidentId, @V("service") String service, ...);

@Agent(outputKey = "logsFindings")
@ToolBox(ObservabilityTools.class)
String analyzeLogs(@MemoryId String incidentId, @V("service") String service, ...);

@Tool("Recent log lines for a service, newest first")
public String searchLogs(String service) { ... }`,
    endpoint: '/incidents/parallel',
    renderer: 'evidence',
    payload: {
      source: 'prometheus', service: 'api-gateway', metric: 'error_rate', value: 15.5, threshold: 5.0,
      message: 'Error rate 15.5% on /api/v2/users; p99 latency climbing after the 14:00 rollout',
    },
  },
  {
    id: 'loop',
    group: 'patterns',
    layer: 2,
    title: 'Loop',
    annotation: '@LoopAgent',
    kicker: 'Layer 2 · @LoopAgent',
    headline: ['Refine until ', 'confident enough'],
    lede: 'Diagnose, propose a remediation, score the confidence, and repeat. The @ExitCondition stops the loop once the score reaches 0.8; maxIterations is the safety net.',
    snippet: `@LoopAgent(outputKey = "loopResult", maxIterations = 3,
    subAgents = { DiagnosticAgent.class, RemediationAgent.class, ConfidenceScorer.class })
ResultWithAgenticScope<String> diagnoseWithLoop(...);

@ExitCondition(testExitAtLoopEnd = true)
static boolean confidenceSufficient(@V("score") String score) {
    return Double.parseDouble(score) >= 0.8;
}`,
    endpoint: '/incidents/loop',
    renderer: 'loop',
    payload: {
      source: 'prometheus', service: 'payments-api', metric: 'heap_used_pct', value: 97, threshold: 85,
      message: 'OutOfMemoryError, heap climbing, GC pauses increasing after v2.3 deploy',
    },
  },
  {
    id: 'conditional',
    group: 'patterns',
    layer: 2,
    title: 'Conditional',
    annotation: '@ConditionalAgent',
    kicker: 'Layer 2 · @ConditionalAgent + @SequenceAgent',
    headline: ['The severity ', 'router'],
    lede: 'An @ActivationCondition reads the severity: P1/P2 activate the deep diagnosis, P3/P4 the light triage. Each branch is itself a @SequenceAgent, so patterns nest.',
    snippet: `@ConditionalAgent(outputKey = "routedResult",
    subAgents = { DeepDiagnosisAgent.class, LightTriageAgent.class })
ResultWithAgenticScope<String> route(@V("severity") Severity severity, ...);

@ActivationCondition(DeepDiagnosisAgent.class)
static boolean activateDeep(@V("severity") Severity severity) {
    return severity == P1_CRITICAL || severity == P2_HIGH;
}`,
    endpoint: '/incidents/conditional',
    renderer: 'conditional',
    payload: {
      source: 'prometheus', service: 'payment-service', metric: 'availability', value: 0, threshold: 1,
      message: 'payment-service down: 100% of requests failing, all pods CrashLoopBackOff after v2.3 deploy',
    },
  },
  {
    id: 'commander',
    group: 'agent',
    layer: 2,
    title: 'Supervisor',
    annotation: '@SupervisorAgent',
    kicker: 'Agent · @SupervisorAgent',
    headline: ['The LLM ', 'decides the path'],
    lede: 'No predefined route: an incident commander chooses which specialists to consult (database, Kubernetes, network, cache) and synthesizes their findings. This is the "Agents" side of workflows vs agents.',
    snippet: `@SupervisorAgent(responseStrategy = SupervisorResponseStrategy.SUMMARY,
    maxAgentsInvocations = 6,
    subAgents = { DatabaseSpecialist.class, KubernetesSpecialist.class,
                  NetworkSpecialist.class, CacheSpecialist.class })
String command(@V("incident") String incident);`,
    endpoint: '/incidents/commander',
    renderer: 'commander',
    payload: {
      source: 'prometheus', service: 'checkout-api', metric: 'db_pool_active', value: 100, threshold: 80,
      message: 'p99 8s, HikariCP connection pool exhausted, threads blocked acquiring connections',
    },
  },
  {
    id: 'degradation',
    group: 'flow',
    layer: 3,
    title: 'Service degradation',
    path: 'auto',
    kicker: 'Layer 3 · Quarkus Flow · safe path',
    headline: ['Safe fix, ', 'no one paged'],
    lede: 'Triage proposes a non-destructive action (scale up, notify), so the switch skips the approval gate and the workflow runs straight through: remediation over HTTP, Slack notification and an agentic post-mortem.',
    snippet: FLOW_SNIPPET,
    endpoint: '/incidents/workflow',
    renderer: 'workflow',
    payload: {
      source: 'prometheus', service: 'recommendations-api', metric: 'latency_p99', value: 780, threshold: 500,
      message: 'recommendations-api p99 latency 780ms over 500ms SLO during a traffic surge. CPU 74%, memory 61%, error rate 0.3%, no crashes or restarts, no recent deploy. Gradual capacity-bound slowdown.',
    },
  },
  {
    id: 'deadlock',
    group: 'flow',
    layer: 3,
    title: 'Hung service',
    path: 'approval',
    kicker: 'Layer 3 · Quarkus Flow · human in the loop',
    headline: ['Destructive fix, ', 'a human decides'],
    lede: 'Triage proposes RESTART_POD. The workflow emits an approval request and durably waits for the SRE; it survives restarts while it waits.',
    snippet: FLOW_SNIPPET,
    endpoint: '/incidents/workflow',
    renderer: 'workflow',
    payload: {
      source: 'prometheus', service: 'payment-service', metric: 'thread_deadlock', value: 100, threshold: 1,
      message: 'payment-service fully deadlocked: all worker threads BLOCKED, 100% of requests failing, liveness probe failing. Process is hung and unrecoverable; standard remediation is an immediate pod restart.',
    },
  },
];


/** Steps of the incident-response workflow, matched against Flow lifecycle task names. */
export const WORKFLOW_STEPS = [
  { id: 'triage', label: 'triage', kind: 'ai', match: ['agenticdiagnosis', 'triage'] },
  { id: 'metrics', label: 'metrics', kind: 'http', match: ['fetchmetrics'] },
  { id: 'decision', label: 'decision', kind: 'code', match: ['switch'] },
  { id: 'approval', label: 'approval', kind: 'human', match: ['requestapproval', 'waitsreapproval', 'approval'] },
  { id: 'remediate', label: 'remediate', kind: 'http', match: ['executeremediation'] },
  { id: 'notify', label: 'notify', kind: 'http', match: ['notifyslack'] },
  { id: 'postmortem', label: 'post-mortem', kind: 'ai', match: ['postmortem'] },
  { id: 'resolved', label: 'resolved', kind: 'event', match: ['incidentresolved'] },
];

export const STEP_KINDS = {
  ai: 'AI task',
  http: 'HTTP call',
  code: 'Java code',
  human: 'Human (event)',
  event: 'Event',
};
