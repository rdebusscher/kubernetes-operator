package be.rubus.payara.accelerator.k8s.operator.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.HashMap;
import java.util.Map;

@JsonDeserialize()
public class PayaraDomainSpec {

    @JsonProperty("payara-image")
    private String payaraImage;

    @JsonProperty("application")
    private String application;

    @JsonProperty("instances")
    private int instances;

    @JsonProperty("config-script")
    private String configScript;

    @JsonProperty("artifact")
    private String artifact;

    public String getPayaraImage() {
        return payaraImage;
    }

    public String getApplication() {
        return application;
    }

    public String geDeploymentGroup() {
        return "dg_" + application;
    }

    public int getInstances() {
        return instances;
    }

    public String getConfigScript() {
        return configScript;
    }

    public String getArtifact() {
        return artifact;
    }

    public Map<String, String> asTemplateVariables() {
        Map<String, String> result = new HashMap<>();
        result.put("payara_image", payaraImage);
        result.put("application", application);
        result.put("instances", String.valueOf(instances));
        result.put("config_script", configScript);
        result.put("artifact", artifact);
        return result;
    }

    public Map<String, String> asTemplateVariablesForNode(String dasIP) {
        Map<String, String> result = new HashMap<>();
        result.put("payara_image", payaraImage.replace("payara/server-full", "server-node-k8s"));
        result.put("application", application);
        result.put("instances", String.valueOf(instances));
        result.put("config_script", configScript);
        result.put("artifact", artifact);
        result.put("deployment_group", geDeploymentGroup());
        result.put("das_host", dasIP);
        return result;
    }

    @Override
    public String toString() {
        return "PayaraDomainSpec{" +
                "payaraImage='" + payaraImage + '\'' +
                ", application='" + application + '\'' +
                ", instances=" + instances +
                ", configScript='" + configScript + '\'' +
                ", artifact='" + artifact + '\'' +
                '}';
    }
}
