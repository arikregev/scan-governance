package com.scangovernance.repository;

import com.scangovernance.entity.WorkflowEntity;
import com.scangovernance.model.WorkflowStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WorkflowRepository implements PanacheRepository<WorkflowEntity> {

    /** Find by the unique (workflow_id, run_id) pair. */
    public Optional<WorkflowEntity> findByWorkflowIdAndRunId(String workflowId, String runId) {
        return find("workflowId = ?1 and runId = ?2", workflowId, runId).firstResultOptional();
    }

    /** Find the un-resolved placeholder row for a specific scan request. */
    public Optional<WorkflowEntity> findByWorkflowIdAndRequestRef(String workflowId, UUID requestRef) {
        return find("workflowId = ?1 and requestRef = ?2", workflowId, requestRef).firstResultOptional();
    }

    /** All non-terminal rows, ordered by creation so older entries are processed first. */
    public List<WorkflowEntity> findNonTerminal(int limit) {
        return find("status not in ?1 order by createdAt asc",
                List.of(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED,
                        WorkflowStatus.TIMED_OUT, WorkflowStatus.CANCELLED))
                .page(0, limit)
                .list();
    }

    /** Non-terminal rows for a specific workflow_id (used during catch-up sync). */
    public List<WorkflowEntity> findActiveByWorkflowId(String workflowId) {
        return find("workflowId = ?1 and status not in ?2", workflowId,
                List.of(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED,
                        WorkflowStatus.TIMED_OUT, WorkflowStatus.CANCELLED))
                .list();
    }

    /** All known run_ids for a workflow_id (used to detect new runs during catch-up). */
    public List<String> findTrackedRunIds(String workflowId) {
        return find("workflowId = ?1 and runId is not null", workflowId)
                .<WorkflowEntity>list()
                .stream()
                .map(e -> e.runId)
                .toList();
    }

    /**
     * QUEUED rows with no run_id yet, ordered oldest-first.
     * Used to assign newly discovered Temporal run_ids in FIFO order.
     */
    public List<WorkflowEntity> findQueuedWithoutRunId(String workflowId) {
        return find("workflowId = ?1 and runId is null and status = ?2 order by createdAt asc",
                workflowId, WorkflowStatus.QUEUED)
                .list();
    }

    /**
     * Rows stuck in QUEUED or RUNNING beyond the timeout threshold.
     * These are candidates for TIMED_OUT.
     */
    public List<WorkflowEntity> findTimedOut(LocalDateTime cutoff) {
        return find("status in ?1 and createdAt < ?2",
                List.of(WorkflowStatus.QUEUED, WorkflowStatus.RUNNING), cutoff)
                .list();
    }
}
