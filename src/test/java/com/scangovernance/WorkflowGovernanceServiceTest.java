package com.scangovernance;

import com.scangovernance.entity.WorkflowEntity;
import com.scangovernance.model.WorkflowStatus;
import com.scangovernance.repository.RequestRepository;
import com.scangovernance.repository.WorkflowRepository;
import com.scangovernance.service.WorkflowGovernanceService;
import com.scangovernance.temporal.TemporalExecutionInfo;
import com.scangovernance.temporal.TemporalQueryService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class WorkflowGovernanceServiceTest {

    @Inject
    WorkflowGovernanceService governanceService;

    @InjectMock
    WorkflowRepository workflowRepository;

    @InjectMock
    RequestRepository requestRepository;

    @InjectMock
    TemporalQueryService temporalQueryService;

    @BeforeEach
    void setUp() {
        when(temporalQueryService.listExecutions(anyString())).thenReturn(List.of());
        when(requestRepository.findPkByRequestId(any())).thenReturn(Optional.empty());
        when(workflowRepository.findByWorkflowIdAndRequestRef(anyString(), any())).thenReturn(Optional.empty());
        when(workflowRepository.findTrackedRunIds(anyString())).thenReturn(List.of());
        when(workflowRepository.findQueuedWithoutRunId(anyString())).thenReturn(List.of());
        when(workflowRepository.findActiveByWorkflowId(anyString())).thenReturn(List.of());
    }

    @Test
    void handleScanEvent_insertsQueuedRow() {
        UUID requestId = UUID.randomUUID();
        UUID requestRef = UUID.randomUUID();
        String workflowId = "app:svc:build:CHECKMARX";

        when(requestRepository.findPkByRequestId(requestId)).thenReturn(Optional.of(requestRef));

        governanceService.handleScanEvent(workflowId, requestId, "CHECKMARX", "SAST");

        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).insert(captor.capture());
        WorkflowEntity inserted = captor.getValue();

        assertEquals(workflowId, inserted.workflowId);
        assertEquals(requestRef, inserted.requestRef);
        assertEquals(WorkflowStatus.QUEUED, inserted.status);
        assertEquals("CHECKMARX", inserted.scanningTool);
        assertEquals("SAST", inserted.scanType);
        assertNull(inserted.runId);
    }

    @Test
    void handleScanEvent_isIdempotent() {
        UUID requestId = UUID.randomUUID();
        UUID requestRef = UUID.randomUUID();
        String workflowId = "app:svc:build:CHECKMARX";

        when(requestRepository.findPkByRequestId(requestId)).thenReturn(Optional.of(requestRef));
        when(workflowRepository.findByWorkflowIdAndRequestRef(workflowId, requestRef))
                .thenReturn(Optional.of(new WorkflowEntity()));

        governanceService.handleScanEvent(workflowId, requestId, "CHECKMARX", "SAST");

        verify(workflowRepository, never()).insert(any());
    }

    @Test
    void syncTemporalRuns_assignsRunIdToQueuedPlaceholder() {
        String workflowId = "app:svc:build:CHECKMARX";
        String runId = UUID.randomUUID().toString();

        WorkflowEntity placeholder = new WorkflowEntity();
        placeholder.id = UUID.randomUUID();
        placeholder.workflowId = workflowId;
        placeholder.status = WorkflowStatus.QUEUED;

        when(workflowRepository.findQueuedWithoutRunId(workflowId)).thenReturn(List.of(placeholder));

        TemporalExecutionInfo info = new TemporalExecutionInfo(
                workflowId, runId, WorkflowStatus.RUNNING,
                Instant.now(), null, "{\"scanId\":\"123\"}", null);
        when(temporalQueryService.listExecutions(workflowId)).thenReturn(List.of(info));

        governanceService.syncTemporalRuns(workflowId);

        assertEquals(runId, placeholder.runId);
        assertEquals(WorkflowStatus.RUNNING, placeholder.status);
        assertEquals("{\"scanId\":\"123\"}", placeholder.input);
        verify(workflowRepository).update(placeholder);
    }

    @Test
    void syncTemporalRuns_catchUp_surplusRunCreatesNewRow() {
        String workflowId = "app:svc:build:SNYK";
        String runId1 = UUID.randomUUID().toString();
        String runId2 = UUID.randomUUID().toString();

        // Only one placeholder but two Temporal runs
        WorkflowEntity placeholder = new WorkflowEntity();
        placeholder.id = UUID.randomUUID();
        placeholder.workflowId = workflowId;
        placeholder.status = WorkflowStatus.QUEUED;

        when(workflowRepository.findQueuedWithoutRunId(workflowId)).thenReturn(List.of(placeholder));

        when(temporalQueryService.listExecutions(workflowId)).thenReturn(List.of(
                new TemporalExecutionInfo(workflowId, runId1, WorkflowStatus.COMPLETED,
                        Instant.now().minusSeconds(120), Instant.now().minusSeconds(10),
                        "{}", "{\"result\":\"ok\"}"),
                new TemporalExecutionInfo(workflowId, runId2, WorkflowStatus.RUNNING,
                        Instant.now(), null, "{}", null)
        ));

        governanceService.syncTemporalRuns(workflowId);

        // Placeholder gets first run
        assertEquals(runId1, placeholder.runId);
        assertEquals(WorkflowStatus.COMPLETED, placeholder.status);
        verify(workflowRepository).update(placeholder);

        // Second run triggers a new insert
        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).insert(captor.capture());
        WorkflowEntity newRow = captor.getValue();
        assertEquals(runId2, newRow.runId);
        assertEquals(WorkflowStatus.RUNNING, newRow.status);
    }

    @Test
    void markTimedOut_setsTerminalStatus() {
        UUID entityId = UUID.randomUUID();
        WorkflowEntity entity = new WorkflowEntity();
        entity.id = entityId;
        entity.status = WorkflowStatus.RUNNING;

        when(workflowRepository.findById(entityId)).thenReturn(Optional.of(entity));

        governanceService.markTimedOut(entityId);

        assertEquals(WorkflowStatus.TIMED_OUT, entity.status);
        verify(workflowRepository).update(entity);
    }

    @Test
    void handleScanEvent_withNullWorkflowId_skips() {
        governanceService.handleScanEvent(null, UUID.randomUUID(), "CHECKMARX", "SAST");
        verify(workflowRepository, never()).insert(any());
    }
}
