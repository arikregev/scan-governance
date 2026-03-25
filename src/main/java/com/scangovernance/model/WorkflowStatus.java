package com.scangovernance.model;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;

public enum WorkflowStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
    }

    public static WorkflowStatus fromTemporal(WorkflowExecutionStatus temporalStatus) {
        if (temporalStatus == null) {
            return QUEUED;
        }
        return switch (temporalStatus) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING           -> RUNNING;
            case WORKFLOW_EXECUTION_STATUS_COMPLETED         -> COMPLETED;
            case WORKFLOW_EXECUTION_STATUS_FAILED            -> FAILED;
            case WORKFLOW_EXECUTION_STATUS_CANCELED          -> CANCELLED;
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT         -> TIMED_OUT;
            case WORKFLOW_EXECUTION_STATUS_TERMINATED        -> FAILED;
            // Continued-as-new is still active; the new run will be tracked separately.
            case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW  -> RUNNING;
            default                                          -> QUEUED;
        };
    }
}
