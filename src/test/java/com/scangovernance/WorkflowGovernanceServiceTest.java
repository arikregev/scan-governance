package com.scangovernance;

import com.scangovernance.entity.WorkflowEntity;
import com.scangovernance.model.WorkflowStatus;
import com.scangovernance.repository.WorkflowRepository;
import com.scangovernance.service.WorkflowGovernanceService;
import com.scangovernance.temporal.TemporalExecutionInfo;
import com.scangovernance.temporal.TemporalQueryService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class WorkflowGovernanceServiceTest {

    @Inject
    WorkflowGovernanceService governanceService;

    @Inject
    WorkflowRepository workflowRepository;

    @InjectMock
    TemporalQueryService temporalQueryService;

    @BeforeEach
    @Transactional
    void cleanUp() {
        workflowRepository.deleteAll();
        // Default: Temporal returns no executions
        when(temporalQueryService.listExecutions(anyString())).thenReturn(List.of());
    }

    @Test
    void handleScanEvent_createsQueuedRow() {
        UUID requestRef = UUID.randomUUID();
        String workflowId = "app1:svc:build1:CHECKMARX";

        governanceService.handleScanEvent(workflowId, requestRef, "CHECKMARX", "SAST");

        List<WorkflowEntity> rows = workflowRepository.list("workflowId", workflowId);
        assertEquals(1, rows.size());
        assertEquals(WorkflowStatus.QUEUED, rows.get(0).status);
        assertNull(rows.get(0).runId);
        assertEquals(requestRef, rows.get(0).requestRef);
    }

    @Test
    void handleScanEvent_isIdempotent() {
        UUID requestRef = UUID.randomUUID();
        String workflowId = "app1:svc:build1:CHECKMARX";

        governanceService.handleScanEvent(workflowId, requestRef, "CHECKMARX", "SAST");
        governanceService.handleScanEvent(workflowId, requestRef, "CHECKMARX", "SAST");

        long count = workflowRepository.count("workflowId", workflowId);
        assertEquals(1, count, "Duplicate message should not insert a second row");
    }

    @Test
    void syncTemporalRuns_assignsRunIdToQueuedPlaceholder() {
        UUID requestRef = UUID.randomUUID();
        String workflowId = "app1:svc:build1:CHECKMARX";
        String runId = UUID.randomUUID().toString();

        // First call: no Temporal data → QUEUED placeholder inserted
        governanceService.handleScanEvent(workflowId, requestRef, "CHECKMARX", "SAST");

        // Now Temporal has the execution
        TemporalExecutionInfo info = new TemporalExecutionInfo(
                workflowId, runId, WorkflowStatus.RUNNING,
                java.time.Instant.now(), null,
                "{\"scanId\":\"123\"}", null);
        when(temporalQueryService.listExecutions(workflowId)).thenReturn(List.of(info));

        governanceService.syncTemporalRuns(workflowId);

        WorkflowEntity entity = workflowRepository
                .findByWorkflowIdAndRunId(workflowId, runId)
                .orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, entity.status);
        assertEquals(runId, entity.runId);
        assertNotNull(entity.startedAt);
        assertEquals("{\"scanId\":\"123\"}", entity.input);
    }

    @Test
    void syncTemporalRuns_catchUp_multiplePlaceholdersMultipleRuns() {
        String workflowId = "app1:svc:build2:CHECKMARX";
        UUID ref1 = UUID.randomUUID();
        UUID ref2 = UUID.randomUUID();

        // Two Kafka messages arrived; service was down so no Temporal data yet
        governanceService.handleScanEvent(workflowId, ref1, "CHECKMARX", "SAST");
        governanceService.handleScanEvent(workflowId, ref2, "CHECKMARX", "SAST");

        // Temporal now reports two completed runs
        String runId1 = UUID.randomUUID().toString();
        String runId2 = UUID.randomUUID().toString();
        when(temporalQueryService.listExecutions(workflowId)).thenReturn(List.of(
                new TemporalExecutionInfo(workflowId, runId1, WorkflowStatus.COMPLETED,
                        java.time.Instant.now().minusSeconds(120),
                        java.time.Instant.now().minusSeconds(10),
                        "{}", "{\"result\":\"ok\"}"),
                new TemporalExecutionInfo(workflowId, runId2, WorkflowStatus.RUNNING,
                        java.time.Instant.now(), null, "{}", null)
        ));

        governanceService.syncTemporalRuns(workflowId);

        long count = workflowRepository.count("workflowId", workflowId);
        assertEquals(2, count);

        WorkflowEntity completed = workflowRepository
                .findByWorkflowIdAndRunId(workflowId, runId1).orElseThrow();
        assertEquals(WorkflowStatus.COMPLETED, completed.status);
        assertNotNull(completed.durationSeconds);

        WorkflowEntity running = workflowRepository
                .findByWorkflowIdAndRunId(workflowId, runId2).orElseThrow();
        assertEquals(WorkflowStatus.RUNNING, running.status);
    }

    @Test
    void markTimedOut_setsTerminalStatus() {
        String workflowId = "app1:svc:build3:SNYK";
        governanceService.handleScanEvent(workflowId, UUID.randomUUID(), "SNYK", "SCA");

        WorkflowEntity entity = workflowRepository.list("workflowId", workflowId).get(0);
        governanceService.markTimedOut(entity.id);

        WorkflowEntity updated = workflowRepository.findById(entity.id);
        assertEquals(WorkflowStatus.TIMED_OUT, updated.status);
    }
}
