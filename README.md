# scan-governance

A Quarkus microservice that governs the lifecycle of security scan workflows.
It consumes OCSF Scan Activity events from Kafka, records each workflow execution in PostgreSQL, and tracks its progress via the Temporal SDK until the workflow reaches a terminal state.

---

## How it works

```
Scan-Service
    │
    │  OCSF Scan Activity (JSON, keyed by workflow_id)
    ▼
 Kafka topic: scan-events
    │
    ▼
ScanEventConsumer          ──► WorkflowGovernanceService
                                    │
                          ┌─────────┴──────────┐
                          ▼                    ▼
                   RequestRepository    TemporalQueryService
                   (JDBI read-only)     (gRPC – list/describe
                   looks up request_ref  executions, fetch
                   from request table)   input & result)
                          │                    │
                          └─────────┬──────────┘
                                    ▼
                           WorkflowRepository
                           (JDBI – workflow table)
                                    │
                                    ▼
                              PostgreSQL DB

WorkflowStatusPoller (@Scheduled)
    polls non-terminal rows every N seconds via WorkflowGovernanceService
    expires rows stuck beyond timeout threshold
```

### Message flow

1. **Kafka consumer** receives an OCSF `ScanActivity` JSON message.
2. `scan.uid` is used to look up the primary key of the matching row in the (read-only) `request` table — this becomes `workflow.request_ref`.
3. `metadata.original_event_uid` becomes `workflow.workflow_id`.
4. A **QUEUED** placeholder row is inserted into the `workflow` table.
5. **Temporal is queried immediately** for all known executions of that `workflow_id`:
   - If Temporal has no data yet → row stays QUEUED.
   - If running → `run_id`, `started_at`, and `input` are populated; status set to RUNNING.
   - If already completed → all fields are populated including `result`, `completed_at`, and `duration_seconds`.
6. The **status poller** runs every N seconds, refreshing every non-terminal row from Temporal until it reaches COMPLETED, FAILED, TIMED_OUT, or CANCELLED.

### Catch-up after downtime

Kafka messages are **keyed by `workflow_id`**, guaranteeing per-key ordering within a partition.
If the service was down and multiple Temporal executions accumulated for the same `workflow_id`, `syncTemporalRuns` enumerates all of them and matches them to existing QUEUED placeholder rows in **FIFO order** (oldest placeholder → oldest untracked run). Any surplus Temporal runs get new rows; any surplus placeholders stay QUEUED until their run appears.

---

## Tech stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Quarkus 3.x |
| Messaging | Apache Kafka (SmallRye Reactive Messaging) |
| Database | PostgreSQL (JDBI, Flyway migrations) |
| Workflow engine | Temporal (Java SDK – client/query only, no workers) |
| Build | Maven |

---

## Database schema

### `workflow` table (owned by this service)

| Column | Type | Description |
|---|---|---|
| `id` | UUID PK | Row identifier |
| `request_ref` | UUID | FK to `request.id` (resolved from `scan.uid`) |
| `workflow_id` | VARCHAR(500) | Temporal workflow ID (`metadata.original_event_uid`) |
| `run_id` | VARCHAR(255) | Temporal run ID – NULL until Temporal creates the execution |
| `scanning_tool` | VARCHAR(100) | Tool name e.g. CHECKMARX (`metadata.product.name`) |
| `scan_type` | VARCHAR(50) | Scan type e.g. SAST (`scan.type`) |
| `status` | VARCHAR(50) | QUEUED / RUNNING / COMPLETED / FAILED / TIMED_OUT / CANCELLED |
| `input` | JSONB | Workflow input payload fetched from Temporal history |
| `result` | JSONB | Workflow result/failure payload fetched from Temporal history |
| `started_at` | TIMESTAMP | UTC – from Temporal's WorkflowExecutionStarted event |
| `completed_at` | TIMESTAMP | UTC – from Temporal's close event |
| `duration_seconds` | INTEGER | `completed_at − started_at` in seconds |
| `created_at` | TIMESTAMP | UTC – row creation time |
| `updated_at` | TIMESTAMP | UTC – last update time |

### `request` table (read-only, owned by Scan-Service)

Queried via `RequestRepository` to resolve `request_ref`.
Key column: `request_id UUID` — matched against `scan.uid` from the OCSF message to retrieve the row's primary key (`id`).

---

## Configuration

All values are externalised via environment variables with sensible defaults for local development.

| Environment variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `scandb` | Database name |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker(s) |
| `KAFKA_SCAN_EVENTS_TOPIC` | `scan-events` | Topic to consume |
| `KAFKA_GROUP_ID` | `scan-governance` | Consumer group ID |
| `KAFKA_DLQ_TOPIC` | `scan-events-dlq` | Dead-letter topic for failed messages |
| `TEMPORAL_HOST` | `localhost:7233` | Temporal frontend address |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |
| `POLLER_INTERVAL` | `30s` | How often the status poller runs |
| `POLLER_BATCH_SIZE` | `100` | Max non-terminal rows processed per poller tick |
| `WORKFLOW_TIMEOUT_HOURS` | `24` | Hours before a stuck QUEUED/RUNNING row is marked TIMED_OUT |

---

## Running locally

### Prerequisites

- Java 21
- Maven 3.9+
- Docker (for dependencies)

### Start dependencies

```bash
# PostgreSQL
docker run -d --name postgres \
  -e POSTGRES_DB=scandb \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:16

# Kafka (single-node)
docker run -d --name kafka \
  -e KAFKA_CFG_PROCESS_ROLES=broker,controller \
  -e KAFKA_CFG_NODE_ID=1 \
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -p 9092:9092 bitnami/kafka:latest

# Temporal (dev server – single binary, no Cassandra needed)
docker run -d --name temporal \
  -p 7233:7233 \
  temporalio/auto-setup:latest
```

### Start the service

```bash
# Dev mode (hot reload)
mvn quarkus:dev

# Or build and run the fat JAR
mvn package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

---

## Running tests

Unit tests mock all external dependencies (repositories, Temporal) — no running infrastructure required.

```bash
# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=WorkflowGovernanceServiceTest
```

---

## Project structure

```
src/main/java/com/scangovernance/
  kafka/
    ScanEventConsumer.java          Kafka @Incoming consumer; deserialises OCSF JSON
  model/
    WorkflowStatus.java             Enum + Temporal status mapping
    ocsf/                           OCSF Scan Activity POJOs
  entity/
    WorkflowEntity.java             Plain POJO representing a workflow table row
  repository/
    WorkflowRepository.java         JDBI repository – all workflow table operations
    RequestRepository.java          JDBI read-only access to the request table
  temporal/
    TemporalClientProducer.java     CDI @Produces for WorkflowServiceStubs + WorkflowClient
    TemporalQueryService.java       Temporal gRPC queries (list, describe, history)
    TemporalExecutionInfo.java      DTO record for a single Temporal execution snapshot
  service/
    WorkflowGovernanceService.java  Core logic: initial write, catch-up sync, refresh
  scheduler/
    WorkflowStatusPoller.java       @Scheduled polling + timeout expiry
src/main/resources/
  application.properties            All config with env-var overrides
  db/migration/
    V1__create_workflow_table.sql   Flyway migration
```
