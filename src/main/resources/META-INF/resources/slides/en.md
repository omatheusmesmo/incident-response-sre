# Agentic AI, Durable Workflows
## Quarkus LangChain4j × Quarkus Flow

Durable, human-gated AI workflows

*Incident Response SRE demo*

Note: 3-layer evolution: a single AI call, then a multi-agent pipeline, then a durable workflow.

---

## Live demo

**Home: http://localhost:8080/** → open the **console**

- Click *Trigger: hung service (needs approval)* **now**, and let it run while we talk
- Triage takes ~1-2 min, so by Layer 3 it will be paused, waiting for our approval

Also: Swagger at `/q/swagger-ui` · Flow Dev UI at `/q/dev-ui/quarkus-flow/workflows`

Note: Start it at the very beginning so the slow local-LLM triage finishes in the background; you approve it live when you reach Layer 3.

---

## The problem

- Monitoring fires alerts; on-call SREs must triage, diagnose, and act
- AI can accelerate triage, but real incident response needs more:
  - wait for human approval before destructive actions
  - call real systems (metrics, deploys, Slack)
  - survive a restart during a live incident

---

## Two libraries

- **Quarkus LangChain4j**: brings LLMs and agents into Quarkus, declaratively
- **Quarkus Flow**: a durable, event-driven workflow engine (CNCF Serverless Workflow)

Together: agents that *think* plus a workflow that *remembers, waits, and integrates*.

---

## Quarkus LangChain4j

Declarative AI for Quarkus:

- `@RegisterAiService` turns an interface into an AI-backed bean
- `@SystemMessage` / `@UserMessage` prompt templates
- Structured output: return typed records, not raw text
- Pluggable providers (Ollama, OpenAI, and more)

---

## A simple AI service

```java
@RegisterAiService
public interface IncidentAnalyzer {
  @SystemMessage("You are an SRE assistant...")
  @UserMessage("Analyze this alert: {alert}")
  IncidentAnalysis analyze(String alert);
}
```

One LLM call, a structured result. Stateless: no memory, no iteration.

---

## LangChain4j Agentic

Compose specialized agents:

- `@Agent` on each role (classifier, diagnostician, ...)
- `@SequenceAgent` chains them in order
- `@LoopAgent` iterates until an `@ExitCondition`
- also `@ConditionalAgent`, `@ParallelAgent`, `@SupervisorAgent`

---

## Agentic pipeline

```java
@SequenceAgent(subAgents = {
  SeverityClassifier.class,
  DiagnosticLoopAgent.class   // diagnose -> score -> refine
})
ResultWithAgenticScope<IncidentResult> respond(...);
```

Agents collaborate and refine. Still missing: durability, a human gate, external calls.

---

## Quarkus Flow

A durable workflow engine for Quarkus, built on the CNCF **Serverless Workflow** spec.

- Java DSL: `agent()`, `get()`, `post()`, `switchWhen()`, `emit()`, `listen()`
- State persisted in PostgreSQL (JPA)
- Events in and out as CloudEvents over Kafka

---

## The super powers

- **Durability**: a workflow can pause for minutes or days; state survives restarts
- **Human-in-the-loop**: emit an approval request, pause, resume on the reply
- **Integrations**: HTTP tasks with retries (metrics, deploy, Slack)
- **Event-driven**: a CloudEvent starts it; a CloudEvent out notifies others
- **Crash recovery**: rehydrate the instance from the database

---

## Workflow as code

```java
workflow("incident-response").tasks(
  agent("triage", triageAgent::triage, ...),
  switchWhenOrElse(ir -> ir.isRemediationDestructive(),
                   "requestApproval", "executeRemediation", ...),
  emitJson("requestApproval", "...approval.required", ...),
  listen("waitSREApproval", toOne("...approval.done")),
  post("executeRemediation", ...),
  agent("postMortem", postMortemAgent::generate, ...)
);
```

---

## Combining the two

The pattern (straight from the Flow samples):

- LC4J agents run as **`agent()` tasks** inside a Flow workflow
- Flow wraps them with durability, HITL, events, and HTTP
- The browser talks REST; the backend emits CloudEvents into the workflow

LangChain4j is the brain. Flow is the nervous system.

---

## The project: 3 layers

| Layer | Tech | What it adds |
|---|---|---|
| 1 | LC4J ChatModel | one structured AI call |
| 2 | LC4J Agentic | multi-agent loop and scoring |
| 3 | Flow + LC4J | durability, HITL, events, recovery |

Same alert, three levels of capability.

