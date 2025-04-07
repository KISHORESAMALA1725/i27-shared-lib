import com.i27academy.builds.Docker;

def call(Map pipelineParams) {
    Docker docker = new Docker(this) 
    pipeline {
        agent {
            label "k8s-slave"
        }

        tools {
            maven "maven-3.8.8"
            jdk "JDK-17"
        }

        parameters {
            choice(name: 'buildOnly', choices: 'no\nyes', description: 'MVN build application')
            choice(name: 'scanOnly', choices: 'no\nyes', description: 'SonarQube scan app')
            choice(name: 'dockerbuildandpush', choices: 'no\nyes', description: 'dockerbuildandpush')
            choice(name: 'deploytodev', choices: 'no\nyes', description: 'deploy to dev')
            choice(name: 'deploytotest', choices: 'no\nyes', description: 'deploy to test')
            choice(name: 'deploytostage', choices: 'no\nyes', description: 'deploy to stage')
            choice(name: 'deploytoprod', choices: 'no\nyes', description: 'deploytoprod')
        }

        environment {
            APPLICATION_NAME = "${pipelineParams.appName}"
            DEV_HOST_PORT = "${pipelineParams.devHostPort}"
            TEST_HOST_PORT = "${pipelineParams.testHostPort}"
            STAGE_HOST_PORT = "${pipelineParams.stageHostPort}"
            PROD_HOST_PORT = "${pipelineParams.prodHostPort}"
            CONT_PORT = "${env.CONT_PORT}"
            POM_VERSION = readMavenPom().getVersion()
            POM_PACKAGING = readMavenPom().getPackaging()
            DOCKER_HUB = 'docker.io/kishoresamala84'
            DOCKER_CREDS = credentials("kishoresamala84_docker_creds")
        }

        stages {
            stage('****** BUILD STAGE *****') {
                when {
                    expression {
                        params.buildOnly == 'yes'
                    }
                }
                steps {
                    script {
                        docker.buildApp("${env.APPLICATION_NAME}")
                    }
                }
            }

            stage('***** SONARQUBE STAGE *****') {
                steps {
                    script {
                        echo "***** SONARQUBE STAGE *****"
                        withSonarQubeEnv('sonarqube') {
                            sh """
                                mvn clean verify sonar:sonar \
                                -Dsonar.projectKey=i27-eureka2 \
                                -Dsonar.host.url=http://34.48.14.175:9000 \
                                -Dsonar.login=sqa_1770f1190375e8cf9d65df9b102c70d43ff4991b
                            """
                        }
                    }
                    timeout(time: 2, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('***** BUILD FORMAT *****') {
                steps {
                    script {
                        sh "echo SOURCE JAR file i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING}"
                        sh "echo TARGET JAR file i27-${env.APPLICATION_NAME}-${currentBuild.number}-${BRANCH_NAME}.${env.POM_PACKAGING}"
                    }
                }
            }

            stage('***** Docker-Build-Push *****') {
                when {
                    expression {
                        params.buildOnly == 'yes' && params.scanOnly == 'yes' && params.dockerbuildandpush == 'yes'
                    }
                }
                steps {
                    script {
                        dockerBuildPush()
                    }
                }
            }

            stage('***** Deploy to DEV-ENV *****') {
                when {
                    expression {
                        params.deployto'dev' == 'yes'
                    }
                }
                steps {
                    script {
                        deployToDocker('dev', "${env.DEV_HOST_PORT}", "${env.CONT_PORT}")
                    }
                }
            }

            stage('***** Deploy to TEST-ENV *****') {
                when {
                    expression {
                        params.deploytotest == 'yes'
                    }
                }
                steps {
                    script {
                        deployToDocker('test', "${evn.TEST_HOST_PORT}", "${env.CONT_PORT}")
                    }
                }
            }

            stage('***** Deploy to STAGE-ENV *****') {
                when {
                    expression {
                        params.deploytostage == 'yes'
                    }
                }
                steps {
                    script {
                        imageValidation()
                        deployToDocker('stage', "${env.STAGE_HOST_PORT}", "${env.CONT_PORT}")
                    }
                }
            }

            stage('***** Deploy to PROD-ENV *****') {
                when {
                    expression {
                        params.deploytoprod == 'yes'
                    }
                }
                steps {
                    script {
                        deployToDocker('prod', "${env.PROD_HOST_PORT}", "${env.CONT_PORT}")
                    }
                }
            }
        }
    }

    def buildApp() {
        echo "***** Building the Application *****"
        sh "mvn clean package -DskipTest=true"
        archiveArtifacts 'target/*.jar'
    }

    def dockerBuildPush() {
        echo "****** Building Docker image *******"
        sh "cp ${WORKSPACE}/target/i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} ./.cicd"
        sh "docker build --no-cache --build-arg JAR_SOURCE=i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} -t ${DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT} ./.cicd"
        withCredentials([usernamePassword(credentialsId: 'kishoresamala84_docker_creds', usernameVariable: 'DOCKER_CREDS_USR', passwordVariable: 'DOCKER_CREDS_PSW')]) {
            sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        }
        sh "docker push ${DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
    }

    def imageValidation() {
        echo '***** Attempting to pull the Docker image *****'
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
            echo '***** Docker Image Pulled successfully *****'
        } catch (Exception e) {
            echo "OOOPS!!! Docker Image with this tag not found, so building the image now..."
            buildApp()
            dockerBuildPush()
        }
    }

    def deployToDocker(envDeploy, hostPort, contPort) {
        echo "***** Deploying to $envDeploy environment *****"
        withCredentials([usernamePassword(credentialsId: 'john_docker_vm_passwd', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
            script {
                try {
                    sh "sshpass -p '$PASSWORD' ssh -o StrictHostKeyChecking=no '$USERNAME'@$dev_ip \"docker stop ${env.APPLICATION_NAME}-'$envDeploy'\""
                    sh "sshpass -p '$PASSWORD' ssh -o StrictHostKeyChecking=no '$USERNAME'@$dev_ip \"docker rm ${env.APPLICATION_NAME}-'$envDeploy'\""
                } catch (err) {
                    echo "Error Caught: $err"
                }
                sh "sshpass -p '$PASSWORD' ssh -o StrictHostKeyChecking=no '$USERNAME'@$dev_ip \"docker container run -dit -p $hostPort:$contPort --name ${env.APPLICATION_NAME}-'$envDeploy' ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}\""
            }
        }
    }

}


