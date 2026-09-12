# Plan — the *correct* fix for `DevModeAgentMonitor` "No ongoing parent invocation found"

Status: draft · Owner: @matheuscruz · Created: 2026-09-10
Companion docs: `devmode-agent-monitor-bug-plan.md` (phased/workarounds), `devmode-agent-monitor-bug-issue.md` (issue text)

---

## 1. Objective

Make `quarkus-flow-langchain4j`‑compiled multi‑agent topologies emit a **well‑formed
agent lifecycle event sequence** to langchain4j's `AgentListener` chain, so that:

- global listeners (`DevModeAgentMonitor`, user listeners, tracing) see
  `beforeAgentInvocation(composite)` → `beforeAgentInvocation(sub)…` →
  `afterAgentInvocation(sub)…` → `afterAgentInvocation(composite)`;
- no `IllegalStateException` / `NullPointerException` is thrown from observability;
- the Dev UI **Agents** execution view renders Flow topologies.

Two work items: **(A)** the real fix in `quarkus-flow-langchain4j`; **(B)** defensive
hardening in `langchain4j-agentic` so observability can never break execution.

---

## 2. Confirmed root cause

Decompiled: `langchain4j-agentic` 1.19.0-beta29, `quarkus-langchain4j-*` 1.13.1,
`quarkus-flow-langchain4j` 1.1.0.

### 2.1 How lifecycle events normally reach listeners

`dev.langchain4j.agentic.internal.PlannerBasedInvocationHandler.executeAgentMethod(...)`
fires the composite ("planner") agent's events **only when it is the root call**:

```java
if (isRootCall()) {
    agenticScope.rootCallStarted(registry);
    ListenerNotifierUtil.beforeAgentInvocation(agentListener, scope, /*this AgentInstance*/, inputs);
}
Planner planner = plannerSupplier.get();
planner.init(new InitPlanningContext(scope, this, subagents));
Object result = new PlannerLoop(this, planner, scope, registry).loop();   // sub-agents run here
...
if (isRootCall()) {
    ListenerNotifierUtil.afterAgentInvocation(agentListener, scope, this, inputs, output);
    agenticScope.rootCallEnded(registry, agentListener);
}
```

`this.agentListener` is populated from `AbstractServiceBuilder.agentListener`, set via
`AbstractServiceBuilder.listener(AgentListener)` at build time.

`AgentMonitor` (superclass of `DevModeAgentMonitor`) keys everything by
`agenticScope().memoryId()` in a `ConcurrentHashMap` (thread‑safe, not thread‑local):

```java
// AgentMonitor.beforeAgentInvocation
MonitoredExecution newExec = new MonitoredExecution(request);          // ctor: ongoingInvocations.put(request.agentId(), topLevel)
MonitoredExecution prev = ongoingExecutions.putIfAbsent(memoryId, newExec);
if (prev != null) prev.beforeAgentInvocation(request);                 // -> MonitoredExecution.beforeAgentInvocation

// MonitoredExecution.beforeAgentInvocation
AgentInvocation parent = ongoingInvocations.get(request.agent().parent().agentId());
if (parent == null) throw new IllegalStateException("No ongoing parent invocation found for agent ID: " + ...);
```

So the invariant is: **the composite's `beforeAgentInvocation` must have run (and be
on the same `agentListener` chain) before any sub‑agent's**, so that
`ongoingInvocations` contains the parent id.

### 2.2 Why it breaks with Flow

`quarkus-flow-langchain4j` builds the composite through
`FlowParallelAgentService extends ParallelAgentServiceImpl extends AbstractServiceBuilder`
and calls `build(() -> new FlowPlanner(PARALLEL, flow))`. The resulting proxy **is**
a `PlannerBasedInvocationHandler`, and `gather()` **is** the root call — so
`executeAgentMethod` *does* fire `beforeAgentInvocation(gather)`.

**But it fires it on `this.agentListener`, which for the Flow‑built composite does
not include the CDI‑registered global listeners.** `io.quarkiverse.flow.langchain4j.workflow.builder.FlowAgentsBuilderService`
(`newParallel` / `newSequential` / `newLoop` / `newConditional`) never calls
`.listener(...)` on the builder. The non‑Flow path
(`quarkus-langchain4j-agentic` `AgenticProcessor`) *does* wire every discovered
`AgentListener` / `AgentListenerSupplier` bean — including `DevModeAgentMonitor` — onto
the agents it builds.

Meanwhile the **leaf sub‑agents** (`LogsAnalysisAgent`, `MetricsAnalysisAgent`,
`DeployHistoryAgent`) are ordinary declarative `@Agent` interfaces built by the
standard quarkus‑langchain4j path, so **they do have `DevModeAgentMonitor`** on their
listener chain.

