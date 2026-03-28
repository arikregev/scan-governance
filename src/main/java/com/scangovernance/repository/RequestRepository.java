package com.scangovernance.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to the {@code request} table (owned by Scan-Service).
 *
 * <p>The {@code request} table contains a {@code request_id} column that matches
 * {@code scan.uid} from the incoming OCSF Kafka message.  We need its primary key
 * ({@code id}) to populate {@code workflow.request_ref}.
 */
@ApplicationScoped
public class RequestRepository {

    private final Jdbi jdbi;

    @Inject
    public RequestRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Looks up the primary key ({@code id}) of the request row whose
     * {@code request_id} matches the given UUID.
     *
     * @param requestId the {@code scan.uid} value from the OCSF message
     * @return the request table PK, or empty if not yet present
     */
    public Optional<UUID> findPkByRequestId(UUID requestId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT id FROM request WHERE request_id = CAST(:requestId AS uuid)")
                        .bind("requestId", requestId)
                        .mapTo(UUID.class)
                        .findFirst()
        );
    }
}
