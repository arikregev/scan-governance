package com.scangovernance.service;

import com.scangovernance.entity.WorkflowEntity;
import com.scangovernance.model.WorkflowStatus;
import com.scangovernance.repository.RequestRepository;
import com.scangovernance.repository.WorkflowRepository;
import com.scangovernance.temporal.TemporalExecutionInfo;
import com.scangovernance.temporal.TemporalQueryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Core governance logic.
 *
 * <h2>Catch-up / multiple run_id scenario</h2>
 * When the service is down for an extended period, multiple Temporal executions
 * may have been created for the same workflow_id.  Because Kafka messages are
 * keyed by workflow_id (ordering is guaranteed per partition), messages are
 * processed sequentially for any given workflow_id.
 *
 * On every {@link #handleScanEvent} call we also call
 * {@link #syncTemporalRuns(String)} which enumerates <em>all</em> Temporal
 * executions for that workflow_id and upserts rows into the DB.  QUEUED rows
 * that still have no run_id are matched to untracked runs in FIFO order
 * (oldest Kafka message → oldest untracked Temporal run).
 */
@ApplicationScoped
public class WorkflowGovernanceService {

    private static final Logger LOG = Logger.getLogger(WorkflowGovernanceService.class);

    @Inject
    WorkflowRepository workflowRepository;

    @Inject
    RequestRepository requestRepository;

    @Inject
    TemporalQueryService temporalQueryService;

    // ── Called by Kafka consumer ─────────────────────────────────────────────

    /**
     * Processes one OCSF Scan Activity Kafka message.
     *
     * @param workflowId   Temporal workflow ID (metadata.original_event_uid)
     * @param requestId    Scan request UUID from OCSF (scan.uid = request.request_id)
     * @param scanningTool Product name (metadata.product.name)
     * @param scanType     Scan type (scan.type)
     */
    @Transactional
    public void handleScanEvent(String workflowId, UUID requestId,
                                String scanningTool, String scanType) {

        if (workflowId == null || workflowId.isBlank()) {
            LOG.warn("Received scan event with null/blank workflow_id – skipping");
            return;
        }

        // Resolve request_ref: look up the request table PK whose request_id = scan.uid.
        // The request table is owned by Scan-Service and may not yet have the row if the
        // message races ahead of the request write; in that case we store null and the
        // poller can backfill if needed (or the row remains with a null request_ref).
        UUID requestRef = null;
        if (requestId != null) {
            requestRef = requestRepository.findPkByRequestId(requestId).orElse(null);
            if (requestRef == null) {
                LOG.warnf("No request row found for request_id=%s – storing request_ref as null", requestId);
            }
        }

        // Idempotency: do not insert a duplicate row for the same (workflowId, requestRef)
        if (requestRef != null) {
            boolean exists = workflowRepository
                    .findByWorkflowIdAndRequestRef(workflowId, requestRef)
                    .isPresent();
            if (exists) {
                LOG.debugf("Duplicate event: workflow_id=%s request_ref=%s – skipped", workflowId, requestRef);
                return;
            }
        }

        // Insert a placeholder QUEUED row; run_id will be filled in by syncTemporalRuns
        WorkflowEntity entity = new WorkflowEntity();
        entity.workflowId   = workflowId;
        entity.requestRef   = requestRef;
        entity.scanningTool = scanningTool;
        entity.scanType     = scanType;
        entity.status       = WorkflowStatus.QUEUED;
        workflowRepository.persist(entity);

        LOG.debugf("Inserted QUEUED row: id=%s workflow_id=%s request_ref=%s",
                entity.id, workflowId, requestRef);

        // Immediately try to synchronise with Temporal so we have the best possible
        // initial state (handles catch-up when many runs accumulated while we were down)
        syncTemporalRuns(workflowId);
    }

    // ── Called by scheduler ──────────────────────────────────────────────────

    /**
     * Refreshes all non-terminal rows for a workflow_id from Temporal.
     * Safe to call multiple times (idempotent).
     */
    @Transactional
    public void syncTemporalRuns(String workflowId) {
        List<TemporalExecutionInfo> temporalRuns = temporalQueryService.listExecutions(workflowId);

        if (temporalRuns.isEmpty()) {
            LOG.debugf("No Temporal executions found for workflow_id=%s (still QUEUED)", workflowId);
            return;
        }

        List<String> trackedRunIds = workflowRepository.findTrackedRunIds(workflowId);

        // ── 1. Update existing rows whose run_id is already known ────────────
        for (TemporalExecutionInfo run : temporalRuns) {
            if (trackedRunIds.contains(run.runId())) {
                workflowRepository.findByWorkflowIdAndRunId(workflowId, run.runId())
                        .ifPresent(e -> updateFromTemporalInfo(e, run));
            }
        }

        // ── 2. Handle Temporal runs NOT yet in the DB ───────────────────────
        List<TemporalExecutionInfo> untrackedRuns = temporalRuns.stream()
                .filter(r -> !trackedRunIds.contains(r.runId()))
                .toList();

        if (untrackedRuns.isEmpty()) return;

        // Assign untracked runs to existing QUEUED-without-runId rows first (FIFO).
        // This is the "catch-up" path: we had N placeholders and N new Temporal runs.
        List<WorkflowEntity> unassignedPlaceholders =
                workflowRepository.findQueuedWithoutRunId(workflowId);

        for (int i = 0; i < untrackedRuns.size(); i++) {
            TemporalExecutionInfo run = untrackedRuns.get(i);
            if (i < unassignedPlaceholders.size()) {
                // Re-use the existing placeholder, filling in the run_id + Temporal data
                WorkflowEntity placeholder = unassignedPlaceholders.get(i);
                placeholder.runId = run.runId();
                updateFromTemporalInfo(placeholder, run);
                LOG.debugf("Assigned run_id=%s to existing placeholder id=%s (workflow_id=%s)",
                        run.runId(), placeholder.id, workflowId);
            } else {
                // More Temporal runs than placeholders: create new rows
                WorkflowEntity newEntity = new WorkflowEntity();
                newEntity.workflowId  = workflowId;
                newEntity.scanningTool = workflowRepository.findActiveByWorkflowId(workflowId)
                        .stream().findFirst().map(e -> e.scanningTool).orElse(null);
                newEntity.scanType = workflowRepository.findActiveByWorkflowId(workflowId)
                        .stream().findFirst().map(e -> e.scanType).orElse(null);
                newEntity.runId = run.runId();
                updateFromTemporalInfo(newEntity, run);
                workflowRepository.persist(newEntity);
                LOG.debugf("Created new row for untracked run_id=%s workflow_id=%s", run.runId(), workflowId);
            }
        }
    }

    /**
     * Refreshes a single row that already has a run_id.
     * Called by the scheduler for each non-terminal row individually.
     */
    @Transactional
    public void refreshWorkflow(UUID entityId) {
        WorkflowEntity entity = workflowRepository.findById(entityId);
        if (entity == null || entity.status.isTerminal()) return;

        if (entity.runId == null) {
            // No run_id yet – do a full sync for this workflow_id
            syncTemporalRuns(entity.workflowId);
            return;
        }

        temporalQueryService.describeExecution(entity.workflowId, entity.runId)
                .ifPresentOrElse(
                        info -> updateFromTemporalInfo(entity, info),
                        () -> LOG.debugf("Temporal execution not found: workflow_id=%s run_id=%s",
                                entity.workflowId, entity.runId)
                );
    }

    /** Marks a workflow row as TIMED_OUT (used by the scheduler). */
    @Transactional
    public void markTimedOut(UUID entityId) {
        WorkflowEntity entity = workflowRepository.findById(entityId);
        if (entity != null && !entity.status.isTerminal()) {
            entity.status = WorkflowStatus.TIMED_OUT;
            entity.updatedAt = LocalDateTime.now();
            LOG.infof("Marked workflow id=%s as TIMED_OUT (workflow_id=%s)", entityId, entity.workflowId);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void updateFromTemporalInfo(WorkflowEntity entity, TemporalExecutionInfo info) {
        entity.status = info.status();
        entity.runId  = info.runId();

        if (info.startTime() != null) {
            entity.startedAt = toLocalDateTime(info.startTime());
        }

        if (info.inputJson() != null) {
            entity.input = info.inputJson();
        }

        if (info.status().isTerminal()) {
            if (info.closeTime() != null) {
                entity.completedAt = toLocalDateTime(info.closeTime());
            }
            if (info.resultJson() != null) {
                entity.result = info.resultJson();
            }
            if (entity.startedAt != null && entity.completedAt != null) {
                long seconds = java.time.Duration
                        .between(entity.startedAt, entity.completedAt)
                        .getSeconds();
                entity.durationSeconds = (int) seconds;
            }
        }

        entity.updatedAt = LocalDateTime.now();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
