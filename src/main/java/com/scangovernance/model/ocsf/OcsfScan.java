package com.scangovernance.model.ocsf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OcsfScan {

    private String type;

    @JsonProperty("type_id")
    private String typeId;

    /** Mapped to request_ref (UUID) in the workflow table. */
    private String uid;

    public String getType()   { return type; }
    public String getTypeId() { return typeId; }
    public String getUid()    { return uid; }

    public void setType(String type)     { this.type = type; }
    public void setTypeId(String typeId) { this.typeId = typeId; }
    public void setUid(String uid)       { this.uid = uid; }
}
