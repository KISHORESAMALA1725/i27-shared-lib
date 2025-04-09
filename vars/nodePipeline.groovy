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
                    installDependencies()  // Method to install npm dependencies
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
                    runTests("${env.APPLICATION_NAME}")  // Method to run the tests
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
                    runSonarQubeAnalysis()  // Method to perform SonarQube scan
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
                    dockerBuildAndPush()  // Method to build and push Docker image
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
                    deployToEnv('dev', DEV_HOST_PORT)  // Method to deploy to dev
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
                    deployToEnv('test', TEST_HOST_PORT)  // Method to deploy to test
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
                    deployToEnv('stage', STAGE_HOST_PORT)  // Method to deploy to stage
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
                    deployToEnv('prod', PROD_HOST_PORT)  // Method to deploy to prod
                }
            }
        }
    }

    post {
        always {
            echo 'Pipeline completed!'
        }
        success {
            echo 'Build succeeded!'
        }
        failure {
            echo 'Build failed.'
        }
    }
}

def installDependencies() {
    echo "Installing dependencies using npm..."
    sh 'npm install'  // Install Node.js dependencies
}

def runTests() {
    echo "Running tests using npm..."
    sh 'npm test'  // Run tests (ensure you have a test script in package.json)
}

def runSonarQubeAnalysis() {
    echo "Running SonarQube analysis..."
    sh 'npm run sonar'  // Ensure you have a sonar script defined in your package.json
}

def dockerBuildAndPush() {
    echo "Building Docker image..."
    sh 'docker build -t ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT} .'  // Build Docker image
    echo "Logging into Docker Hub..."
    sh 'docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}'  // Login to Docker Hub
    echo "Pushing Docker image to Docker Hub..."
    sh 'docker push ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT}'  // Push Docker image to Docker Hub
}

def deployToEnv(env, hostPort) {
    echo "Deploying ${APPLICATION_NAME} to ${env} environment..."
    sh "docker run -d -p ${hostPort}:${CONT_PORT} --name ${APPLICATION_NAME}-${env} ${DOCKER_HUB}/${APPLICATION_NAME}:${GIT_COMMIT}"  // Deploy to environment
}
