# Creating a Kubernetes Operator in Java.

Code example for the presentation https://www.slideshare.net/rdebusscher/creating-a-kubernetes-operator-in-java.

The Operator will watch for new Custom Resource YAML files that are applied to Kubernetes and will create a Payara DAS and Deployment group with 2 instances.

## Goal

The goals of the code are

- Register Custom Resource in Kubernetes client
- List and Watch principles of Kubernetes client.
- Programmatically apply (and delete) a Kubernetes Deployment.
- Programmatically apply (and delete) a Kubernetes Service.
- Use the Metadata of Deployment and Service to identify the resources.
- Use Metadata of deployment (like getting IP address of pod)
- Execute shell commands within Pod and get the output.

## Requirement

Kubernetes 1.19+ cluster and Kubernetes Client installed locally.

## Steps

1\. Build Kubernetes operator in Java

    mvn clean package

  Build the Java project to have a executable jar file with the Operator.

2\. Build Docker Image with Operator

Have a look at the DockerFile in the directory  `src/main/docker/payara-operator`

The Docker image can be built with the following command: (from within that directory)

    docker build -t payara-operator:0.2 .

3\. Build Docker Image for Payara instance

The instances of Payara for the Deployment group can be based on the `payara/server-node` official Docker image but the `entrypoint.sh` script needs to be adjusted to be able to work correctly within a Kubernetes environment.

The changes can be found in the directory `src/main/docker/payara-node-k8s` and the image can be built using:

    docker build -t server-node-k8s:5.2021.1 .

4\. Register custom resource definition

The file `define-payara-domain-resource.yaml` (within root of project) contains the definition of the Custom resource and needs to be applied to the cluster with the command:

    kubectl apply -f define-payara-domain-resource.yaml

5\. Launch the Kubernetes Operator

The Operator needs to be running within the Kubernetes Cluster so that it can react on 'instances' of our Custom Resource.  The command to launch the operator is:

    kubectl apply -f payara-operator.yaml

6\. Check Operator

You can see if the Operator correctly found the Custom Resource Definition and the namespace it is running in by performing the command:

    kubectl logs deployment/payara-operator

7\. Apply Custom Resource data

We can now apply a YAML file containing the custom resource values which will trigger the logic of our Operator.

    kubectl apply -f payara-domain-resource.yaml

8\. Verify

You can follow the progress of the Operator logic by checking the log again, the same we did in step 6.  Once you see that the operator has performed the logic (`ADDED a resource` is not in the log), you can verify with:


    kubectl get all

And see the resources the operator has created, and verify within Payara with

    kubectl port-forward deployment/payara-domain 4848:4848

And open the Payara Web Administration console at `http://localhost:4848` (you can login with user name and password values `admin` ) and check with the Deployment Group menu item if all linking is done properly.


9\.  Cleanup

With the following steps, all resources can be cleaned up on the Kubernetes Cluster.


    kubectl delete -f payara-domain-resource.yaml

    kubectl delete -f payara-operator.yaml

    kubectl delete -f define-payara-domain-resource.yaml
