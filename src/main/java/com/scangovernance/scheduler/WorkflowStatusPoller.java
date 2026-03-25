package com.scangovernance.scheduler;

import com.scangovernance.entity.WorkflowEntity;
import com.scangovernance.repository.WorkflowRepository;
import com.scangovernance.service.WorkflowGovernanceService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodically polls Temporal for workflow status updates.
 *
 * <p>Two tasks run on configurable intervals:
 * <ol>
 *   <li>{@code pollActiveWorkflows} – refreshes every non-terminal row from Temporal.</li>
 *   <li>{@code expireTimedOutWorkflows} – marks rows that have been stuck in
 *       QUEUED/RUNNING beyond the configured timeout.</li>
 * </ol>
 */
@ApplicationScoped
public class WorkflowStatusPoller {

    private static final Logger LOG = Logger.getLogger(WorkflowStatusPoller.class);

    @Inject
    WorkflowRepository workflowRepository;

    @Inject
    WorkflowGovernanceService governanceService;

    @ConfigProperty(name = "scan.governance.poller.batch-size", defaultValue = "100")
    int batchSize;

    @ConfigProperty(name = "scan.governance.workflow.timeout-hours", defaultValue = "24")
    int timeoutHours;

    /**
     * Polls all non-terminal workflow rows and refreshes their status from Temporal.
     * The interval is driven by {@code scan.governance.poller.interval} (default 30 s).
     */
    @Scheduled(every = "${scan.governance.poller.interval:30s}", identity = "workflow-status-poller")
    public void pollActiveWorkflows() {
        List<WorkflowEntity> nonTerminal = workflowRepository.findNonTerminal(batchSize);

        if (nonTerminal.isEmpty()) {
            return;
        }

        LOG.debugf("Polling %d non-terminal workflow(s)", nonTerminal.size());

        for (WorkflowEntity entity : nonTerminal) {
            try {
                governanceService.refreshWorkflow(entity.id);
            } catch (Exception e) {
                // Log and continue – we don't want a single failure to block the rest of the batch
                LOG.errorf(e, "Error refreshing workflow id=%s workflow_id=%s",
                        entity.id, entity.workflowId);
            }
        }
    }

    /**
     * Expires workflows that have been stuck in a non-terminal state longer
     * than the configured timeout.  Runs every hour.
     */
    @Scheduled(every = "1h", identity = "workflow-timeout-checker")
    @Transactional
    public void expireTimedOutWorkflows() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(timeoutHours);
        List<WorkflowEntity> candidates = workflowRepository.findTimedOut(cutoff);

        if (candidates.isEmpty()) return;

        LOG.infof("Expiring %d workflow(s) stuck since before %s", candidates.size(), cutoff);

        for (WorkflowEntity entity : candidates) {
            try {
                governanceService.markTimedOut(entity.id);
            } catch (Exception e) {
                LOG.errorf(e, "Error expiring workflow id=%s", entity.id);
            }
        }
    }
}
