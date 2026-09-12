# Plan — `DevModeAgentMonitor` "No ongoing parent invocation found" with Flow‑compiled agents

Status: draft · Owner: @matheuscruz · Created: 2026-09-10

## 1. Summary

In `quarkus:dev`, running a `@ParallelAgent` (or any multi‑agent topology) that is
AOT‑compiled by `quarkus-flow-langchain4j` produces a burst of caught‑but‑logged
`ERROR`s from `dev.langchain4j.agentic.observability.ListenerNotifierUtil`:

```
beforeAgentInvocation listener for agent analyzeLogs failed:
  No ongoing parent invocation found for agent ID: gather
  java.lang.IllegalStateException: No ongoing parent invocation found for agent ID: gather
    at dev.langchain4j.agentic.observability.MonitoredExecution.beforeAgentInvocation(MonitoredExecution.java:30)
    at dev.langchain4j.agentic.observability.AgentMonitor.beforeAgentInvocation(AgentMonitor.java:109)
    at io.quarkiverse.langchain4j.agentic.runtime.devui.DevModeAgentMonitor.beforeAgentInvocation(DevModeAgentMonitor.java:44)
    at dev.langchain4j.agentic.observability.ListenerNotifierUtil.beforeAgentInvocation(ListenerNotifierUtil.java:20)
    at dev.langchain4j.agentic.internal.AgentInvoker.invoke(AgentInvoker.java:34)
    at dev.langchain4j.agentic.internal.PlannerBasedInvocationHandler$PlannerLoop.lambda$parallelExecution$1(PlannerBasedInvocationHandler.java:489)
```

and, on the return path:

```
afterAgentInvocation listener for agent analyzeLogs failed:
  Cannot invoke "...MonitoredExecution.afterAgentInvocation(...)" because "execution" is null
    at dev.langchain4j.agentic.observability.AgentMonitor.afterAgentInvocation(AgentMonitor.java:117)
```

**Functionally harmless** — `ListenerNotifierUtil` swallows both, the agents still
call the model and return. The cost is: (a) log spam that hides real errors during a
live demo, and (b) the Dev UI **Agents** execution view is incomplete/empty for
Flow‑compiled topologies.

## 2. Environment

| Component | Version |
|---|---|
| Quarkus | 3.39.2 |
| `io.quarkiverse.langchain4j:quarkus-langchain4j-*` | 1.13.1 |
| `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic-dev` | 1.13.1 (dev‑mode only) |
| `io.quarkiverse.flow:quarkus-flow-*` | 1.1.0 |
| `dev.langchain4j:langchain4j` | 1.19.0 |
| `dev.langchain4j:langchain4j-agentic` | 1.19.0-beta29 |
| JDK | 25 |

Reproducer: `EvidenceGatherer#gather` — a `@ParallelAgent` with sub‑agents
`LogsAnalysisAgent`, `MetricsAnalysisAgent`, `DeployHistoryAgent`, invoked via
`POST /incidents/parallel` while `mvn quarkus:dev` is running.

## 3. Root cause (verified by decompiling 1.19.0-beta29 + 1.13.1)

`DevModeAgentMonitor extends dev.langchain4j.agentic.observability.AgentMonitor` and
is registered as a **global `AgentListener`** in dev mode
(`AgenticDevUIProcessor.devModeAgentMonitor()` →
`AdditionalBeanBuildItem.unremovableOf(DevModeAgentMonitor.class)`;
`AgenticRecorder.enableDevModeMonitoring(...)` registers the root agents).

`AgentMonitor.beforeAgentInvocation(AgentRequest)`:

```java
Object memoryId = request.agenticScope().memoryId();
MonitoredExecution newExec = new MonitoredExecution(request);        // ctor: ongoingInvocations.put(request.agentId(), topLevel)
MonitoredExecution prev = ongoingExecutions.putIfAbsent(memoryId, newExec);
if (prev != null) {
    prev.beforeAgentInvocation(request);                             // MonitoredExecution.java:30
}
```

`MonitoredExecution.beforeAgentInvocation(AgentRequest request)`:

```java
AgentInvocation parent = ongoingInvocations.get(request.agent().parent().agentId());
if (parent == null) {
    throw new IllegalStateException(
        "No ongoing parent invocation found for agent ID: " + request.agent().parent().agentId());
}
```

