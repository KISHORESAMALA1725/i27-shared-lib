import com.i27academy.builds.Docker;
import com.i27academy.K8s.K8s;

def call (Map pipelineParams) {
    K8s K8s = new K8s(this)
    pipeline {
    agent {
        label 'k8s-slave'
    }
    parameters {
        // choice(name: 'buildOnly', choices: 'no\nyes', description: 'this will run maven build')
        // choice(name: 'scanOnly',  choices: 'no\nyes', description: 'this will run sonarscans')
        choice(name: 'dockerPush',choices: 'no\nyes', description: 'this will trigger build,sonarscan,image is build & pushed to repo')
        choice(name: 'deploytoDev', choices: 'no\nyes', description: 'this will deploy to dev')
        choice(name: 'deploytoTest', choices: 'no\nyes', description: 'this will deploy to test')
        choice(name: 'deploytoStage', choices: 'no\nyes', description: 'this will deploy to stage')
        choice(name: 'deploytoProd', choices: 'no\nyes', description: 'this will deploy to prod')
    }

    // tools {
    //     maven 'maven-3.8.8'
    //     jdk 'jdk-17'
    // }
    
    environment {
        APPLICATION_NAME = "${pipelineParams.appName}"
        // DEV_HOST_PORT = "${pipelineParams.devHostPort}"
        // TST_HOST_PORT = "${pipelineParams.tstHostPort}"
        // STG_HOST_PORT = "${pipelineParams.stgHostPort}"
        // PROD_HOST_PORT = "${pipelineParams.prodHostPort}"
        // CONT_PORT = "${pipelineParams.contPort}"
        // POM_VERSION = readMavenPom().getVersion() 
        // POM_PACKAGING = readMavenPom().getPackaging()
        DOCKER_HUB = "docker.io/kishoresamala84"
        DOCKER_CREDS = credentials('kishoresamala84_docker_creds')

        DEV_CLUSTER_NAME = "i27-cluster"
        DEV_CLUSTER_ZONE = "us-central1-c"
        DEV_PROJECT_ID = "saharssh-456212"

        K8S_DEV_FILE = "k8s_dev.yaml"
        K8S_TEST_FILE = "k8s_test.yaml"
        K8S_STAGE_FILE = "k8s_stage.yaml"
        K8S_PROD_FILE = "k8s_prod.yaml"

        DEV_NAMESPACE = "cart-dev-ns"
        TEST_NAMESPACE = "cart-test-ns"
        STAGE_NAMESPACE = "cart-stage-ns"
        PROD_NAMESPACE = "prod-stage-ns"
    }

    stages {
        // stage (' ***** BUILD STAGE ***** ') {
        //     when {
        //         expression {
        //             params.buildOnly == 'yes'
        //         }
        //     }
        //     steps {
        //         script {
        //             docker.buildApp("${env.APPLICATION_NAME}")
        //         }
        //     }
        // }

        // stage (' ***** SONARQUBE STAGE ***** ') {
        //     when {
        //         anyOf {
        //         expression {
        //             params.buildOnly == 'yes'
        //             params.scanOnly == 'yes'                    
        //             params.dockerPush == 'yes'
        //         }
        //     }

        //     }
        //     steps {
        //         echo "***** sonar stage implementing *****"                
        //         withSonarQubeEnv('sonarqube') {
        //                 sh """
        //                     mvn clean verify sonar:sonar \
        //                     -Dsonar.projectKey=i27-eureka-05 \
        //                     -Dsonar.host.url=http://34.85.201.20:9000/ \
        //                     -Dsonar.login=sqa_3ed9d5a5f6a874816c2f1c19b9c7674747b81b21
        //                 """
        //         }
        //         timeout(time: 2, unit: 'MINUTES') {
        //             waitForQualityGate abortPipeline: true
        //         }
        //     }
        // }

        // stage ('BUILD-FORMAT') {
        //     steps {
        //         script {
        //             // Existing : i27-eureka-0.0.1-SNAPSHOT.jar
        //             // Destination: i27-eureka-buildnumber-branchname.packagin
        //             sh """
        //             echo "Testing source jar-source: i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING}"
        //             echo "Tesing destination Jar: i27-${env.APPLICATION_NAME}-${currentBuild.number}-${BRANCH_NAME}.${env.POM_PACKAGING}"
        //             """ 
        //         }
        //     }
        // }

        stage (' ***** Docker-Build-Push ***** ') {
            when {
                expression {
                    params.dockerPush == 'yes'
                }
            }
            steps {
                script {
                    dockerBuildAndPush().call()
            }
        }

      }

        stage (' ***** Deploy to dev ***** ') {
            when {
                expression {
                    params.deploytoDev == 'yes'
                }
            }
            steps {
                script {
                    def docker_image = "${DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
                    K8s.auth_login("${env.DEV_CLUSTER_NAME}","${env.DEV_CLUSTER_ZONE}","${env.DEV_PROJECT_ID}")
                    imageValidation().call()
                    K8s.K8sdeploy("${env.K8S_DEV_FILE}", docker_image, "${env.DEV_NAMESPACE}")
                    // dockerDeploy('dev', "${env.DEV_HOST_PORT}", "${env.CONT_PORT}").call()
                }

            }
        }

        stage (' ***** Deploy to Test ***** ') {
            when {
                expression {
                    params.deploytoTest == 'yes'
                }
            }
            steps {
                script {
                    imageValidation().call()
                    dockerDeploy('tst', "${env.TST_HOST_PORT}", "${env.CONT_PORT}").call()
                }
            }
        }

        stage (' ***** Deploy to Stage ***** ') {
            when {
                allOf{
                    anyOf{
                        expression {
                            params.deploytoStage == 'yes'
                        }
                    }
                    anyOf{ 
                            branch 'release/*'
                            tag pattern: "v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2\\}", comparator: "REGEXP"
 
                    }
                }
            }

            steps {
                script {
                    imageValidation().call()
                    dockerDeploy('stg', "${env.STG_HOST_PORT}", "${env.CONT_PORT}").call()
                }
            }
        }

        stage (' ***** Deploy to PROD ***** ') {
            when {
                allOf {
                    anyOf{
                      expression {
                         params.deploytoProd == 'yes'
                    }
                }
                anyOf {
                    branch 'release/*'
                    tag pattern: "v.\\d{1,2\\}.\\d{1,2}.\\d{1,2}\\"
                }

                }
            }
            steps {
                timeout(time: 300, unit: 'SECONDS') {
                    input message: "Deploying to ${env.APPLICATION_NAME} to Production" , ok: 'yes', submitter: 'sivasre,i27academy'
                }
                    script {
                    dockerDeploy('prd', "${env.PROD_HOST_PORT}", "${env.contPort}").call()
                }
            }
        }
    }
}
    
}



