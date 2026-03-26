package com.scangovernance.config;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jdbi.v3.core.Jdbi;

/**
 * Produces the application-scoped {@link Jdbi} instance backed by the Quarkus
 * Agroal connection pool.  This replaces the quarkiverse quarkus-jdbi extension
 * so we can use plain {@code jdbi3-core} without a compatibility wrapper.
 */
@ApplicationScoped
public class JdbiProducer {

    private final AgroalDataSource dataSource;

    @Inject
    public JdbiProducer(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Produces
    @ApplicationScoped
    public Jdbi jdbi() {
        return Jdbi.create(dataSource);
    }
}
