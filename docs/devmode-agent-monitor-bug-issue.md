<!--
Draft GitHub issue. Primary target repo: quarkiverse/quarkus-flow
(FlowAgentsBuilderService does not attach global AgentListeners to Flow-built composites)
Suggested labels: bug, area/langchain4j, area/agentic
Cross-file / link:
  - langchain4j/langchain4j           (MonitoredExecution throws; AgentMonitor.afterAgentInvocation missing null-guard)
  - quarkiverse/quarkus-langchain4j   (optional: dev-ui.enabled flag; defensive DevModeAgentMonitor)
-->

# Flow‑compiled multi‑agent topologies don't propagate global `AgentListener`s to the composite → `DevModeAgentMonitor` throws `IllegalStateException` / NPE ("No ongoing parent invocation found for agent ID: …")

## Summary

When a `@ParallelAgent` (or any multi‑agent topology) is AOT‑compiled by
`quarkus-flow-langchain4j` and executed under `quarkus:dev`, the dev‑mode agent
monitor produces a burst of `ERROR` logs for every sub‑agent whose parent
invocation the monitor never observed:

- `IllegalStateException: No ongoing parent invocation found for agent ID: <parent>`
  from `MonitoredExecution.beforeAgentInvocation`
- `NullPointerException: ... "execution" is null` from
  `AgentMonitor.afterAgentInvocation`

Both are caught by `dev.langchain4j.agentic.observability.ListenerNotifierUtil`, so
**agent execution is not affected** — but the log noise buries real errors (painful
during live demos) and the Dev UI **Agents** execution view is incomplete/empty for
these topologies.

## Environment

| Component | Version |
|---|---|
| Quarkus | 3.39.2 |
| `io.quarkiverse.langchain4j:quarkus-langchain4j-core` / `-agentic` / `-openai` / `-ollama` | 1.13.1 |
| `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic-dev` | 1.13.1 |
| `io.quarkiverse.flow:quarkus-flow-langchain4j` / `-mvstore` / `-messaging` | 1.1.0 |
| `dev.langchain4j:langchain4j` | 1.19.0 |
| `dev.langchain4j:langchain4j-agentic` | 1.19.0-beta29 |
| JDK | 25 |
| OS | macOS 14 (Darwin 24.6) |

## Reproducer

A `@ParallelAgent` with three sub‑agents, all compiled to a Flow workflow by
`quarkus-flow-langchain4j`:

```java
public interface EvidenceGatherer {

    @ParallelAgent(
        description = "Fan-out evidence gathering",
        outputKey = "evidence",
        subAgents = {LogsAnalysisAgent.class, MetricsAnalysisAgent.class, DeployHistoryAgent.class})
    Evidence gather(@MemoryId String memoryId,
            @V("service") String service, @V("message") String message,
            @V("metric") String metric, @V("value") String value);

    @Output
    static Evidence aggregate(@V("logsFindings") String logs,
            @V("metricsFindings") String metrics, @V("deployFindings") String deploy) {
        return new Evidence(logs, metrics, deploy);
    }
}

// each sub-agent:
public interface MetricsAnalysisAgent {
    @Agent(description = "...", outputKey = "metricsFindings")
    @ModelName("evidence")
    @SystemMessage("...") @UserMessage("...")
    String analyzeMetrics(@V("service") String s, @V("message") String m,
            @V("metric") String metric, @V("value") String v);
}
```

1. `mvn clean quarkus:dev`
2. Invoke the parallel agent once (e.g. via a REST endpoint that calls
   `evidenceGatherer.gather(...)`).
3. Observe the logs.

## Expected

No `ERROR` logs from agent observability listeners. The Dev UI **Agents** page shows
the parallel execution (or degrades gracefully with a clear message).

## Actual

