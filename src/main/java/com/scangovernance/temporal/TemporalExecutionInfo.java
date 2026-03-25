package com.scangovernance.temporal;

import com.scangovernance.model.WorkflowStatus;

import java.time.Instant;

/**
 * Snapshot of a single Temporal workflow execution, ready for DB persistence.
 */
public record TemporalExecutionInfo(
        String workflowId,
        String runId,
        WorkflowStatus status,
        Instant startTime,
        Instant closeTime,
        /** Raw JSON string of the workflow input (from WorkflowExecutionStarted event). */
        String inputJson,
        /** Raw JSON string of the workflow result/failure (from close event). */
        String resultJson
) {}