Net effect:

| Agent | Built by | Has `DevModeAgentMonitor`? |
|---|---|---|
| `gather` (`@ParallelAgent`) | `FlowParallelAgentService` | ❌ no |
| `analyzeLogs` / `analyzeMetrics` / `analyzeDeployHistory` (`@Agent`) | standard quarkus‑langchain4j | ✅ yes |

So `beforeAgentInvocation(gather)` never reaches the monitor →
`MonitoredExecution` for the session is never seeded with `gather`. Then the 3
sub‑agents (running in parallel on virtual threads via
`PlannerBasedInvocationHandler$PlannerLoop.parallelExecution`) each call
`AgentMonitor.beforeAgentInvocation`:

1. First one wins `putIfAbsent(memoryId, newExec)` → `prev == null` → it becomes a
   (mis‑rooted) top‑level execution registered only under its own id.
2. The other two get `prev != null` → `prev.beforeAgentInvocation(request)` →
   `ongoingInvocations.get("gather")` → `null` → **`IllegalStateException`**.
3. When the winner finishes, `afterAgentInvocation` → `execution.done()` true →
   `ongoingExecutions.remove(memoryId, execution)`; the losers' `afterAgentInvocation`
   then get `ongoingExecutions.get(memoryId) == null` → **NPE at `AgentMonitor:117`**
   (that method, unlike `afterAgentToolExecution`/`onAgentInvocationError`, has no
   null guard).

Both are caught by `ListenerNotifierUtil` (logs `error`, continues) → execution is
unaffected, only observability/Dev UI is broken and the log is noisy.

---

## 3. The fix

### 3.1 (A) Primary — `quarkus-flow-langchain4j`: propagate `AgentListener`s to Flow‑built composites

**Goal:** the Flow‑built composite proxy carries the exact same `AgentListener` chain
that the non‑Flow agentic path installs, so `beforeAgentInvocation(composite)` /
`afterAgentInvocation(composite)` / `agentError(composite)` reach every global
listener, keyed by the same `memoryId` the sub‑agents use.

**Where:** `io.quarkiverse.flow.langchain4j.workflow.builder.FlowAgentsBuilderService`
and/or `FlowParallelAgentService` / `FlowSequentialAgentService` /
`FlowLoopAgentService` / `FlowConditionalAgentService`.

**How:**

1. Obtain the aggregate `AgentListener` the standard quarkus‑langchain4j agentic
   build uses. Options, cheapest first:
   - Inject `Instance<AgentListener>` + `Instance<AgentListenerSupplier>` in
     `FlowAgentsBuilderService` (CDI), compose them with
     `dev.langchain4j.agentic.observability.ComposedAgentListener`, and call
     `builder.listener(composed)` before `build(...)`. This mirrors what
     `quarkus-langchain4j-agentic`'s `AgenticProcessor` does.
   - Or expose a small SPI from `quarkus-langchain4j-agentic` (e.g.
     `AgenticListeners.global()` / a `@Singleton GlobalAgentListeners` bean) that both
     the standard path and the Flow path consume, so the two never drift.
2. Ensure `AgentListener.inheritedBySubagents()` inheritance still works: the
   composite must register its listener *and* propagate to sub‑agents
   (`registerInheritedParentListener`). If sub‑agents are built separately and already
   have the monitor, dedupe so the monitor isn't invoked twice for a sub‑agent
   (`ComposedAgentListener` should already dedupe identical instances; verify — add an
   identity `Set` guard if not).
3. Confirm `isRootCall()` is `true` for a Flow composite entered directly (it is for
   `evidenceGatherer.gather(...)` from REST). For a Flow composite nested inside
   another agent, `isRootCall()` is `false` and the parent handles the events — verify
   that nesting path too.
4. `FlowPlanner` already starts the workflow inside `PlannerLoop.loop()` (which runs
   inside the composite's `executeAgentMethod`), so ordering (composite‑before →
   subs → composite‑after) is already guaranteed once the listener is on the chain.
   No thread‑affinity work needed — `AgentMonitor` maps are keyed by `memoryId`.
5. Also emit `onAgentInvocationError(composite, throwable)` when the workflow fails —
   `PlannerBasedInvocationHandler` already calls `ListenerNotifierUtil.agentError(...)`
   in its catch block when `isRootCall()`, so this comes for free once the listener is
   attached. Verify `FlowPlanner` propagates workflow failure out of `loop()` (it
   calls `signalTermination()` and logs "Workflow failed" — make sure the exception
   actually surfaces to `executeAgentMethod`'s catch, otherwise `agentError` never
   fires and `done()`/`afterAgentInvocation` runs with a null/partial result).

