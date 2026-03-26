package com.scangovernance.temporal;

import com.scangovernance.model.WorkflowStatus;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.HistoryEventFilterType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin wrapper around the Temporal gRPC API used exclusively for querying
 * (no worker/activity definitions here).
 */
@ApplicationScoped
public class TemporalQueryService {

    private static final Logger LOG = Logger.getLogger(TemporalQueryService.class);

    private final WorkflowServiceStubs serviceStubs;
    private final String namespace;

    @Inject
    public TemporalQueryService(WorkflowServiceStubs serviceStubs,
                                @ConfigProperty(name = "scan.governance.temporal.namespace",
                                                defaultValue = "default") String namespace) {
        this.serviceStubs = serviceStubs;
        this.namespace = namespace;
    }

    /**
     * Returns all known executions (open + closed) for the given workflow_id.
     * Uses Temporal Visibility API with a query string.
     */
    public List<TemporalExecutionInfo> listExecutions(String workflowId) {
        List<TemporalExecutionInfo> results = new ArrayList<>();
        String nextPageToken = "";

        try {
            do {
                ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(namespace)
                        // Temporal Visibility query – matches by workflow ID across all statuses
                        .setQuery("WorkflowId = '" + escapeQuery(workflowId) + "'")
                        .setPageSize(100)
                        .setNextPageToken(com.google.protobuf.ByteString.copyFromUtf8(nextPageToken))
                        .build();

                ListWorkflowExecutionsResponse response =
                        serviceStubs.blockingStub().listWorkflowExecutions(request);

                for (WorkflowExecutionInfo info : response.getExecutionsList()) {
                    results.add(toExecutionInfo(info));
                }

                nextPageToken = response.getNextPageToken().toStringUtf8();
            } while (!nextPageToken.isEmpty());

        } catch (Exception e) {
            LOG.warnf("Failed to list Temporal executions for workflow_id=%s: %s", workflowId, e.getMessage());
        }

        return results;
    }