// def buildApp() {
//     return {
//             echo "*****Building the Application *****"
//             sh "mvn clean package -DskipTest=true"
//             archiveArtifacts 'target/*.jar'        
//     }
// }

def imageValidation() {
    return {
        println(' *****Attempting to pulling the docker image***** ')
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.APPLICATON_NAME}:${GIT_COMMIT}"
            println('*****Docker Image Pulled successfully')
        }
        catch (Exception e){
            echo "OOOPPSSS!!! Docker Image with this tag is not found, so image is building now"
            // buildApp().call()
            dockerBuildAndPush().call()
        }

    }
}

def dockerBuildAndPush() {
    return {
        echo "****** Building Doker image *******"                    
        // sh "cp ${WORKSPACE}/target/i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} ./.cicd"
        sh "docker build --no-cache -t ${DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT} ./.cicd"
        sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        echo "****** Building Doker image *******" 
        sh "docker push ${DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"                    
       }        
}

def dockerDeploy(envDeploy, hostPort, contPort) {
    return {
                echo " ***** deploy to $envDeploy env ***** "
                withCredentials([usernamePassword(credentialsId: 'john_docker_vm_passwd', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        script {    
                            try {
                                sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no '$USERNAME'@$dev_ip \"docker stop ${env.APPLICATION_NAME}-$envDeploy \""
                                sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no '$USERNAME'@$dev_ip \"docker rm ${env.APPLICATION_NAME}-$envDeploy \""
                            }
                            catch(err){
                                echo "Error Caught: $err"                      
                            }  
                            sh "sshpass -p '$PASSWORD' -v ssh -o StrictHostKeyChecking=no '$USERNAME'@$dev_ip \"docker container run -dit -p $hostPort:$contPort --name ${env.APPLICATION_NAME}-$envDeploy ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}\""                    
                        }
                   }
    }
}