`AgentMonitor.afterAgentInvocation(AgentResponse)` — **no null guard** (contrast with
`afterAgentToolExecution` / `onAgentInvocationError`, which do guard):

```java
MonitoredExecution execution = ongoingExecutions.get(memoryId);
execution.afterAgentInvocation(response);                            // AgentMonitor.java:117 → NPE if null
```

### Why the invariant breaks

`AgentMonitor` assumes a **strict, single‑threaded parent‑before‑child** invocation
order: the enclosing agent's `beforeAgentInvocation` always fires (registering it in
`ongoingInvocations`) before any sub‑agent's.

`quarkus-flow-langchain4j` 1.1.0 compiles the `@ParallelAgent` node (`gather`) into a
serverless‑workflow definition and plugs a custom
`dev.langchain4j.agentic.planner.Planner` (`FlowPlanner`) into langchain4j. The
**parent `gather` invocation is driven by Flow and never emits
`beforeAgentInvocation`** to the listener chain. The sub‑agents, however, *are* run
through langchain4j's `AgentExecutor` / `AgentInvoker`
(`PlannerBasedInvocationHandler$PlannerLoop.parallelExecution`, on virtual threads),
so their `beforeAgentInvocation` does fire — with a parent that was never registered.

### The race (explains "2 of 3 sub‑agents, varying which")

1. First sub‑agent to reach `AgentMonitor.beforeAgentInvocation` wins
   `putIfAbsent(memoryId, newExec)` → `prev == null` → the `if` block is skipped.
   The session's `MonitoredExecution` now has only *that sub‑agent's* id registered as
   the top‑level invocation (the tree is mis‑rooted).
2. The other two sub‑agents get `prev != null` →
   `prev.beforeAgentInvocation(request)` → `ongoingInvocations.get("gather")` →
   `null` → **`IllegalStateException`**.
3. When the "winner" finishes, `afterAgentInvocation` →
   `execution.done()` is `true` (single top‑level invocation) →
   `ongoingExecutions.remove(memoryId, execution)`. The two losers' subsequent
   `afterAgentInvocation` calls then do `ongoingExecutions.get(memoryId)` → `null` →
   **NPE at `AgentMonitor.java:117`**.

Both exceptions propagate only as far as `ListenerNotifierUtil`, which logs
`LOG.error("... listener for agent X failed", e)` and continues. Agent execution is
unaffected.

## 4. Options

| # | Change | Where | Effort | Fixes noise | Fixes Dev UI viz | Notes |
|---|---|---|---|---|---|---|
| A1 | Set `ListenerNotifierUtil` log category to `OFF` | this repo (`application.properties`) | trivial | ✅ | ❌ | Immediate, zero‑risk demo mitigation |
| A2 | Exclude `quarkus-langchain4j-agentic-dev` | this repo (`pom.xml`) | small | ✅ | n/a (removes it) | May not be excludable if injected as a dev‑mode dep; verify |
| B | Make `langchain4j-agentic` observability non‑fatal & planner‑tolerant | `langchain4j/langchain4j` | medium | ✅ | partial | Correct root fix for the fragility |
| C | Defensive `DevModeAgentMonitor` + opt‑out config flag | `quarkiverse/quarkus-langchain4j` | small–medium | ✅ | partial | Smallest fix in a repo we can PR quickly |
| D | `FlowPlanner` emits parent‑agent lifecycle events | `quarkiverse/quarkus-flow` | medium–large | ✅ | ✅ | The only option that makes the Dev UI viz actually work for Flow topologies |

## 5. Recommended path (phased)

### Phase 0 — Unblock the demo (this repo, today)

- [ ] Add to `src/main/resources/application.properties`:
      `quarkus.log.category."dev.langchain4j.agentic.observability.ListenerNotifierUtil".level=OFF`
- [ ] Try `pom.xml` exclusion of `io.quarkiverse.langchain4j:quarkus-langchain4j-agentic-dev`
      on `quarkus-langchain4j-agentic` (and `-agentic-deployment` if needed). If dev mode
      still pulls it, keep only the log‑level line and note why.
- [ ] Confirm: no `ERROR` lines on `POST /incidents/parallel`; `Evidence` payload unchanged.

