package com.acme.sre.flow;

import io.quarkiverse.flow.dsl.FlowWorkflowBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.acme.sre.ai.diagnostics.IncidentDiagnosisService;
import com.acme.sre.ai.response.WorkflowPostMortemAgent;
import com.acme.sre.domain.contract.ApprovalResponse;
import com.acme.sre.domain.contract.RemediationCommand;
import com.acme.sre.domain.contract.RemediationRejection;
import com.acme.sre.domain.contract.SlackAck;
import com.acme.sre.domain.contract.SlackMessage;
import com.acme.sre.domain.contract.TriagePrompt;
import com.acme.sre.domain.diagnosis.IncidentResult;
import com.acme.sre.domain.diagnosis.MetricsSnapshot;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.FlowDirectiveEnum;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.TaskContextData;
import io.serverlessworkflow.impl.WorkflowContextData;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static io.quarkiverse.flow.dsl.FlowDSL.*;
import static io.quarkiverse.flow.dsl.FlowDSL.function;


@ApplicationScoped
public class IncidentResponseFlow extends Flow {

    @Inject
    IncidentDiagnosisService diagnosisService;

    @Inject
    WorkflowPostMortemAgent postMortemAgent;

    @ConfigProperty(name = "monitoring-api.prometheus.url")
    String prometheusUrl;

    @ConfigProperty(name = "deployment-api.url")
    String deploymentApiUrl;

    @ConfigProperty(name = "slack-webhook.url")
    String slackWebhookUrl;

    @Override
    public Workflow descriptor() {
        return FlowWorkflowBuilder.workflow("incident-response")
                .document(doc -> doc
                        .namespace(getClass().getPackageName())
                        .title("Incident Response")
                        .summary("Durable, human-gated incident response: agentic diagnosis, "
                                + "live metrics enrichment, approval gate for destructive remediation, "
                                + "remediation execution, Slack notification, and an agentic post-mortem."))
                .tasks(
                        function("agenticDiagnosis", diagnosisService::diagnose, TriagePrompt.class)
                                .exportAsTaskOutput(),

                        get("fetchMetrics", prometheusUrl)
                                .outputAs((MetricsSnapshot live, WorkflowContextData wf, TaskContextData tf) ->
                                                diagnosed(wf).withLiveMetrics(live),
                                        MetricsSnapshot.class)
                                .exportAsTaskOutput(),

                        switchWhenOrElse(
                                (IncidentResult ir) -> ir.isRemediationDestructive(),
                                "requestApproval", "executeRemediation",
                                IncidentResult.class),

                        emitJson("requestApproval", "com.acme.sre.incident.approval.required", IncidentResult.class),

                        listen("waitSREApproval",
                                toOne(consumed("com.acme.sre.incident.approval.done")
                                        .extensionByInstanceId("flowinstanceid"))),

                        switchWhenOrElse(
                                (ApprovalResponse ar) -> ar.approved(),
                                "executeRemediation", "remediationRejected",
                                ApprovalResponse.class),

                        function("remediationRejected", RemediationRejection::from, ApprovalResponse.class)
                                .then("notifyRejectionSlack"),

                        post("notifyRejectionSlack",
                                new SlackMessage("#sre-alerts", "Remediation rejected by SRE"),
                                slackWebhookUrl)
                                .then(FlowDirectiveEnum.END),

                        post("executeRemediation",
                                new RemediationCommand("Executing approved remediation", null, null),
                                deploymentApiUrl),

                        post("notifySlack",
                                new SlackMessage("#sre-alerts", "Incident remediation executed successfully"),
                                slackWebhookUrl)
                                .outputAs((SlackAck ack, WorkflowContextData wf, TaskContextData tf) -> diagnosed(wf),
                                        SlackAck.class),

                        agent("postMortemAgent", postMortemAgent::generate, IncidentResult.class)
                                .outputAs((String summary, WorkflowContextData wf, TaskContextData tf) ->
                                                diagnosed(wf).withPostMortem(summary),
                                        String.class),

                        emitJson("incidentResolved", "com.acme.sre.incident.resolved", IncidentResult.class))
                .build();
    }

    private static IncidentResult diagnosed(WorkflowContextData wf) {
        return wf.context().as(IncidentResult.class).orElseThrow();
    }
}
