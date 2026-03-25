package com.scangovernance.model.ocsf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OcsfTag {
    private String name;
    private String value;

    public String getName()  { return name; }
    public String getValue() { return value; }
    public void setName(String name)   { this.name = name; }
    public void setValue(String value) { this.value = value; }
}
