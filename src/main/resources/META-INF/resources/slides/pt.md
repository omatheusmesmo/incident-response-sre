# AI Agentic, Workflows Duráveis
## Quarkus LangChain4j × Quarkus Flow

Workflows de AI duráveis e com aprovação humana

*Demo: Incident Response SRE*

Note: evolução em 3 camadas: uma chamada de AI, depois um pipeline multi-agente, depois um workflow durável.

---

## Demo ao vivo

**Home: http://localhost:8080/** → abra o **console**

- Clique em *Trigger: hung service (needs approval)* **agora** e deixe rodar enquanto falamos
- A triagem leva ~1-2 min, então na Camada 3 ele estará pausado, esperando nossa aprovação

Também: Swagger em `/q/swagger-ui` · Flow Dev UI em `/q/dev-ui/quarkus-flow/workflows`

Note: Inicie no começo para a triagem (LLM local, lenta) terminar em background; você aprova ao vivo ao chegar na Camada 3.

---

## O problema

- Monitoramento dispara alertas; o SRE de plantão precisa triar, diagnosticar e agir
- AI acelera a triagem, mas resposta a incidentes real precisa de mais:
  - esperar aprovação humana antes de ações destrutivas
  - chamar sistemas reais (métricas, deploys, Slack)
  - sobreviver a um restart durante um incidente ativo

---

## Duas bibliotecas

- **Quarkus LangChain4j**: traz LLMs e agentes para o Quarkus, de forma declarativa
- **Quarkus Flow**: um workflow engine durável e orientado a eventos (CNCF Serverless Workflow)

Juntas: agentes que *pensam* mais um workflow que *lembra, espera e integra*.

---

## Quarkus LangChain4j

AI declarativa para Quarkus:

- `@RegisterAiService` transforma uma interface em um bean apoiado por AI
- templates de prompt com `@SystemMessage` / `@UserMessage`
- structured output: retorna records tipados, não texto cru
- múltiplos providers (Ollama, OpenAI e outros)

---

## Um AI service simples

```java
@RegisterAiService
public interface IncidentAnalyzer {
  @SystemMessage("You are an SRE assistant...")
  @UserMessage("Analyze this alert: {alert}")
  IncidentAnalysis analyze(String alert);
}
```

Uma chamada de LLM, resultado estruturado. Stateless: sem memória, sem iteração.

---

## LangChain4j Agentic

Compõe agentes especializados:

- `@Agent` em cada papel (classifier, diagnostician, ...)
- `@SequenceAgent` os encadeia em ordem
- `@LoopAgent` itera até uma `@ExitCondition`
- também `@ConditionalAgent`, `@ParallelAgent`, `@SupervisorAgent`

---

## Pipeline agentic

```java
@SequenceAgent(subAgents = {
  SeverityClassifier.class,
  DiagnosticLoopAgent.class   // diagnose -> score -> refine
})
ResultWithAgenticScope<IncidentResult> respond(...);
```

Os agentes colaboram e refinam. Ainda falta: durabilidade, gate humano, chamadas externas.

---

## Quarkus Flow

Um workflow engine durável para Quarkus, baseado na spec CNCF **Serverless Workflow**.

- DSL Java: `agent()`, `get()`, `post()`, `switchWhen()`, `emit()`, `listen()`
- estado persistido em PostgreSQL (JPA)
- eventos de entrada e saída como CloudEvents via Kafka

---

## Os super poderes

- **Durabilidade**: o workflow pode pausar por minutos ou dias; o estado sobrevive a restarts
- **Human-in-the-loop**: emite um pedido de aprovação, pausa, retoma na resposta
- **Integrações**: HTTP tasks com retries (métricas, deploy, Slack)
- **Orientado a eventos**: um CloudEvent inicia; um CloudEvent de saída notifica outros
- **Crash recovery**: reidrata a instância a partir do banco

---

## Workflow como código

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

## Combinando as duas

O padrão (direto das samples do Flow):

- agentes LC4J rodam como **tasks `agent()`** dentro de um workflow Flow
- o Flow os envolve com durabilidade, HITL, eventos e HTTP
- o browser fala REST; o backend emite CloudEvents para dentro do workflow

LangChain4j é o cérebro. Flow é o sistema nervoso.

---

## O projeto: 3 camadas

| Camada | Tech | O que adiciona |
|---|---|---|
| 1 | LC4J ChatModel | uma chamada de AI estruturada |
| 2 | LC4J Agentic | loop multi-agente e scoring |
| 3 | Flow + LC4J | durabilidade, HITL, eventos, recovery |

Mesmo alerta, três níveis de capacidade.

---

## Camada 3 em ação

1. O alerta chega, a AI faz a triagem
2. Remediação destrutiva? O workflow **pausa** para aprovação do SRE
3. Aprovar: executa, notifica, post-mortem, resolvido
4. Rejeitar: escala, nada é executado
5. Crash durante a pausa? Restart e depois retoma na aprovação