**Acceptance:** a user `AgentListener` bean sees `beforeAgentInvocation` /
`afterAgentInvocation` for the `@ParallelAgent` node itself, not just its sub‑agents.

### 3.2 (B) Companion — `langchain4j-agentic`: observability must never throw

Even with (A), `MonitoredExecution` / `AgentMonitor` are fragile for any custom
`Planner` or any listener registered mid‑flight. Harden:

1. **`MonitoredExecution.beforeAgentInvocation`** — when
   `ongoingInvocations.get(parentId) == null`:
   - do **not** `throw`;
   - walk `request.agent().parent()` up the chain; synthesize the missing
     `AgentInvocation` nodes and attach under `topLevelInvocations` (or a synthetic
     root) so the tree is still usable;
   - log once at `DEBUG`/`WARN` ("observed sub‑agent `X` before its parent `Y`;
     attaching under root").
2. **`AgentMonitor.afterAgentInvocation`** — null‑guard `execution` before
   dereference (align with `afterAgentToolExecution` / `onAgentInvocationError`, which
   already guard).
3. **`AgentMonitor.beforeAgentInvocation`** — when the first call for a `memoryId` is a
   *sub‑agent* (`request.agent().parent() != null`), build the `MonitoredExecution`
   rooted at the top of the parent chain rather than at the sub‑agent, so nested
   registration is consistent regardless of arrival order.
4. Consider making `ListenerNotifierUtil` demote a listener that throws to a one‑time
   `WARN` + disable for the rest of the run, instead of `error`‑logging on every
   invocation (defense against noisy third‑party listeners). Optional.

### 3.3 (C) Optional — `quarkus-langchain4j-agentic-dev`: opt‑out + graceful degrade

Independent of (A)/(B), still worth doing (tracked in the issue doc):
`quarkus.langchain4j.agentic.dev-ui.enabled` flag; and if a topology is Flow‑compiled,
either skip monitor registration for that root or show "execution view unavailable for
Flow topologies" until (A) ships.

---

## 4. Implementation steps

### Step 0 — Reproduce & instrument (this repo)

- [ ] Add a CDI `@ApplicationScoped implements AgentListener` that logs
      `beforeAgentInvocation` / `afterAgentInvocation` with `agentId` + `memoryId`.
- [ ] Run `POST /incidents/parallel`. Confirm: sub‑agent events present, **`gather`
      events absent** → validates §2.2.
- [ ] Record baseline: exact ERROR count/run, Dev UI Agents page state.

### Step 1 — `quarkus-flow-langchain4j` PR

- [ ] Fork `quarkiverse/quarkus-flow`; branch `fix/agentic-listener-propagation`.
- [ ] In `FlowAgentsBuilderService`, inject the global `AgentListener`(s) (CDI
      `Instance<AgentListener>` + suppliers) and compose.
- [ ] In each `new*` builder method, call `.listener(composed)` before returning /
      before `build(...)`.
- [ ] If `quarkus-langchain4j-agentic` has no clean accessor, open a companion PR
      there exposing one (`@Singleton` bean or static SPI) and depend on the released
      version; until then, replicate the discovery (Jandex/CDI) it does.
- [ ] Dedup guard so a sub‑agent that already has the monitor isn't double‑notified.
- [ ] Unit/integration test (see §5).
- [ ] Changelog + docs note in `docs.quarkiverse.io/quarkus-flow`.

### Step 2 — `langchain4j-agentic` PR (companion hardening)

- [ ] Fork `langchain4j/langchain4j`; module `langchain4j-agentic`.
- [ ] `MonitoredExecution.beforeAgentInvocation`: replace `throw` with
      synthesize‑and‑warn.
- [ ] `AgentMonitor.afterAgentInvocation`: null guard.
- [ ] `AgentMonitor.beforeAgentInvocation`: root at parent chain top when first event
      is a sub‑agent.
- [ ] Tests: a `Planner` stub that invokes sub‑agents without the composite event;
      assert no throw, tree still coherent.
- [ ] Link to `langchain4j#4795` (same "context lost across threads/planners" family).

### Step 3 — Verify end‑to‑end in this repo

- [ ] Build both forks locally (`mvn install`), pin versions in `pom.xml`.
- [ ] Re‑run Step 0 instrumentation → `gather` events now present; **0** ERROR lines
      over 10 runs.
- [ ] Dev UI → Agents: parallel execution renders.
- [ ] `mvn test` and native build unaffected.
- [ ] Revert the `pom.xml` pins once upstream releases; keep only the
      `application.properties` workaround as a safety net until then.

---

## 5. Test plan

### `quarkus-flow-langchain4j` (integration test in `quarkus-flow-langchain4j-integration-tests`)

1. A `@ParallelAgent` with 3 stub sub‑agents (no real LLM — use a fixed
   `ChatModel` mock) + a recording `AgentListener` bean.
2. Invoke the composite from a `@QuarkusTest`.
3. Assert the listener received, in order:
   `before(composite)`, `before(sub)×3` (any order), `after(sub)×3`,
   `after(composite)` — all with the **same `memoryId`**.
4. Assert **no** `ERROR` logged by `dev.langchain4j.agentic.observability.*`
   (`LogCollectingTestResource` / `@TestHTTPResource` log capture or an
   `io.quarkus.test.junit.LogRecords` assertion).
5. Repeat for `@SequentialAgent`, `@LoopAgent`, `@ConditionalAgent`.
6. Nested case: `@SequentialAgent` containing a `@ParallelAgent` — assert the inner
   composite's events fire with `isRootCall()==false` handled by the outer.
7. Dev‑mode test (`@QuarkusDevModeTest`): assert `DevModeAgentMonitor.successfulExecutions()`
   contains a tree rooted at the composite after one invocation.

### `langchain4j-agentic` (unit)

1. `MonitoredExecution` — feed a sub‑agent `AgentRequest` whose parent was never
   registered; assert no exception, `topLevelInvocations` reachable, warn logged once.
2. `AgentMonitor.afterAgentInvocation` with no prior `beforeAgentInvocation` for the
   `memoryId`; assert no NPE.
3. Concurrency: 8 threads firing sub‑agent before/after for one `memoryId` with the
   parent missing; assert no exception, no lost/leaked `ongoingExecutions` entry.

---

## 6. Rollout / PR strategy

| Order | Repo | PR | Blocking? |
|---|---|---|---|
| 1 | `quarkiverse/quarkus-langchain4j` | expose global `AgentListener`s accessor (if needed) | only if Flow PR can't discover them itself |
| 2 | `quarkiverse/quarkus-flow` | `fix/agentic-listener-propagation` (the real fix) | ships the user‑visible fix |
| 3 | `langchain4j/langchain4j` | `langchain4j-agentic` observability hardening | independent; defense‑in‑depth |
| 4 | `quarkiverse/quarkus-langchain4j` | `dev-ui.enabled` flag + graceful degrade | independent; nice‑to‑have |

- File the tracking issue (see `devmode-agent-monitor-bug-issue.md`) first; link all
  PRs to it.
- 2 can merge and release without 1 if the Flow module does its own CDI discovery.
- 3 makes the whole thing robust for *any* custom `Planner`, not just Flow — worth
  landing regardless.

---

## 7. Risks & alternatives

| Risk | Mitigation |
|---|---|
| Double‑notification of sub‑agents (monitor on both composite chain and sub‑agent chain) | `ComposedAgentListener` identity dedupe; add an explicit `IdentityHashMap` guard; test asserts exactly‑once |
| Version skew between `quarkus-flow` and `quarkus-langchain4j` listener discovery | Prefer an explicit SPI/bean in `quarkus-langchain4j`; pin compatible ranges |
| `FlowPlanner` swallows workflow failure so `agentError` never fires | Part of Step 1: ensure workflow exceptions propagate out of `PlannerLoop.loop()` |
| `isRootCall()` semantics differ for scheduled / message‑triggered Flows (`@ScheduleOn`) | Add integration tests for those entry points |
| Upstream review latency | Keep the `application.properties` log‑off workaround in this repo meanwhile |

**Alternative considered:** make `FlowPlanner` call `ListenerNotifierUtil` directly
for the composite. Rejected — `FlowPlanner` doesn't hold the `agentListener` and
duplicating `PlannerBasedInvocationHandler`'s root‑call bookkeeping is fragile. The
builder already fires the events correctly; it just needs the listener attached.

---

## 8. Definition of done

- [ ] `quarkus-flow` PR merged + released; Flow composites carry global listeners.
- [ ] `langchain4j-agentic` PR merged: observability never throws.
- [ ] This repo on released versions, `application.properties` workaround removed.
- [ ] `POST /incidents/parallel` in `quarkus:dev`: 0 observability ERRORs over 20
      runs; Dev UI Agents view renders the parallel execution.
- [ ] Regression tests in both upstream repos.
- [ ] Issue closed with links to all merged PRs.