For each run, **2 of the 3** sub‑agents (which two varies — it's a race) log:

```
ERROR [dev.langchain4j.agentic.observability.ListenerNotifierUtil] ()
  beforeAgentInvocation listener for agent analyzeLogs failed:
  No ongoing parent invocation found for agent ID: gather:
  java.lang.IllegalStateException: No ongoing parent invocation found for agent ID: gather
    at dev.langchain4j.agentic.observability.MonitoredExecution.beforeAgentInvocation(MonitoredExecution.java:30)
    at dev.langchain4j.agentic.observability.AgentMonitor.beforeAgentInvocation(AgentMonitor.java:109)
    at io.quarkiverse.langchain4j.agentic.runtime.devui.DevModeAgentMonitor.beforeAgentInvocation(DevModeAgentMonitor.java:44)
    at dev.langchain4j.agentic.observability.ListenerNotifierUtil.beforeAgentInvocation(ListenerNotifierUtil.java:20)
    at dev.langchain4j.agentic.internal.AgentInvoker.invoke(AgentInvoker.java:34)
    at dev.langchain4j.agentic.internal.AbstractAgentInvoker.invoke(AbstractAgentInvoker.java:88)
    at dev.langchain4j.agentic.internal.AgentExecutor.agentResponse(AgentExecutor.java:130)
    at dev.langchain4j.agentic.internal.AgentExecutor.internalExecute(AgentExecutor.java:82)
    at dev.langchain4j.agentic.internal.AgentExecutor.execute(AgentExecutor.java:40)
    at dev.langchain4j.agentic.internal.PlannerBasedInvocationHandler$PlannerLoop.lambda$parallelExecution$1(PlannerBasedInvocationHandler.java:489)
    at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
    at java.base/java.util.concurrent.ThreadPerTaskExecutor$TaskRunner.run(ThreadPerTaskExecutor.java:291)
    at java.base/java.lang.VirtualThread.run(VirtualThread.java:456)
```

and on the return path:

```
ERROR [dev.langchain4j.agentic.observability.ListenerNotifierUtil] ()
  afterAgentInvocation listener for agent analyzeLogs failed:
  Cannot invoke "dev.langchain4j.agentic.observability.MonitoredExecution.afterAgentInvocation(...)" because "execution" is null:
  java.lang.NullPointerException
    at dev.langchain4j.agentic.observability.AgentMonitor.afterAgentInvocation(AgentMonitor.java:117)
    at dev.langchain4j.agentic.observability.ListenerNotifierUtil.afterAgentInvocation(ListenerNotifierUtil.java:37)
    at dev.langchain4j.agentic.internal.AgentInvoker.invoke(AgentInvoker.java:37)
    ...
    at dev.langchain4j.agentic.internal.PlannerBasedInvocationHandler$PlannerLoop.lambda$parallelExecution$1(PlannerBasedInvocationHandler.java:489)
```

## Analysis

`DevModeAgentMonitor extends dev.langchain4j.agentic.observability.AgentMonitor` and
is registered as a **global `AgentListener`** in dev mode
(`AgenticDevUIProcessor.devModeAgentMonitor()` →
`AdditionalBeanBuildItem.unremovableOf(DevModeAgentMonitor.class)`;
`AgenticRecorder.enableDevModeMonitoring(Set<String>)` registers the detected root
agents).

`AgentMonitor` assumes a **strict, single‑threaded, parent‑before‑child** invocation
order:

```java
// AgentMonitor.beforeAgentInvocation
Object memoryId = request.agenticScope().memoryId();
MonitoredExecution newExec = new MonitoredExecution(request);   // ctor: ongoingInvocations.put(request.agentId(), topLevel)
MonitoredExecution prev = ongoingExecutions.putIfAbsent(memoryId, newExec);
if (prev != null) {
    prev.beforeAgentInvocation(request);                        // -> MonitoredExecution.java:30
}
```

```java
// MonitoredExecution.beforeAgentInvocation
AgentInvocation parent = ongoingInvocations.get(request.agent().parent().agentId());
if (parent == null) {
    throw new IllegalStateException(
        "No ongoing parent invocation found for agent ID: " + request.agent().parent().agentId());
}
```

```java
// AgentMonitor.afterAgentInvocation  -- NO null guard (unlike afterAgentToolExecution / onAgentInvocationError)
MonitoredExecution execution = ongoingExecutions.get(memoryId);
execution.afterAgentInvocation(response);                       // AgentMonitor.java:117 -> NPE if null
```

**Why the invariant is violated:** `PlannerBasedInvocationHandler.executeAgentMethod`
*does* fire `beforeAgentInvocation(composite)` when `isRootCall()` is true — but on
`this.agentListener`, the chain assembled from `AbstractServiceBuilder.listener(...)`.
`quarkus-flow-langchain4j` builds the composite via
`FlowParallelAgentService extends ParallelAgentServiceImpl` /
`io.quarkiverse.flow.langchain4j.workflow.builder.FlowAgentsBuilderService`, which
**never calls `.listener(...)`** — so the Flow‑built composite's listener chain does
**not** include the CDI‑registered global listeners (`DevModeAgentMonitor` among
them). The non‑Flow agentic build (`quarkus-langchain4j-agentic`'s `AgenticProcessor`)
*does* wire every `AgentListener` / `AgentListenerSupplier` bean onto the agents it
builds.

The leaf sub‑agents (`analyzeLogs` etc.) are ordinary declarative `@Agent`
interfaces built by the standard path, so **they carry `DevModeAgentMonitor`**. Result:

| Agent | Built by | Has `DevModeAgentMonitor`? |
|---|---|---|
| `gather` (`@ParallelAgent`) | `FlowParallelAgentService` | ❌ |
| `analyzeLogs` / `analyzeMetrics` / `analyzeDeployHistory` | standard quarkus‑langchain4j | ✅ |

So `beforeAgentInvocation(gather)` never reaches the monitor →
`MonitoredExecution` is never seeded with `gather` → the sub‑agents (run on virtual
threads by `PlannerBasedInvocationHandler$PlannerLoop.parallelExecution`) can't find
their parent in `ongoingInvocations`.

**The race:**

1. First sub‑agent wins `putIfAbsent(memoryId, newExec)` → `prev == null` → the `if`
   block is skipped; the session's `MonitoredExecution` has only that sub‑agent
   registered as top‑level (mis‑rooted tree).
2. The other two get `prev != null` → `prev.beforeAgentInvocation(request)` →
   `ongoingInvocations.get("gather")` → `null` → **`IllegalStateException`**.
3. When the "winner" finishes, `afterAgentInvocation` → `execution.done()` is `true`
   → `ongoingExecutions.remove(memoryId, execution)`. The two losers'
   `afterAgentInvocation` then get `ongoingExecutions.get(memoryId) == null` →
   **NPE**.

## Impact

- Log spam (`ERROR` level) on every multi‑agent run in dev mode — hides genuine
  errors, especially during live demos.
- Dev UI **Agents** execution view is incomplete/empty for Flow‑compiled topologies.
- No functional impact: `ListenerNotifierUtil` catches both exceptions; agents
  execute and return normally.
- There is currently **no config flag** to disable dev‑mode agent monitoring.

## Proposed fix

### Primary — `quarkiverse/quarkus-flow` (`quarkus-flow-langchain4j`)

`FlowAgentsBuilderService` (`newParallel` / `newSequential` / `newLoop` /
`newConditional`) must attach the CDI‑registered global `AgentListener`s to the Flow
composite builder — `builder.listener(composed)` — the same way
`quarkus-langchain4j-agentic`'s `AgenticProcessor` does for non‑Flow agents. The
composite already fires `beforeAgentInvocation` / `afterAgentInvocation` /
`agentError` from `PlannerBasedInvocationHandler` on the root call; it just needs the
listener on its chain. (`AgentMonitor` maps are keyed by `memoryId`, not thread‑local,
so no thread‑affinity work is needed.) This also makes the Dev UI **Agents** execution
view work for Flow topologies, and lets user `AgentListener` beans observe the
composite node.

### Companion — `langchain4j/langchain4j` (`langchain4j-agentic`)

Observability must never throw into execution, for *any* custom `Planner`:

1. `MonitoredExecution.beforeAgentInvocation` — don't `throw` on a missing parent;
   synthesize the parent chain / attach under `topLevelInvocations` and `warn` once.
2. `AgentMonitor.afterAgentInvocation` — null‑guard `execution` (align with
   `afterAgentToolExecution` / `onAgentInvocationError`, which already guard).
3. `AgentMonitor.beforeAgentInvocation` — when the first event for a `memoryId` is a
   sub‑agent, root the `MonitoredExecution` at the top of the parent chain.

Related: langchain4j#4795 (managed invocation context lost across threads).

### Optional — `quarkiverse/quarkus-langchain4j` (`quarkus-langchain4j-agentic-dev`)

Independent hardening: add `quarkus.langchain4j.agentic.dev-ui.enabled` (default
`true`); make `DevModeAgentMonitor` catch `IllegalStateException` / missing
`MonitoredExecution` and log once at `DEBUG` instead of letting it reach
`ListenerNotifierUtil` as `ERROR`.

## Workaround

```properties
# application.properties — silence the (harmless, caught) listener errors
quarkus.log.category."dev.langchain4j.agentic.observability.ListenerNotifierUtil".level=OFF
```
