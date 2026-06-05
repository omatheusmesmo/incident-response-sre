package com.acme.sre.flow;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.acme.sre.ai.diagnostics.IncidentDiagnosisService;
import com.acme.sre.ai.response.WorkflowPostMortemAgent;
import com.acme.sre.domain.ApprovalResponse;
import com.acme.sre.domain.IncidentResult;
import com.acme.sre.domain.TriagePrompt;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.FlowDirectiveEnum;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.fluent.func.FuncWorkflowBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.agent;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.consumed;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.emitJson;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.function;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.get;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.listen;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.post;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.switchWhenOrElse;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.toOne;

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
        return FuncWorkflowBuilder.workflow("incident-response")
                .tasks(
                        function("agenticDiagnosis", diagnosisService::diagnose, TriagePrompt.class)
                                .exportAsTaskOutput(),

                        get("fetchMetrics", prometheusUrl)
                                .outputAs((result, wf, tf) -> wf.context().as(IncidentResult.class).orElseThrow(),
                                        Object.class),

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

                        function("remediationRejected", (ApprovalResponse ar) -> {
                            return Map.of("status", "REJECTED", "reviewer", ar.reviewer(), "reason", ar.reason());
                        }, ApprovalResponse.class)
                                .then("notifyRejectionSlack"),

                        post("notifyRejectionSlack",
                                Map.of("channel", "#sre-alerts", "message", "Remediation rejected by SRE"),
                                slackWebhookUrl)
                                .then(FlowDirectiveEnum.END),

                        post("executeRemediation",
                                Map.of("description", "Executing approved remediation"),
                                deploymentApiUrl),

                        post("notifySlack",
                                Map.of("channel", "#sre-alerts", "message", "Incident remediation executed successfully"),
                                slackWebhookUrl),

                        agent("postMortemAgent", postMortemAgent::generate, IncidentResult.class)
                                .inputFrom((result, wf, tf) -> wf.context().as(IncidentResult.class).orElseThrow(),
                                        Object.class),

                        emitJson("incidentResolved", "com.acme.sre.incident.resolved", IncidentResult.class)
                                .inputFrom((result, wf, tf) -> wf.context().as(IncidentResult.class).orElseThrow(),
                                        Object.class))
                .build();
    }
}
