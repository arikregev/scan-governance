package com.scangovernance.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scangovernance.model.ocsf.ScanActivity;
import com.scangovernance.service.WorkflowGovernanceService;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Consumes OCSF Scan Activity events from Kafka.
 *
 * Messages are keyed by workflow_id in the producing service (Scan-Service),
 * which guarantees ordering per workflow_id within a partition.  This lets us
 * process catch-up messages sequentially and maintain FIFO run_id assignment.
 *
 * Processing is done on a worker thread (@Blocking) because it involves
 * database writes and Temporal gRPC calls.
 */
@ApplicationScoped
public class ScanEventConsumer {

    private static final Logger LOG = Logger.getLogger(ScanEventConsumer.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowGovernanceService governanceService;

    @Incoming("scan-events")
    @Blocking
    public void consume(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            LOG.warn("Received null/blank message – skipping");
            return;
        }

        ScanActivity activity;
        try {
            activity = objectMapper.readValue(rawMessage, ScanActivity.class);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to deserialise Kafka message: %s", rawMessage);
            // Re-throw so SmallRye nacks the message → DLQ (configured in application.properties)
            throw new RuntimeException("Deserialisation failed", e);
        }

        String workflowId = activity.workflowId();
        String requestRefStr = activity.requestRef();

        if (workflowId == null || workflowId.isBlank()) {
            LOG.warnf("Message missing metadata.original_event_uid – skipping. raw=%s", rawMessage);
            return;
        }

        UUID requestRef = null;
        if (requestRefStr != null && !requestRefStr.isBlank()) {
            try {
                requestRef = UUID.fromString(requestRefStr);
            } catch (IllegalArgumentException e) {
                LOG.warnf("scan.uid '%s' is not a valid UUID – storing as null", requestRefStr);
            }
        }

        LOG.debugf("Processing scan event: workflow_id=%s request_ref=%s tool=%s type=%s",
                workflowId, requestRef, activity.scanningTool(), activity.scanType());

        governanceService.handleScanEvent(workflowId, requestRef,
                activity.scanningTool(), activity.scanType());
    }
}
