package com.scangovernance.model.ocsf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OcsfMetadata {

    @JsonProperty("original_event_uid")
    private String originalEventUid;

    private OcsfProduct product;

    private List<OcsfTag> tags;

    private String version;

    public String getOriginalEventUid() { return originalEventUid; }
    public OcsfProduct getProduct()     { return product; }
    public List<OcsfTag> getTags()      { return tags; }
    public String getVersion()          { return version; }

    public void setOriginalEventUid(String originalEventUid) { this.originalEventUid = originalEventUid; }
    public void setProduct(OcsfProduct product)              { this.product = product; }
    public void setTags(List<OcsfTag> tags)                  { this.tags = tags; }
    public void setVersion(String version)                   { this.version = version; }
}
