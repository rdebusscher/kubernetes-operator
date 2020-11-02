package be.rubus.payara.accelerator.k8s.operator;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.Optional;

public class PodUtil {

    private  KubernetesClient client;
    private String namespace;

    public PodUtil(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /**
     * Lookup a pod based on his lobel (= the same name shown by `kubectl get pods`)
     * @param podLabel
     * @return
     */
    public Pod lookupPod(String podLabel) {
        Pod result = null;
        Optional<Pod> pod = client.pods().inNamespace(namespace).list().getItems()
                .stream()
                .filter(p -> hasPodLabel(p, "app", podLabel))
                .findAny();

        if (pod.isPresent()) {
            result = pod.get();
        } else {
            // FIXME
            System.out.println("Not found");
        }

        return result;
    }

    private boolean hasPodLabel(Pod pod, String key, String value) {
        return pod.getMetadata().getLabels().entrySet().stream()
                .anyMatch(e -> key.equals(e.getKey()) && value.equals(e.getValue()));

    }

    public String lookupIP(Pod pod) {
        // FIXME We should check if container is ready and ip is something meaningful
        return pod.getStatus().getPodIP();
    }

}
