package be.rubus.payara.accelerator.k8s.operator;

import be.rubus.payara.accelerator.k8s.operator.resource.PayaraDomainResource;
import be.rubus.payara.accelerator.k8s.operator.resource.PayaraDomainResourceList;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.*;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Kubernetes operator to read a Custom resource file and setup a Payara Deployment Group setup.
 * This is not a fully functional version, it was created for the presentation "Creating a Kubernetes Operator In java" https://www.slideshare.net/rdebusscher/creating-a-kubernetes-operator-in-java
 */
public class PayaraOperatorTest {

    // Keep latest version of the Custom Resource cached.
    private Map<String, PayaraDomainResource> cache = new ConcurrentHashMap<>();

    // Client to access the Custom resource
    private NonNamespaceOperation<PayaraDomainResource, PayaraDomainResourceList, Resource<PayaraDomainResource>> customResourceClient;

    private static KubernetesClient client;
    private static PodUtil podUtil;

    private final String namespace;

    public static void main(String args[]) throws IOException {

        Config config = new ConfigBuilder().build();
        client = new DefaultKubernetesClient(config);

        String namespace = client.getNamespace();
        LogHelper.log("Running in Namespace " + namespace);

        // Register the Custom Resource so that Client will use PayaraDomainResource for the YAML.
        KubernetesDeserializer.registerCustomKind(HasMetadata.getApiVersion(PayaraDomainResource.class), "Domain", PayaraDomainResource.class);


        // Get the Custom Resource, If not registered, our Operator can't work properly so throw an Exception.
        CustomResourceDefinition crd = client
                .apiextensions().v1().customResourceDefinitions()
                .list()
                .getItems()
                .stream()
                .filter(d -> "domains.payara.fish".equals(d.getMetadata().getName()))
                .findAny()
                .orElseThrow(
                        () -> new RuntimeException("Deployment error: Custom resource definition Domain for payara.fish not found."));


        // Start the Operator as Deamon thread (since using Watch)
        new PayaraOperatorTest(namespace,
                client
                        .customResources(PayaraDomainResource.class, PayaraDomainResourceList.class)
                        .inNamespace(namespace)

        ).performWork();

    }

    public PayaraOperatorTest(String namespace,
                              NonNamespaceOperation<PayaraDomainResource, PayaraDomainResourceList, Resource<PayaraDomainResource>> customResourceClient) {
        this.customResourceClient = customResourceClient;
        this.namespace = namespace;
    }

    private void performWork() {

        podUtil = new PodUtil(client, namespace);

        listThenWatch(this::handleEvent);
    }

