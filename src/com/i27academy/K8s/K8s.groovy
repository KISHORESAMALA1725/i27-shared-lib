package com.i27academy.K8s;

class K8s {
    def jenkins
    K8s(jenkins) {
        this.jenkins = jenkins
    }

    // Method TO Authenticate to K8S Cluster

    def auth_login(clusterName, zone, projectID) {
        jenkins.sh """
        echo " ***** creating k8s authentication login menthod ***** " 
        gcloud compute instances list
        echo " ***** create config file for environment ***** "
        gcloud container clusters get-credentials $clusterName --zone $zone --project $projectID
        kubectl get nodes
        """
    }

    def K8sdeploy (fileName, docker_image, namespace) {
        jenkins.sh """
            echo " ***** entering into K8S deployment method **** "
            sed -i "s|DIT|${docker_image}|g" ./.cicd/${fileName}
            kubectl apply -f ./cicd/${fileName} -n $namespace
        """
    }
}