    /**
     * Describes a single execution by (workflowId, runId).
     * Returns empty if the execution cannot be found.
     */
    public Optional<TemporalExecutionInfo> describeExecution(String workflowId, String runId) {
        try {
            DescribeWorkflowExecutionRequest request = DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(namespace)
                    .setExecution(WorkflowExecution.newBuilder()
                            .setWorkflowId(workflowId)
                            .setRunId(runId)
                            .build())
                    .build();

            DescribeWorkflowExecutionResponse response =
                    serviceStubs.blockingStub().describeWorkflowExecution(request);

            WorkflowExecutionInfo info = response.getWorkflowExecutionInfo();

            // For COMPLETED / FAILED executions fetch input & result from history
            WorkflowStatus status = WorkflowStatus.fromTemporal(info.getStatus());
            String inputJson  = fetchInput(workflowId, runId);
            String resultJson = status.isTerminal() ? fetchClosePayload(workflowId, runId, info.getStatus()) : null;

            return Optional.of(new TemporalExecutionInfo(
                    workflowId,
                    runId,
                    status,
                    toInstant(info.getStartTime()),
                    toInstant(info.getCloseTime()),
                    inputJson,
                    resultJson
            ));

        } catch (Exception e) {
            LOG.debugf("Could not describe execution workflow_id=%s run_id=%s: %s",
                    workflowId, runId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private TemporalExecutionInfo toExecutionInfo(WorkflowExecutionInfo info) {
        String wfId = info.getExecution().getWorkflowId();
        String rId  = info.getExecution().getRunId();
        WorkflowStatus status = WorkflowStatus.fromTemporal(info.getStatus());

        // Eagerly fetch input/result only for terminal or running workflows
        String inputJson  = null;
        String resultJson = null;
        try {
            inputJson = fetchInput(wfId, rId);
            if (status.isTerminal()) {
                resultJson = fetchClosePayload(wfId, rId, info.getStatus());
            }
        } catch (Exception e) {
            LOG.debugf("Could not fetch history for workflow_id=%s run_id=%s: %s", wfId, rId, e.getMessage());
        }

        return new TemporalExecutionInfo(
                wfId, rId, status,
                toInstant(info.getStartTime()),
                toInstant(info.getCloseTime()),
                inputJson, resultJson
        );
    }

    /**
     * Fetches the workflow input JSON from the WorkflowExecutionStarted history event.
     * Temporal's default DataConverter serialises payloads as UTF-8 JSON in the `data` field.
     */
    private String fetchInput(String workflowId, String runId) {
        try {
            GetWorkflowExecutionHistoryResponse histResponse = serviceStubs.blockingStub()
                    .getWorkflowExecutionHistory(GetWorkflowExecutionHistoryRequest.newBuilder()
                            .setNamespace(namespace)
                            .setExecution(WorkflowExecution.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setRunId(runId)
                                    .build())
                            .setMaximumPageSize(1)  // only the first event needed
                            .build());

            return histResponse.getHistory().getEventsList().stream()
                    .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
                    .findFirst()
                    .map(e -> payloadsToJson(
                            e.getWorkflowExecutionStartedEventAttributes().getInput()))
                    .orElse(null);

        } catch (Exception e) {
            LOG.debugf("fetchInput failed for workflow_id=%s run_id=%s: %s", workflowId, runId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the close-event payload (result or failure message) by requesting
     * only the close event from history.
     */
    private String fetchClosePayload(String workflowId, String runId,
                                     WorkflowExecutionStatus temporalStatus) {
        try {
            GetWorkflowExecutionHistoryResponse histResponse = serviceStubs.blockingStub()
                    .getWorkflowExecutionHistory(GetWorkflowExecutionHistoryRequest.newBuilder()
                            .setNamespace(namespace)
                            .setExecution(WorkflowExecution.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setRunId(runId)
                                    .build())
                            .setHistoryEventFilterType(
                                    HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT)
                            .build());

            return histResponse.getHistory().getEventsList().stream()
                    .findFirst()
                    .map(event -> {
                        if (event.hasWorkflowExecutionCompletedEventAttributes()) {
                            return payloadsToJson(
                                    event.getWorkflowExecutionCompletedEventAttributes().getResult());
                        } else if (event.hasWorkflowExecutionFailedEventAttributes()) {
                            var failure = event.getWorkflowExecutionFailedEventAttributes().getFailure();
                            return "{\"message\":\"" + escapeJson(failure.getMessage()) + "\"}";
                        } else if (event.hasWorkflowExecutionTimedOutEventAttributes()) {
                            return "{\"message\":\"workflow timed out\"}";
                        } else if (event.hasWorkflowExecutionCanceledEventAttributes()) {
                            return "{\"message\":\"workflow cancelled\"}";
                        } else if (event.hasWorkflowExecutionTerminatedEventAttributes()) {
                            return "{\"message\":\"" + escapeJson(
                                    event.getWorkflowExecutionTerminatedEventAttributes().getReason())
                                    + "\"}";
                        }
                        return null;
                    })
                    .orElse(null);

        } catch (Exception e) {
            LOG.debugf("fetchClosePayload failed for workflow_id=%s run_id=%s: %s",
                    workflowId, runId, e.getMessage());
            return null;
        }
    }

    /**
     * Converts a Temporal Payloads proto to a JSON string.
     * The default DataConverter stores JSON bytes in payload.data so we can
     * emit them verbatim.  Multi-payload inputs are wrapped in a JSON array.
     */
    private String payloadsToJson(io.temporal.api.common.v1.Payloads payloads) {
        if (payloads == null || payloads.getPayloadsCount() == 0) return null;
        if (payloads.getPayloadsCount() == 1) {
            return payloads.getPayloads(0).getData().toStringUtf8();
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < payloads.getPayloadsCount(); i++) {
            if (i > 0) sb.append(",");
            sb.append(payloads.getPayloads(i).getData().toStringUtf8());
        }
        sb.append("]");
        return sb.toString();
    }

    private Instant toInstant(com.google.protobuf.Timestamp ts) {
        if (ts == null || (ts.getSeconds() == 0 && ts.getNanos() == 0)) return null;
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    /** Escapes a string for use inside a Temporal Visibility query. */
    private String escapeQuery(String value) {
        return value.replace("'", "\\'");
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
