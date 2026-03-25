package com.scangovernance.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TemporalClientProducer {

    private static final Logger LOG = Logger.getLogger(TemporalClientProducer.class);

    @ConfigProperty(name = "scan.governance.temporal.host", defaultValue = "localhost:7233")
    String temporalHost;

    @ConfigProperty(name = "scan.governance.temporal.namespace", defaultValue = "default")
    String temporalNamespace;

    private WorkflowServiceStubs serviceStubs;
    private WorkflowClient workflowClient;

    @Produces
    @ApplicationScoped
    public WorkflowServiceStubs produceServiceStubs() {
        LOG.infof("Connecting to Temporal at %s (namespace=%s)", temporalHost, temporalNamespace);
        serviceStubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalHost)
                        .build());
        return serviceStubs;
    }

    @Produces
    @ApplicationScoped
    public WorkflowClient produceWorkflowClient(WorkflowServiceStubs stubs) {
        workflowClient = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(temporalNamespace)
                        .build());
        return workflowClient;
    }

    @PreDestroy
    void shutdown() {
        if (serviceStubs != null) {
            try {
                serviceStubs.shutdown();
            } catch (Exception e) {
                LOG.warn("Error shutting down Temporal service stubs", e);
            }
        }
    }
}