---

## Layer 3 in action

1. Alert arrives, AI triages it
2. Destructive remediation? Workflow **pauses** for SRE approval
3. Approve: execute, notify, post-mortem, resolved
4. Reject: escalate, nothing executed
5. Crash mid-pause? Restart, then resume on approval

**The incident we triggered at the start is now paused here** - let's approve it live.

The console is fed by `flow-out` events over a WebSocket.

---

## The two triggers

**Service degradation** -> auto path
```json
{ "service": "api-gateway", "metric": "cpu_usage",
  "value": 95, "threshold": 80,
  "message": "restart loop, CPU 95%, memory 89%, errors..." }
```
Triage proposes a SAFE action -> runs straight through.

**Hung service** -> human-approval path
```json
{ "service": "payment-service", "metric": "thread_deadlock",
  "value": 100, "threshold": 1,
  "message": "all worker threads BLOCKED, restart needed..." }
```
Triage proposes RESTART_POD (destructive) -> pauses for approval.

---

## Incident lifecycle: agents + Flow

```text
[Flow]  alert in (REST or CloudEvent) starts the workflow
  |
[agent] triage: classify severity, diagnose in a loop (diagnose -> score -> refine)
  |
[Flow]  fetch metrics (HTTP task, with retries)
  |
[Flow]  switch: is the remediation destructive?
  |-- no  -> execute (HTTP) -> notify (HTTP)
  |-- yes -> emit "approval.required" -> PAUSE (durable listen)
  |            human approves -> execute -> notify
  |            human rejects  -> notify -> END
  |
[agent] post-mortem summary
  |
[Flow]  emit "resolved" (CloudEvent out)
```

**[agent]** = LangChain4j reasoning · **[Flow]** = durability, HTTP, events, the human gate

---

## External calls (HTTP tasks)

The workflow calls three external systems over HTTP. In the demo they hit in-app **mocks**, so it runs offline:

- `fetchMetrics` -> `GET /mock/prometheus/query`  (Prometheus / Datadog)
- `executeRemediation` -> `POST /mock/deployment/remediate`  (Kubernetes / deploy pipeline)
- `notifySlack` -> `POST /mock/slack/notify`  (**Slack / PagerDuty**)

The **Slack mock** just logs the message it receives. Point `slack-webhook.url` at a real Slack incoming webhook for production - the approval request and the "resolved" notice are exactly what the on-call channel would see.

---

## Events in and out (Kafka)

CloudEvents over Kafka are the backbone - the workflow both consumes **and publishes**:

- `flow-in` - **consume**: alerts + approval/rejection replies (start / resume)
- `flow-in` - **publish**: the SRE's approve/reject, from the REST API
- `flow-out` - **publish**: domain events (`approval.required`, `resolved`)
- `flow-lifecycle-out` - **publish**: per-task progress (drives the live console)

Downstream systems (the dashboard here; ticketing / SLA in production) just subscribe.

---

## From demo to production (1/2)

| In this demo | In production |
|---|---|
| curl / dashboard button | Prometheus / Datadog alert as a CloudEvent |
| triage on local Ollama | hosted model (OpenAI, Bedrock, vLLM) |
| mock deployment API | Kubernetes API / deploy pipeline |

---

## From demo to production (2/2)

| In this demo | In production |
|---|---|
| mock Slack webhook | Slack / PagerDuty interactive approval |
| dashboard WebSocket | existing incident tooling / Backstage |
| Dev Services Postgres | managed, durable PostgreSQL |

Same workflow shape; only the edges change.

---

## Lessons learned

- A nested composite (loop) must be the **last** sub-agent of a sequence
- Flow's event publisher binds only to a channel named exactly `flow-out`
- Thread state across tasks via the workflow `$context`
- Crash recovery needs a **persistent** database plus `generation=update`

---

## Takeaways

- LangChain4j makes AI **declarative** in Quarkus
- Flow makes AI workflows **durable, gated, and integrated**
- Together they shape production-grade agentic systems, not just demos

---

## Thanks

- Quarkus LangChain4j: [docs.quarkiverse.io/quarkus-langchain4j/dev](https://docs.quarkiverse.io/quarkus-langchain4j/dev/)
- Quarkus Flow: [docs.quarkiverse.io/quarkus-flow/dev](https://docs.quarkiverse.io/quarkus-flow/dev/)
- LangChain4j Agentic: [docs.langchain4j.dev/tutorials/agents](https://docs.langchain4j.dev/tutorials/agents/)

Questions?