    public void listThenWatch(BiConsumer<Watcher.Action, String> callback) {

        try {

            // list

            customResourceClient
                    .list()
                    .getItems()
                    .forEach(resource -> {
                                cache.put(resource.getMetadata().getUid(), resource);
                                String uid = resource.getMetadata().getUid();
                                callback.accept(Watcher.Action.ADDED, uid);
                            }
                    );

            // watch

            customResourceClient.watch(new Watcher<PayaraDomainResource>() {
                @Override
                public void eventReceived(Action action, PayaraDomainResource resource) {
                    try {
                        // Get the UID for the Custom Resource instance.
                        String uid = resource.getMetadata().getUid();
                        if (cache.containsKey(uid)) {
                            // This Custom Resuerce instance is already known. Check if it is a updated version.
                            int knownResourceVersion = Integer.parseInt(cache.get(uid).getMetadata().getResourceVersion());
                            int receivedResourceVersion = Integer.parseInt(resource.getMetadata().getResourceVersion());
                            if (knownResourceVersion > receivedResourceVersion) {
                                // We have already a version which is more recent, ignore this event.
                                return;
                            }
                        }
                        LogHelper.log("received " + action + " for resource " + resource);
                        if (action == Action.ADDED || action == Action.MODIFIED) {
                            cache.put(uid, resource);
                        }
                        callback.accept(action, uid);
                        // We have handled the resource so now we can remove it from the cache
                        if (action == Action.DELETED) {
                            cache.remove(uid);
                        }
                    } catch (Exception e) {
                        e.printStackTrace(); // FIXME
                        System.exit(-1);
                    }
                }

                @Override
                public void onClose(WatcherException cause) {
                    cause.printStackTrace();// FIXME
                    System.exit(-1);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();// FIXME
            System.exit(-1);
        }
    }

    private void handleEvent(Watcher.Action action, String uid) {
        LogHelper.log(uid + " action = " + action.name());

        PayaraDomainResource payaraDomainResource = cache.get(uid);
        //LogHelper.log(payaraDomainResource.toString());

        if (action == Watcher.Action.ADDED) {
            // New Yaml file, Add DAS and a number of Instances and join them in a Deployment Group.
            try {
                AliveDetector domainDetector = addNewDeploymentDomain(payaraDomainResource);
                addNewService(payaraDomainResource);

                domainDetector.waitUntilReady();  // Waits until the domain is up.
                if (domainDetector.isUpAndRunning()) {
                    prepareDomain(domainDetector.getPod(), payaraDomainResource);
                }
            } catch (Exception e) {
                e.printStackTrace();  // FIXME
            }
            LogHelper.log("ADDED a resource");
        }
        if (action == Watcher.Action.DELETED) {

            removeDeploymentNode(payaraDomainResource);
            removeDeploymentDomain(payaraDomainResource);
            removeService(payaraDomainResource);
            LogHelper.log("DELETED a resource");
        }
        if (action == Watcher.Action.MODIFIED) {
            LogHelper.log("TODO: Modification not implemented yet");

            LogHelper.log("MODIFIED a resource");
        }
    }

    /**
     * When DAS server is running, prepare the config and add a deployment for the instances.
     *
     * @param payaraDomain
     * @param payaraDomainResource
     * @throws IOException
     */
    private void prepareDomain(Pod payaraDomain, PayaraDomainResource payaraDomainResource) throws IOException {
        String command = "${PAYARA_DIR}/bin/asadmin --user=${ADMIN_USER} --passwordfile=${PASSWORD_FILE} create-deployment-group  "
                + payaraDomainResource.getSpec().geDeploymentGroup();
        LogHelper.log(command);

        String output = executeWithinPod(payaraDomain, command);
        LogHelper.log("output for create-deployment-group " + output);

        command = "${PAYARA_DIR}/bin/asadmin --user=${ADMIN_USER} --passwordfile=${PASSWORD_FILE} copy-config default-config  "
                + payaraDomainResource.getSpec().geDeploymentGroup();
        LogHelper.log(command);

        output = executeWithinPod(payaraDomain, command);
        LogHelper.log("output for copy-config " + output);

        addNewDeploymentNode(payaraDomainResource, podUtil.lookupIP(payaraDomain));

    }

    /**
     * Remove the deployment for the Payara Instances.
     *
     * @param payaraDomainResource
     */
    private void removeDeploymentNode(PayaraDomainResource payaraDomainResource) {
        Optional<Deployment> deployment = findDeployment(payaraDomainResource, "node");
        if (deployment.isPresent()) {
            NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = client.apps().deployments().inNamespace(namespace);
            deployments.delete(deployment.get());

            // LogHelper.log("Removed Deployment Node" + deployment.get());  // With all info from K8S
            LogHelper.log("Removed Node K8S Deployment ");

        }
    }

    /**
     * Remove the deployment for the DAS.
     *
     * @param payaraDomainResource
     */
    private void removeDeploymentDomain(PayaraDomainResource payaraDomainResource) {
        Optional<Deployment> deployment = findDeployment(payaraDomainResource, "domain");
        if (deployment.isPresent()) {
            NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = client.apps().deployments().inNamespace(namespace);
            deployments.delete(deployment.get());

            //LogHelper.log("Removed Domain Deployment " + deployment.get());  // With all info from K8S
            LogHelper.log("Removed  Domain K8S Deployment ");

        }
    }

    /**
     * Remove the Service for the DAS.
     *
     * @param payaraDomainResource
     */
    private void removeService(PayaraDomainResource payaraDomainResource) {
        Optional<Service> service = findService(payaraDomainResource);

        if (service.isPresent()) {
            NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> services = client.services().inNamespace(namespace);
            services.delete(service.get());

            //LogHelper.log("Removed Service " + service.get());  // With all info from K8S
            LogHelper.log("Removed  K8S Service ");

        }
    }

    /**
     * Find the Kubernetes Deployment.
     *
     * @param payaraDomainResource The Custom Resource
     * @param type                 The type, domain (=DAS) or node (=Instances)
     * @return
     */
    private Optional<Deployment> findDeployment(PayaraDomainResource payaraDomainResource, String type) {
        return client.apps().deployments()
                .inNamespace(namespace)
                .list()
                .getItems()
                .stream()
                .filter(d -> d.getMetadata().getOwnerReferences().stream()
                        .anyMatch(ownerReference -> ownerReference.getUid().equals(type + payaraDomainResource.getMetadata().getUid())))
                .findFirst();
    }

    /**
     * Find the Kubernetes Service.
     *
     * @param payaraDomainResource
     * @return
     */
    private Optional<Service> findService(PayaraDomainResource payaraDomainResource) {
        return client.services()
                .inNamespace(namespace)
                .list()
                .getItems()
                .stream()
                .filter(d -> d.getMetadata().getOwnerReferences().stream()
                        .anyMatch(ownerReference -> ownerReference.getUid().equals(payaraDomainResource.getMetadata().getUid())))
                .findFirst();
    }

    /**
     * Add a new Deployment for the DAS to K8S.
     *
     * @param payaraDomainResource
     * @return
     * @throws IOException
     */
    private AliveDetector addNewDeploymentDomain(PayaraDomainResource payaraDomainResource) throws IOException {

        if (noDeploymentYet(payaraDomainResource, "domain")) {
            // There is no deployment yet

            // Process the payaraDomainDeployment.yaml file with ThymeLeaf so that it is customized with info from the Custom Resource
            String processed = ThymeleafEngine.getInstance().processFile("/payaraDomainDeployment.yaml", payaraDomainResource.getSpec().asTemplateVariables());
            ByteArrayInputStream inputStream = new ByteArrayInputStream(processed.getBytes());
            NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = client.apps().deployments().inNamespace(namespace);

            // Load the Deployment into Kubernetes (not executed yet)
            Deployment newDeployment = deployments.load(inputStream).get();
            // Add some metadata so that we can link this deployment to the Custom Resource.
            newDeployment.getMetadata().getOwnerReferences().get(0).setUid("domain" + payaraDomainResource.getMetadata().getUid());
            newDeployment.getMetadata().getOwnerReferences().get(0).setName(payaraDomainResource.getMetadata().getName());

            // Apply the Deployment to K8S.
            deployments.create(newDeployment);
            //LogHelper.log("Created new Deployment " + newDeployment);  // With all info from K8S
            LogHelper.log("Created new K8S Deployment ");

            inputStream.close();
            // Return a AliveDetector so tat we can wait until DAS is up and running.
            return waitServerStarted();
        } else {
            LogHelper.log("Deployment already available");
        }
        return null;
    }

    private AliveDetector waitServerStarted() {
        AliveDetector detector = new AliveDetector("domain", podUtil);

        // Do checks asynchronous.
        new Thread(detector).start();
        return detector;
    }


    private boolean noDeploymentYet(PayaraDomainResource payaraDomainResource, String type) {
        return !findDeployment(payaraDomainResource, type).isPresent();
    }

    private void addNewDeploymentNode(PayaraDomainResource payaraDomainResource, String dasIP) throws IOException {

        if (noDeploymentYet(payaraDomainResource, "node")) {
            // Add deployment for the Instances, similar to addNewDeploymentDomain().
            String processed = ThymeleafEngine.getInstance().processFile("/payaraNodeDeployment.yaml", payaraDomainResource.getSpec().asTemplateVariablesForNode(dasIP));
            ByteArrayInputStream inputStream = new ByteArrayInputStream(processed.getBytes());
            NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = client.apps().deployments().inNamespace(namespace);

            Deployment newDeployment = deployments.load(inputStream).get();
            newDeployment.getMetadata().getOwnerReferences().get(0).setUid("node" + payaraDomainResource.getMetadata().getUid());
            newDeployment.getMetadata().getOwnerReferences().get(0).setName(payaraDomainResource.getMetadata().getName());


            deployments.create(newDeployment);
            // LogHelper.log("Created new Deployment " + newDeployment);
            LogHelper.log("Created new Deployment for Node");
            inputStream.close();
        } else {
            LogHelper.log("Deployment already available");
        }
    }

    private void addNewService(PayaraDomainResource payaraDomainResource) throws IOException {

        if (noServiceYet(payaraDomainResource)) {
            //There is no service yet

            // Process the payaraDomainService.yaml file with ThymeLeaf so that it is customized with info from the Custom Resource
            String processed = ThymeleafEngine.getInstance().processFile("//payaraDomainService.yaml", payaraDomainResource.getSpec().asTemplateVariables());
            ByteArrayInputStream inputStream = new ByteArrayInputStream(processed.getBytes());
            NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> services = client.services().inNamespace(namespace);

            // Load the Service into Kubernetes (not executed yet)
            Service newService = services.load(inputStream).get();
            // Add some metadata so that we can link this Service to the Custom Resource.
            newService.getMetadata().getOwnerReferences().get(0).setUid(payaraDomainResource.getMetadata().getUid());
            newService.getMetadata().getOwnerReferences().get(0).setName(payaraDomainResource.getMetadata().getName());

            // Apply the Service to K8S.
            services.create(newService);
            //LogHelper.log("Created new Service " + newService);   // With all info from K8S
            LogHelper.log("Created new K8S Service ");
            inputStream.close();
        } else {
            LogHelper.log("Service already available");
        }
    }

    private boolean noServiceYet(PayaraDomainResource payaraDomainResource) {
        return !findService(payaraDomainResource).isPresent();
    }

    /**
     * Execute a OS command in the POD and get the output of it.
     * @param pod
     * @param command
     * @return
     */
    private String executeWithinPod(Pod pod, String command) {
        final CountDownLatch execLatch = new CountDownLatch(1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExecWatch watch = client.pods().withName(pod.getMetadata().getName()).writingOutput(baos).usingListener(new ExecListener() {
            @Override
            public void onOpen(Response response) {
            }

            @Override
            public void onFailure(Throwable t, Response response) {
                execLatch.countDown();
            }

            @Override
            public void onClose(int code, String reason) {
                execLatch.countDown();
            }
        }).exec("/bin/bash", "-c", command);

        try {
            // Don't wait forever until command is executed (can be stuck for example) so max 1 min.
            execLatch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace(); // FIXME
        }
        return baos.toString();
    }


}
