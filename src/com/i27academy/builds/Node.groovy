package com.i27academy.builds;

class Node {
    def jenkins
    Node(jenkins) {
        this.jenkins = jenkins
    }

    def runTests(appName) {
        jenkins.sh """
            echo "Installing Node.js dependencies..."
            sh 'npm install'  // Install dependencies using npm
          """
        }
}