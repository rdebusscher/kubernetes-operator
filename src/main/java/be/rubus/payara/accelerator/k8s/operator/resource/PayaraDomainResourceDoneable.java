package be.rubus.payara.accelerator.k8s.operator.resource;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.client.CustomResourceDoneable;

public class PayaraDomainResourceDoneable extends CustomResourceDoneable<PayaraDomainResource> {
    public PayaraDomainResourceDoneable(PayaraDomainResource resource, Function<PayaraDomainResource, PayaraDomainResource> function) {
        super(resource, function);
    }
}
