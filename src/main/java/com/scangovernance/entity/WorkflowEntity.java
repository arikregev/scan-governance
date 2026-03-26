package com.scangovernance.entity;

import com.scangovernance.model.WorkflowStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Plain data object representing one row in the {@code workflow} table.
 * No ORM annotations – persistence is handled entirely by {@code WorkflowRepository} via JDBI.
 */
public class WorkflowEntity {

    public UUID id;

    /** FK to request.id (resolved from scan.uid via the request table). */
    public UUID requestRef;

    /** Temporal workflow ID – metadata.original_event_uid from OCSF. */
    public String workflowId;

    /**
     * Temporal run ID. Initially NULL (QUEUED); filled in once Temporal
     * creates the execution.
     */
    public String runId;

    /** E.g. CHECKMARX, SNYK – sourced from metadata.product.name. */
    public String scanningTool;

    /** E.g. SAST, DAST – sourced from scan.type. */
    public String scanType;

    public WorkflowStatus status = WorkflowStatus.QUEUED;

    /** Workflow result JSON fetched from Temporal on completion. */
    public String result;

    /** Workflow input JSON fetched from Temporal's start event. */
    public String input;

    public LocalDateTime startedAt;
    public LocalDateTime completedAt;
    public Integer durationSeconds;

    public LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);
    public LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);
}
