package com.scangovernance.repository;

import com.scangovernance.entity.WorkflowEntity;
import com.scangovernance.model.WorkflowStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WorkflowRepository {

    private final Jdbi jdbi;

    @Inject
    public WorkflowRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ── Write operations ─────────────────────────────────────────────────────

    public void insert(WorkflowEntity entity) {
        if (entity.id == null) entity.id = UUID.randomUUID();
        if (entity.createdAt == null) entity.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (entity.updatedAt == null) entity.updatedAt = LocalDateTime.now(ZoneOffset.UTC);

        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO workflow
                    (id, request_ref, workflow_id, run_id, scanning_tool, scan_type,
                     status, result, input, started_at, completed_at, duration_seconds,
                     created_at, updated_at)
                VALUES
                    (CAST(:id AS uuid), CAST(:requestRef AS uuid), :workflowId, :runId, :scanningTool, :scanType,
                     :status, CAST(:result AS jsonb), CAST(:input AS jsonb),
                     :startedAt, :completedAt, :durationSeconds, :createdAt, :updatedAt)
                """)
                .bind("id",              entity.id)
                .bind("requestRef",      entity.requestRef)
                .bind("workflowId",      entity.workflowId)
                .bind("runId",           entity.runId)
                .bind("scanningTool",    entity.scanningTool)
                .bind("scanType",        entity.scanType)
                .bind("status",          entity.status.name())
                .bind("result",          entity.result)
                .bind("input",           entity.input)
                .bind("startedAt",       entity.startedAt)
                .bind("completedAt",     entity.completedAt)
                .bind("durationSeconds", entity.durationSeconds)
                .bind("createdAt",       entity.createdAt)
                .bind("updatedAt",       entity.updatedAt)
                .execute());
    }

    public void update(WorkflowEntity entity) {
        entity.updatedAt = LocalDateTime.now(ZoneOffset.UTC);

        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE workflow SET
                    run_id           = :runId,
                    status           = :status,
                    result           = CAST(:result AS jsonb),
                    input            = CAST(:input AS jsonb),
                    started_at       = :startedAt,
                    completed_at     = :completedAt,
                    duration_seconds = :durationSeconds,
                    updated_at       = :updatedAt
                WHERE id = CAST(:id AS uuid)
                """)
                .bind("id",              entity.id)
                .bind("runId",           entity.runId)
                .bind("status",          entity.status.name())
                .bind("result",          entity.result)
                .bind("input",           entity.input)
                .bind("startedAt",       entity.startedAt)
                .bind("completedAt",     entity.completedAt)
                .bind("durationSeconds", entity.durationSeconds)
                .bind("updatedAt",       entity.updatedAt)
                .execute());
    }

    // ── Read operations ──────────────────────────────────────────────────────

    public Optional<WorkflowEntity> findById(UUID id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM workflow WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(new WorkflowEntityMapper())
                .findFirst());
    }

    public Optional<WorkflowEntity> findByWorkflowIdAndRunId(String workflowId, String runId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM workflow WHERE workflow_id = :workflowId AND run_id = :runId")
                .bind("workflowId", workflowId)
                .bind("runId", runId)
                .map(new WorkflowEntityMapper())
                .findFirst());
    }

    public Optional<WorkflowEntity> findByWorkflowIdAndRequestRef(String workflowId, UUID requestRef) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM workflow WHERE workflow_id = :workflowId AND request_ref = CAST(:requestRef AS uuid)")
                .bind("workflowId", workflowId)
                .bind("requestRef", requestRef)
                .map(new WorkflowEntityMapper())
                .findFirst());
    }

    /** All non-terminal rows, oldest first – used by the status poller. */
    public List<WorkflowEntity> findNonTerminal(int limit) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT * FROM workflow
                        WHERE status NOT IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED')
                        ORDER BY created_at
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map(new WorkflowEntityMapper())
                .list());
    }

    /** Non-terminal rows for a specific workflow_id (used during catch-up sync). */
    public List<WorkflowEntity> findActiveByWorkflowId(String workflowId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT * FROM workflow
                        WHERE workflow_id = :workflowId
                          AND status NOT IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED')
                        """)
                .bind("workflowId", workflowId)
                .map(new WorkflowEntityMapper())
                .list());
    }

    /** All tracked run_ids for a workflow_id (to detect new runs during catch-up). */
    public List<String> findTrackedRunIds(String workflowId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT run_id FROM workflow WHERE workflow_id = :workflowId AND run_id IS NOT NULL")
                .bind("workflowId", workflowId)
                .mapTo(String.class)
                .list());
    }

    /**
     * QUEUED rows with no run_id yet, oldest-first.
     * Used to assign newly discovered Temporal run_ids in FIFO order.
     */
    public List<WorkflowEntity> findQueuedWithoutRunId(String workflowId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT * FROM workflow
                        WHERE workflow_id = :workflowId
                          AND run_id IS NULL
                          AND status = 'QUEUED'
                        ORDER BY created_at
                        """)
                .bind("workflowId", workflowId)
                .map(new WorkflowEntityMapper())
                .list());
    }

    /** Rows stuck in QUEUED or RUNNING beyond the timeout cutoff. */
    public List<WorkflowEntity> findTimedOut(LocalDateTime cutoff) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT * FROM workflow
                        WHERE status IN ('QUEUED','RUNNING')
                          AND created_at < :cutoff
                        """)
                .bind("cutoff", cutoff)
                .map(new WorkflowEntityMapper())
                .list());
    }

    // ── Row mapper ───────────────────────────────────────────────────────────

    public static class WorkflowEntityMapper implements RowMapper<WorkflowEntity> {
        @Override
        public WorkflowEntity map(ResultSet rs, StatementContext ctx) throws SQLException {
            WorkflowEntity e = new WorkflowEntity();
            e.id             = rs.getObject("id", UUID.class);
            e.requestRef     = rs.getObject("request_ref", UUID.class);
            e.workflowId     = rs.getString("workflow_id");
            e.runId          = rs.getString("run_id");
            e.scanningTool   = rs.getString("scanning_tool");
            e.scanType       = rs.getString("scan_type");
            e.status         = WorkflowStatus.valueOf(rs.getString("status"));
            e.result         = rs.getString("result");
            e.input          = rs.getString("input");
            e.startedAt      = rs.getObject("started_at", LocalDateTime.class);
            e.completedAt    = rs.getObject("completed_at", LocalDateTime.class);
            Object dur       = rs.getObject("duration_seconds");
            e.durationSeconds = dur != null ? ((Number) dur).intValue() : null;
            e.createdAt      = rs.getObject("created_at", LocalDateTime.class);
            e.updatedAt      = rs.getObject("updated_at", LocalDateTime.class);
            return e;
        }
    }
}