### Phase 1 — File upstream issues

- [ ] `quarkiverse/quarkus-langchain4j` — primary issue (owns `DevModeAgentMonitor`).
      See `docs/devmode-agent-monitor-bug-issue.md`.
- [ ] Cross‑link from `quarkiverse/quarkus-flow` (custom `Planner` doesn't replay
      parent lifecycle events).
- [ ] Cross‑link from `langchain4j/langchain4j` (observability throws / missing null
      guard). Reference existing #4795 (managed context lost across threads).

### Phase 2 — Contribute `quarkiverse/quarkus-langchain4j` fix (option C)

Smallest change that stops the bleeding for every Flow user:

- [ ] `DevModeAgentMonitor.beforeAgentInvocation` / `afterAgentInvocation`: wrap the
      `super` call; on `IllegalStateException` / missing execution, downgrade to a
      single `debug` log and skip (do not rethrow).
- [ ] Add config: `quarkus.langchain4j.agentic.dev-ui.enabled` (default `true`) —
      when `false`, `AgenticDevUIProcessor` does not register the monitor bean or call
      `enableDevModeMonitoring`.
- [ ] Optional: when a `FlowAgenticWorkflowBuildItem` is present for a root agent,
      skip monitor registration for that root (or log a one‑time "Dev UI agent
      execution view is not available for Flow‑compiled topologies").
- [ ] Tests: dev‑mode test with a `@ParallelAgent` + custom planner asserting no
      `ERROR` is logged and the app responds normally.

### Phase 3 — Contribute the correct fixes (options B + D)

`langchain4j/langchain4j` (`langchain4j-agentic`):

- [ ] `MonitoredExecution.beforeAgentInvocation`: if the parent invocation is not
      found, walk `request.agent().parent()` up to the top and synthesize the missing
      `AgentInvocation` chain (or attach under `topLevelInvocations`), then `warn`
      once — never `throw`. Observability must not raise into the invocation path.
- [ ] `AgentMonitor.afterAgentInvocation`: null‑guard `execution` (mirror
      `afterAgentToolExecution`).
- [ ] `AgentMonitor.beforeAgentInvocation`: when the first call for a `memoryId` is a
      sub‑agent (`request.agent().parent() != null` and no `MonitoredExecution` yet),
      build the `MonitoredExecution` rooted at the top of the parent chain so nested
      registration is consistent.

`quarkiverse/quarkus-flow` (`quarkus-flow-langchain4j`):

- [ ] `FlowPlanner` / `RuntimeFlow*AgentService`: emit
      `beforeAgentInvocation` / `afterAgentInvocation` (and `onAgentInvocationError`)
      for the enclosing `@ParallelAgent` / `@SequentialAgent` / `@LoopAgent` /
      `@ConditionalAgent` node around workflow execution, so global listeners observe
      a well‑formed parent→child sequence.
- [ ] Propagate langchain4j's managed invocation context onto the virtual threads
      spawned in the parallel branch execution.
- [ ] Verify the Dev UI **Agents** execution view then renders Flow topologies.

## 6. Verification checklist

- [ ] `POST /incidents/parallel` in `quarkus:dev` → **zero** `ListenerNotifierUtil`
      `ERROR` lines across 10 runs.
- [ ] `Evidence` response body identical before/after (findings populated, not the
      `"No … findings"` fallbacks — tracked separately).
- [ ] Dev UI → **Agents** page: either renders the Flow topology execution, or shows
      a clear "not supported for Flow topologies" message (depending on phase).
- [ ] `mvn test` (non‑dev) unaffected — monitor never loads outside dev.
- [ ] Native / prod build unaffected.

## 7. Open questions

- Is `quarkus-langchain4j-agentic-dev` addable/removable via a normal Maven
  `<exclusion>`, or is it force‑added by a `DevModeDependencyBuildItem`? (Determines
  whether A2 is viable.)
- Does `quarkus-langchain4j` 1.14.x already change `DevModeAgentMonitor` /
  `AgentMonitor` behaviour? (1.14.0.CR2 is in the local `~/.m2`.) Check before
  writing the patch.
- Upstream appetite: is the "correct" fix owned by `quarkus-flow` (emit lifecycle
  events) or by `langchain4j-agentic` (tolerate custom planners)? Likely both.
