import com.i27academy.builds.Node;

def call (Map pipelineParams) {
    Docker docker = new Docker(this)
    pipeline {
    agent {
        label 'k8s-slave'
    }

    parameters {
        choice(name: 'buildOnly', choices: 'no\nyes', description: 'This will run the build for Node.js app')
        choice(name: 'scanOnly',  choices: 'no\nyes', description: 'This will run the SonarQube scan')
        choice(name: 'dockerPush',choices: 'no\nyes', description: 'This will trigger build, SonarQube scan, image build & push to repo')
        choice(name: 'deploytoDev', choices: 'no\nyes', description: 'This will deploy to dev')
        choice(name: 'deploytoTest', choices: 'no\nyes', description: 'This will deploy to test')
        choice(name: 'deploytoStage', choices: 'no\nyes', description: 'This will deploy to stage')
        choice(name: 'deploytoProd', choices: 'no\nyes', description: 'This will deploy to prod')
    }

    tools {
        // Define Node.js version (ensure NodeJS plugin is installed on Jenkins)
        nodejs 'NodeJS-14'  // Ensure NodeJS-14 is configured in Jenkins tools
    }

    environment {
        APPLICATION_NAME = "${pipelineParams.appName}"
        DEV_HOST_PORT = "${pipelineParams.devHostPort}"
        TEST_HOST_PORT = "${pipelineParams.testHostPort}"
        STAGE_HOST_PORT = "${pipelineParams.stageHostPort}"
        PROD_HOST_PORT = "${pipelineParams.prodHostPort}"
        CONT_PORT = "${pipelineParams.contPort}"
        DOCKER_HUB = "docker.io/kishoresamala84"
        DOCKER_CREDS = credentials("kishoresamala84_docker_creds")
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm  // Checkout the source code from the repository
            }
        }

        stage('Install Dependencies') {
            when {
                expression { params.buildOnly == 'yes' }
            }
            steps {
                script {
                    echo "Installing Node.js dependencies..."
                    sh 'npm install'  // Install dependencies using npm
                }
            }
        }

        stage('Run Tests') {
            when {
                expression { params.buildOnly == 'yes' }
            }
            steps {
                script {
                    echo "Running tests..."
                    sh 'npm test'  // Run the tests (ensure you have test scripts set in package.json)
                }
            }
        }

        stage('SonarQube Analysis') {
            when {
                anyOf {
                    expression { params.buildOnly == 'yes' }
                    expression { params.scanOnly == 'yes' }
                    expression { params.dockerPush == 'yes' }
                }
            }
            steps {
                withSonarQubeEnv('sonarqube') {
                    echo "Running SonarQube scan..."
                    sh 'npm run sonar'  // Ensure you have a sonar script in package.json
                }
                timeout(time: 2, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Docker Build and Push') {
            when {
                expression { params.dockerPush == 'yes' }
            }
            steps {
                script {
                    echo "Building Docker image..."
                    sh 'docker build -t ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT} .'  // Build the Docker image
                    echo "Logging into Docker Hub..."
                    sh 'docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}'  // Login to Docker Hub
                    echo "Pushing Docker image to Docker Hub..."
                    sh 'docker push ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT}'  // Push image to Docker Hub
                }
            }
        }

        stage('Deploy to Dev') {
            when {
                expression { params.deploytoDev == 'yes' }
            }
            steps {
                script {
                    echo "Deploying to Dev environment..."
                    sh "docker run -d -p ${DEV_HOST_PORT}:${CONT_PORT} --name ${APPLICATION_NAME}-dev ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT}"
                }
            }
        }

        stage('Deploy to Test') {
            when {
                expression { params.deploytoTest == 'yes' }
            }
            steps {
                script {
                    echo "Deploying to Test environment..."
                    sh "docker run -d -p ${TEST_HOST_PORT}:${CONT_PORT} --name ${APPLICATION_NAME}-test ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT}"
                }
            }
        }

        stage('Deploy to Stage') {
            when {
                allOf {
                    expression { params.deploytoStage == 'yes' }
                    anyOf {
                        branch 'release/*'
                        tag pattern: "v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}", comparator: "REGEXP"
                    }
                }
            }
            steps {
                script {
                    echo "Deploying to Stage environment..."
                    sh "docker run -d -p ${STAGE_HOST_PORT}:${CONT_PORT} --name ${APPLICATION_NAME}-stage ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT}"
                }
            }
        }

        stage('Deploy to Prod') {
            when {
                allOf {
                    expression { params.deploytoProd == 'yes' }
                    anyOf {
                        branch 'release/*'
                        tag pattern: "v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}", comparator: "REGEXP"
                    }
                }
            }
            steps {
                script {
                    echo "Deploying to Prod environment..."
                    sh "docker run -d -p ${PROD_HOST_PORT}:${CONT_PORT} --name ${APPLICATION_NAME}-prod ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT}"
                }
            }
        }
    }

    post {
        always {
            // Actions to perform after all stages (cleanup, notifications, etc.)
            echo 'Pipeline completed!'
        }
        success {
            // Actions to perform if the build is successful
            echo 'Build succeeded!'
        }
        failure {
            // Actions to perform if the build fails
            echo 'Build failed.'
        }
    }
}
}