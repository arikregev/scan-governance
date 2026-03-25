package com.scangovernance.entity;

import com.scangovernance.model.WorkflowStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "workflow")
public class WorkflowEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    public UUID id;

    /** Corresponds to scan.uid from the OCSF Kafka message (the scan request identifier). */
    @Column(name = "request_ref")
    public UUID requestRef;

    /** Temporal workflow ID: metadata.original_event_uid from OCSF. */
    @Column(name = "workflow_id", length = 500)
    public String workflowId;

    /**
     * Temporal run ID. Initially NULL (QUEUED state); populated once Temporal
     * creates the execution.  Multiple rows may exist for the same workflow_id,
     * each with a distinct run_id (retries / re-triggers).
     */
    @Column(name = "run_id", length = 255)
    public String runId;

    /** E.g. CHECKMARX, SNYK – sourced from metadata.product.name. */
    @Column(name = "scanning_tool", length = 100)
    public String scanningTool;

    /** E.g. SAST, DAST – sourced from scan.type. */
    @Column(name = "scan_type", length = 50)
    public String scanType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    public WorkflowStatus status = WorkflowStatus.QUEUED;

    /** Workflow result payload fetched from Temporal upon completion. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    public String result;

    /** Workflow input payload fetched from Temporal's start event. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "jsonb")
    public String input;

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    @Column(name = "duration_seconds")
    public Integer durationSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
