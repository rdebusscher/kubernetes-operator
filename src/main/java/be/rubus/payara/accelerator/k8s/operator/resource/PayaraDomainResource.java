package be.rubus.payara.accelerator.k8s.operator.resource;

import io.fabric8.kubernetes.client.CustomResource;

public class PayaraDomainResource extends CustomResource {

    private PayaraDomainSpec spec;

    public PayaraDomainSpec getSpec() {
        return spec;
    }

    public void setSpec(PayaraDomainSpec spec) {
        this.spec = spec;
    }

    @Override
    public String toString() {
        String name = getMetadata() != null ? getMetadata().getName() : "unknown";
        String version = getMetadata() != null ? getMetadata().getResourceVersion() : "unknown";
        return "name=" + name + " version=" + version + " value=" + spec;
    }
}