**O incidente que disparamos no começo está pausado aqui** - vamos aprová-lo ao vivo.

O console é alimentado por eventos `flow-out` via WebSocket.

---

## Os dois triggers

**Service degradation** -> caminho automático
```json
{ "service": "api-gateway", "metric": "cpu_usage",
  "value": 95, "threshold": 80,
  "message": "restart loop, CPU 95%, memory 89%, erros..." }
```
A triagem propõe uma ação SAFE -> executa direto, sem pausa.

**Hung service** -> caminho com aprovação humana
```json
{ "service": "payment-service", "metric": "thread_deadlock",
  "value": 100, "threshold": 1,
  "message": "todas as worker threads BLOCKED, restart necessário..." }
```
A triagem propõe RESTART_POD (destrutivo) -> pausa para aprovação.

---

## Ciclo de vida: agentes + Flow

```text
[Flow]  alerta entra (REST ou CloudEvent) e inicia o workflow
  |
[agent] triage: classifica severidade, diagnostica em loop (diagnose -> score -> refine)
  |
[Flow]  busca métricas (HTTP task, com retries)
  |
[Flow]  switch: a remediação é destrutiva?
  |-- não -> executa (HTTP) -> notifica (HTTP)
  |-- sim -> emite "approval.required" -> PAUSA (listen durável)
  |            humano aprova  -> executa -> notifica
  |            humano rejeita -> notifica -> END
  |
[agent] resumo de post-mortem
  |
[Flow]  emite "resolved" (CloudEvent de saída)
```

**[agent]** = raciocínio do LangChain4j · **[Flow]** = durabilidade, HTTP, eventos, o gate humano

---

## Chamadas externas (HTTP tasks)

O workflow chama três sistemas externos por HTTP. No demo eles batem em **mocks** in-app, então roda offline:

- `fetchMetrics` -> `GET /mock/prometheus/query`  (Prometheus / Datadog)
- `executeRemediation` -> `POST /mock/deployment/remediate`  (Kubernetes / pipeline de deploy)
- `notifySlack` -> `POST /mock/slack/notify`  (**Slack / PagerDuty**)

O **mock do Slack** apenas loga a mensagem recebida. Aponte `slack-webhook.url` para um incoming webhook real do Slack em produção - o pedido de aprovação e o aviso de "resolved" são exatamente o que o canal de plantão veria.

---

## Eventos de entrada e saída (Kafka)

CloudEvents sobre Kafka são a espinha dorsal - o workflow consome **e publica**:

- `flow-in` - **consome**: alertas + respostas de aprovação/rejeição (inicia / retoma)
- `flow-in` - **publica**: o approve/reject do SRE, via REST API
- `flow-out` - **publica**: eventos de domínio (`approval.required`, `resolved`)
- `flow-lifecycle-out` - **publica**: progresso por task (alimenta o console ao vivo)

Sistemas downstream (o dashboard aqui; ticketing / SLA em produção) só assinam.

---

## Do demo para produção (1/2)

| Neste demo | Em produção |
|---|---|
| curl / botão do dashboard | alerta do Prometheus / Datadog como CloudEvent |
| triagem no Ollama local | modelo hospedado (OpenAI, Bedrock, vLLM) |
| mock da deployment API | Kubernetes API / pipeline de deploy |

---

## Do demo para produção (2/2)

| Neste demo | Em produção |
|---|---|
| mock do Slack webhook | aprovação interativa no Slack / PagerDuty |
| WebSocket do dashboard | ferramenta de incidentes / Backstage |
| Postgres via Dev Services | PostgreSQL gerenciado e durável |

Mesmo formato de workflow; só as bordas mudam.

---

## Lições aprendidas

- Um composite aninhado (loop) precisa ser o **último** sub-agente de uma sequence
- O event publisher do Flow só vincula a um canal chamado exatamente `flow-out`
- Carregue o estado entre tasks pelo `$context` do workflow
- Crash recovery exige um banco **persistente** mais `generation=update`

---

## Conclusões

- LangChain4j torna a AI **declarativa** no Quarkus
- Flow torna os workflows de AI **duráveis, controlados e integrados**
- Juntas, viabilizam sistemas agentic de produção, não só demos

---

## Obrigado

- Quarkus LangChain4j: [docs.quarkiverse.io/quarkus-langchain4j/dev](https://docs.quarkiverse.io/quarkus-langchain4j/dev/)
- Quarkus Flow: [docs.quarkiverse.io/quarkus-flow/dev](https://docs.quarkiverse.io/quarkus-flow/dev/)
- LangChain4j Agentic: [docs.langchain4j.dev/tutorials/agents](https://docs.langchain4j.dev/tutorials/agents/)

Perguntas?
