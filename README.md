# Incident Response SRE

A demo application showcasing the 3-layer evolution of AI-powered incident response using Quarkus, LangChain4j, and Flow - from a single stateless AI call, to a multi-agent pipeline, to a durable, human-gated, event-driven workflow.

## Quick start

> **Build Flow first (one-time).** This demo pins Quarkus Flow `1.0.0-SNAPSHOT`, which is **not** on Maven Central or any public snapshot repo yet - so it won't resolve until you build it locally:
> ```bash
> git clone https://github.com/quarkiverse/quarkus-flow && cd quarkus-flow && ./mvnw -DskipTests install
> ```
> (The released `0.9.0` is not a substitute - see [Tech Stack](#tech-stack).)

```bash
# Prereqs: Java 25, Docker, and Ollama with the model pulled (ollama pull qwen3.5:2b)
./mvnw quarkus:dev
```

Then open the **live console at http://localhost:8080/** and click *Trigger: hung service (needs approval)* to watch an incident run → pause for approval → resolve. Dev Services starts PostgreSQL, Kafka, and Ollama automatically.

## The 3-Layer Evolution

### Layer 1: ChatModel (Simple AI Service)

A single `@RegisterAiService` that takes alert text and returns structured analysis. Statelessness means no memory, no iteration, no durability.

**Endpoint:** `POST /incidents/alert`

```bash
curl -X POST http://localhost:8080/incidents/alert \
  -H "Content-Type: application/json" \
  -d '{
    "source": "prometheus",
    "service": "api-gateway",
    "metric": "error_rate",
    "value": 15.5,
    "threshold": 5.0,
    "message": "Error rate exceeded threshold: 15.5% > 5.0%"
  }'
```

### Layer 2: Agentic (Multi-Agent Pipeline)

A `@SequenceAgent` orchestrating SeverityClassifier then DiagnosticLoopAgent (a LoopAgent over DiagnosticAgent + RemediationAgent + ConfidenceScorer, `@ExitCondition` score >= 0.8). Agents iterate and collaborate, but there's no durability, no human-in-the-loop, no external integrations.

> Note: the nested loop is the **last** sub-agent of the sequence. With the Flow-backed planner, a composite (loop) must be the final sub-agent - a plain agent placed *after* a nested composite deadlocks. The post-incident summary is therefore produced in Layer 3 (as its own Flow `agent()` task), not appended after the loop here.

**Endpoint:** `POST /incidents/analyze-agentic`

```bash
curl -X POST http://localhost:8080/incidents/analyze-agentic \
  -H "Content-Type: application/json" \
  -d '{
    "source": "prometheus",
    "service": "api-gateway",
    "metric": "error_rate",
    "value": 15.5,
    "threshold": 5.0,
    "message": "Error rate exceeded threshold"
  }'
```

### Layer 3: Flow (Durable Workflow with HITL)

A `Flow` workflow adding what Agentic alone cannot provide:

- **Durability**: JPA-backed persistence (PostgreSQL). Workflow state survives restarts
- **Human-in-the-Loop**: Destructive remediations (RESTART_POD, SCALE_DOWN) require SRE approval; the workflow emits an approval-request CloudEvent, pauses at `listen`, and resumes when the approval/rejection event arrives
- **External integrations**: HTTP tasks fetch Prometheus metrics, execute remediation via Deployment API, notify Slack
- **Event-driven (in *and* out)**: consumes alerts/approvals from `flow-in`, and **publishes** its own domain events (`flow-out`) and per-task lifecycle (`flow-lifecycle-out`) as CloudEvents over Kafka. See [Eventing](#eventing-cloudevents-over-kafka)
- **Crash recovery**: paused workflow state is persisted in PostgreSQL, so a restarted instance can resume from where it left off

**Endpoints:** `POST /incidents/workflow`, `PUT /incidents/{id}/approve`, `PUT /incidents/{id}/reject`, `GET /incidents/{id}`.

Whether the workflow takes the HITL path depends on the triage agent classifying the remediation as `destructive` (e.g. `RESTART_POD`). The alert below describes an unrecoverable, hung service, which the model reliably triages as a destructive restart:

```bash
# 1) Start the workflow and capture the incident id
INCID=$(curl -s -X POST http://localhost:8080/incidents/workflow \
  -H "Content-Type: application/json" \
  -d '{
    "source": "prometheus",
    "service": "payment-service",
    "metric": "thread_deadlock",
    "value": 100, "threshold": 1,
    "message": "payment-service fully deadlocked: all worker threads BLOCKED, 100% of requests failing, liveness probe failing. Process is hung and unrecoverable; standard remediation is an immediate pod restart."
  }' | jq -r .incidentId)
echo "incident: $INCID"

# 2) Triage runs (~1-2 min on a small local model), then the workflow PAUSES at the
#    approval gate. You can watch this happen in the app log (see "Observing the demo").

# 3a) Approve -> workflow resumes -> executeRemediation -> notifySlack -> postMortem -> resolved
curl -X PUT "http://localhost:8080/incidents/$INCID/approve" \
  -H "Content-Type: application/json" \
  -d '{"reviewer": "sre-oncall", "reason": "Confirmed deadlock; restart approved"}'

# 3b) ...or Reject -> rejection notice -> END (no remediation executed)
curl -X PUT "http://localhost:8080/incidents/$INCID/reject" \
  -H "Content-Type: application/json" \
  -d '{"reviewer": "sre-oncall", "reason": "Prefer manual investigation"}'

# 4) Inspect the persisted incident
curl -s "http://localhost:8080/incidents/$INCID" | jq
```

A non-destructive alert (e.g. the `error_rate` example used above) skips the approval gate and runs straight through `executeRemediation -> notifySlack -> postMortem -> resolved`.

## Eventing (CloudEvents over Kafka)

The app both **consumes from** and **publishes to** Kafka topics (Redpanda via Dev Services). Everything is CloudEvents:

| Topic | App direction | By | Carries |
|-------|---------------|----|---------|
| `flow-in` | **consume** | Flow engine | incoming alerts and approval/rejection replies that start or resume workflows |
| `flow-in` | **publish** | `IncidentResource` (`flow-in-outgoing`) | the approval/rejection CloudEvent sent when an SRE clicks approve/reject |
| `flow-out` | **publish** | Flow `FlowDomainEventsPublisher` | domain events the workflow `emit`s (`incident.approval.required`, `incident.resolved`) |
| `flow-out` | **consume** | `IncidentEventBridge` | forwarded to the dashboard WebSocket |
| `flow-lifecycle-out` | **publish** | Flow `FlowLifecycleEventsPublisher` | per-task lifecycle (`...task.started/completed`, `...workflow.started/completed`) |
| `flow-lifecycle-out` | **consume** | `IncidentEventBridge` | drives the live per-step progress on each card |

So the workflow isn't just a consumer: it **emits** domain and lifecycle events that any downstream (the dashboard here, a ticketing/SLA system in production) can subscribe to. Publishing is enabled by `quarkus.flow.messaging.defaults-enabled=true` (domain) and `quarkus.flow.messaging.lifecycle-enabled=true` (lifecycle); the channel→topic bindings are in `application.properties`. Note Flow's domain publisher only binds to a channel named exactly **`flow-out`**.

## Observing the demo

### Live console (recommended) — `http://localhost:8080/`

A lightweight dashboard (static HTML + a `@ServerEndpoint` WebSocket fed by the Flow `flow-out` topic — the same pattern as the `quarkus-flow` samples, no Quinoa/npm). It lets you:

- **Trigger** a workflow (service-degradation or hung-service) with one click.
- See incidents update **live**: `TRIAGING → ⏸ AWAITING APPROVAL → RESOLVED`.
- **Approve / Reject** a destructive remediation right from the card when the workflow pauses at HITL (the buttons POST to the REST API, which publishes the approval CloudEvent to `flow-in`).

Implementation: `com.acme.sre.web.IncidentEventBridge` (consumes `flow-out` CloudEvents) + `IncidentDashboardSocket` (`/ws/incidents`) + `META-INF/resources/index.html`. Requires the `quarkus-websockets` extension and Flow's event publisher bound to a channel named exactly **`flow-out`**.

### Other surfaces

- **App log (raw trace).** Every task is traced; the HITL pause shows up clearly:
  ```
  Task 'triageAgent' completed ...
  Task 'switch-2' completed ...
  Task 'requestApproval' completed ...
  Task 'waitSREApproval' started ...        <-- workflow is now PAUSED here
  ```
  After you `PUT .../approve`, the same log shows it resume: `Task 'waitSREApproval' completed -> executeRemediation -> notifySlack -> postMortemAgent -> incidentResolved`.
- **Slack mock** (`POST /mock/slack/notify`, logged) - in production this is where the approval request and resolution notices land. This is the realistic HITL channel (a Slack interactive message), simulated here.
- **Swagger UI**: <http://localhost:8080/q/swagger-ui> - try the endpoints (note: shows request *schemas*, not pre-filled example payloads; use the curl snippets above).
- **Flow Dev UI**: <http://localhost:8080/q/dev-ui/quarkus-flow/workflows> - lists registered workflow **definitions**; the agentic **Topology** page visualizes the agent graph. (This engine version's Dev UI does not include a running-instance/HITL view - tail the log for that.)
- **`GET /incidents/{id}`** - the persisted incident record (severity, diagnosis, remediation, post-mortem).

## Architecture

```
Alert Input
     |
     +---> Layer 1: IncidentAnalyzer (@RegisterAiService)
     |         Single AI call, structured output
     |
     +---> Layer 2: IncidentResponseAgent (@SequenceAgent)
     |         SeverityClassifier -> DiagnosticLoopAgent (loop = last sub-agent)
     |         Iterative diagnosis until confidence >= 0.8
     |
     +---> Layer 3: IncidentResponseFlow (extends Flow)
               agent(triage) -> get(prometheus) -> switchWhenOrElse
                   destructive? -> emit(approval.required) -> listen(approval.done)
                       approved?  -> post(deploy) -> post(slack) -> agent(postMortem) -> emit(resolved)
                       rejected?  -> function(rejected) -> post(slack) -> END
                   not destructive -> post(deploy) -> post(slack) -> agent(postMortem) -> emit(resolved)
```

## Project structure

```
src/main/java/com/acme/sre/
├── ai/                          AI services & agents
│   ├── IncidentAnalyzer            Layer 1 — single @RegisterAiService
│   ├── IncidentResponseAgent       Layer 2 — @SequenceAgent (classifier → loop)
│   ├── SeverityClassifier, DiagnosticLoopAgent, DiagnosticAgent,
│   │   RemediationAgent, ConfidenceScorer, PostIncidentAgent   sub-agents
│   ├── WorkflowTriageAgent         Layer 3 — single triage agent (one Flow task)
│   └── WorkflowPostMortemAgent     Layer 3 — post-mortem agent (one Flow task)
├── flow/IncidentResponseFlow       Layer 3 — the durable Workflow descriptor
├── resource/IncidentResource       REST API (alert / analyze-agentic / workflow / approve / reject / get)
├── web/                          Live console backend
│   ├── IncidentEventBridge         @Incoming("flow-out-incoming") → broadcasts events
│   └── IncidentDashboardSocket     @ServerEndpoint("/ws/incidents")
├── domain/                       Records & JPA entities (Alert, Incident, IncidentResult, …)
└── mock/                         MockPrometheusResource, MockDeploymentResource, MockSlackResource
src/main/resources/
├── application.properties        LLM, datasource, Flow persistence, Kafka channels
└── META-INF/resources/index.html The live dashboard (static HTML + JS)
docker-compose.yml                Persistent Postgres for the crash-recovery demo
```

## Domain Model

| Concept | Type | Key Values |
|---------|------|------------|
| Severity | Enum | P1_CRITICAL, P2_HIGH, P3_MEDIUM, P4_LOW |
| ActionType | Enum | RESTART_POD, SCALE_DOWN (destructive), SCALE_UP, FEATURE_FLAG, NOTIFY, CREATE_TICKET, INVESTIGATE_ONLY |
| IncidentStatus | Enum | TRIAGED, DIAGNOSING, PENDING_APPROVAL, REMEDIATING, RESOLVED, ESCALATED |

## Tech Stack

- **Quarkus** 3.36.0
- **LangChain4j** 1.11.0.CR1 (Ollama + OpenAI providers)
- **Quarkus Flow** 1.0.0-SNAPSHOT (JPA persistence, Kafka messaging). **Must be built locally** - this version is not on Maven Central nor any public snapshot repo. Clone [quarkus-flow](https://github.com/quarkiverse/quarkus-flow) and `./mvnw install`. The latest Central *release* is `0.9.0`, but it is **not** usable here: 0.9.0's `flow-in` event handoff can't deserialize the approval CloudEvent (`Cannot construct io.cloudevents.CloudEvent`), which breaks the HITL resume. The demo therefore targets `1.0.0-SNAPSHOT` until a working release is published.
- **PostgreSQL** (via Dev Services)
- **Kafka/Redpanda** (via Dev Services)
- **Ollama** with `qwen3.5:2b` (via Dev Services)

## Setup

### Prerequisites

- **Quarkus Flow `1.0.0-SNAPSHOT` installed locally** (see [Quick start](#quick-start)) - the build fails to resolve dependencies without it.
- Java 25+
- Docker (for Dev Services: PostgreSQL, Kafka/Redpanda, Ollama)
- Ollama with `qwen3.5:2b` pulled: `ollama pull qwen3.5:2b`

### Running in Dev Mode

```shell
./mvnw quarkus:dev
```

Dev Services auto-starts PostgreSQL, Kafka (Redpanda), and Ollama containers. The Ollama container binds `~/.ollama` from the host so pre-pulled models are available.

### OpenAI (Alternative)

Uncomment the OpenAI config in `application.properties` and set `OPENAI_API_KEY`:

```properties
quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY}
quarkus.langchain4j.openai.chat-model.model-name=gpt-4o
```

## Testing

### Mock-based tests (default, no LLM required)

```shell
./mvnw test
```

### Real-LLM tests (requires Ollama with qwen3.5:0.8b)

```shell
./mvnw test -Preal-llm
```

Real-LLM tests use `qwen3.5:0.8b` for faster responses. They are excluded from default runs via the `real-llm` JUnit tag. These tests are slow (30s-5min per test depending on hardware) and may be flaky on resource-constrained machines. For reliable CI, use the default mock-based test suite.

## Mock Infrastructure

The Layer 3 workflow integrates with external systems through **HTTP tasks**. So the demo runs with **zero external dependencies**, those systems are stubbed by in-app REST endpoints (`com.acme.sre.mock`). Each Flow task points at one of them via `application.properties`:

| Flow task | Mock endpoint | Stands in for (production) |
|-----------|---------------|----------------------------|
| `fetchMetrics` (`get`) | `GET /mock/prometheus/query` | Prometheus / Datadog metrics API |
| `executeRemediation` (`post`) | `POST /mock/deployment/remediate` | Kubernetes API / deploy pipeline |
| `notifySlack` (`post`) | `POST /mock/slack/notify` | Slack / PagerDuty notification |

They're plain Quarkus REST resources that return canned responses (and log what they received), letting you exercise the full workflow - including the HTTP tasks - without real infrastructure. In production you would point `monitoring-api.prometheus.url`, `deployment-api.url`, and `slack-webhook.url` at the real services and delete the `mock` package (it ships in `src/main` only for the demo).

## Demo: Crash Recovery (verified)

The durability payoff: a workflow paused at HITL is persisted in PostgreSQL and survives a hard `kill -9` of the JVM. **Verified end-to-end** — the same instance that paused before the crash resumed and completed after restart.

Two requirements (the default Dev Services setup does **not** satisfy them): the database must **outlive** the JVM (Dev Services Postgres is ephemeral) and Hibernate must **not** drop the schema on restart (`%dev` uses `drop-and-create`). So run against the persistent Postgres in `docker-compose.yml` with `generation=update`:

```bash
# 1) Start a persistent Postgres (named volume; survives app restarts)
docker compose up -d

# 2) Run the app against it (datasource Dev Services off; keep Kafka/Ollama Dev Services)
DS="-Dquarkus.datasource.devservices.enabled=false \
    -Dquarkus.datasource.username=sre -Dquarkus.datasource.password=sre \
    -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5599/incidents \
    -Dquarkus.hibernate-orm.database.generation=update"
./mvnw quarkus:dev $DS

# 3) Start a destructive workflow; wait until the log shows: Task 'waitSREApproval' started  (PAUSED)
# 4) Hard-kill the JVM:  kill -9 <pid>     (state remains in Postgres: processinstanceentity + taskinfoentity rows)
# 5) Restart:  ./mvnw quarkus:dev $DS
# 6) Approve:  curl -X PUT http://localhost:8080/incidents/$INCID/approve -H 'Content-Type: application/json' \
#                   -d '{"reviewer":"oncall","reason":"resume after restart"}'
# 7) Flow rehydrates the suspended instance from Postgres on the correlated event and completes it.
```

> Note: resume is **event-driven**, not eager-on-boot — Flow rehydrates the suspended instance when its correlated `flow-in` event arrives after restart (there is no `auto-restore` config key; that property is ignored). This is exactly the right behavior for a workflow parked on a human decision.

## Related Guides

- Quarkus LangChain4j: <https://docs.quarkiverse.io/quarkus-langchain4j/dev/>
- LangChain4j Agentic: <https://docs.langchain4j.dev/tutorials/agents/>
- Quarkus Flow: <https://docs.quarkiverse.io/quarkus-flow/dev/>
- Hibernate ORM with Panache: <https://quarkus.io/guides/hibernate-orm-panache>
- SmallRye Kafka: <https://quarkus.io/guides/kafka>
