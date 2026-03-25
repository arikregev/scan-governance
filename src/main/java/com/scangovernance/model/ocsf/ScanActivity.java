package com.scangovernance.model.ocsf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level OCSF Scan Activity event consumed from Kafka.
 *
 * Mapping to workflow table:
 *   metadata.originalEventUid  → workflow_id
 *   metadata.product.name      → scanning_tool
 *   scan.uid                   → request_ref  (UUID)
 *   scan.type                  → scan_type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScanActivity {

    @JsonProperty("activity_id")
    private String activityId;

    @JsonProperty("activity_name")
    private String activityName;

    private OcsfMetadata metadata;

    private OcsfScan scan;

    @JsonProperty("severity_id")
    private String severityId;

    private long time;

    @JsonProperty("type_uid")
    private String typeUid;

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getActivityId()    { return activityId; }
    public String getActivityName()  { return activityName; }
    public OcsfMetadata getMetadata(){ return metadata; }
    public OcsfScan getScan()        { return scan; }
    public String getSeverityId()    { return severityId; }
    public long getTime()            { return time; }
    public String getTypeUid()       { return typeUid; }

    public void setActivityId(String activityId)       { this.activityId = activityId; }
    public void setActivityName(String activityName)   { this.activityName = activityName; }
    public void setMetadata(OcsfMetadata metadata)     { this.metadata = metadata; }
    public void setScan(OcsfScan scan)                 { this.scan = scan; }
    public void setSeverityId(String severityId)       { this.severityId = severityId; }
    public void setTime(long time)                     { this.time = time; }
    public void setTypeUid(String typeUid)             { this.typeUid = typeUid; }

    // ── Convenience helpers ──────────────────────────────────────────────────

    /** Returns metadata.originalEventUid; null if metadata is absent. */
    public String workflowId() {
        return metadata != null ? metadata.getOriginalEventUid() : null;
    }

    /** Returns scan.uid (request_ref); null if scan is absent. */
    public String requestRef() {
        return scan != null ? scan.getUid() : null;
    }

    /** Returns metadata.product.name (scanning tool); null if absent. */
    public String scanningTool() {
        if (metadata == null || metadata.getProduct() == null) return null;
        return metadata.getProduct().getName();
    }

    /** Returns scan.type; null if scan is absent. */
    public String scanType() {
        return scan != null ? scan.getType() : null;
    }
}
