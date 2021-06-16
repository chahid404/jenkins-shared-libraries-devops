#!/usr/bin/env groovy

def call(){

    def projectName 
    def obfuscated

    node {
        properties([
            disableConcurrentBuilds(),
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '5', artifactNumToKeepStr: '1'))
        ])
        try {
            stage('checkout') {
                checkout scm
                def pom = readMavenPom file: 'pom.xml'
                projectName = pom.artifactId
                obfuscated = pom.properties['obfuscated']
            }
            docker.image('openjdk:8').inside('-u root -v $HOME/.m2:/root/.m2') {
                try {
                    stage('check java') {
                        sh "java -version"
                    }
                    stage('clean') {
                        sh "chmod +x mvnw"
                        sh "./mvnw clean"
                    }
                    stage('backend tests') {
                        try {
                            sh "./mvnw test"
                        } finally {
                            junit '**/target/surefire-reports/TEST-*.xml'
                        }
                    }
                    stage('quality analysis') {
                        withSonarQubeEnv('sonar.advyteam') {
                            if (env.BRANCH_NAME == "develop") {
                                sh "./mvnw sonar:sonar"
                            } else if ((env.BRANCH_NAME == "master")||(env.BRANCH_NAME.startsWith("release"))) {
                                sh "./mvnw sonar:sonar -Dsonar.branch=${env.BRANCH_NAME}"
                            } else {
                                sh "./mvnw sonar:sonar -Dsonar.branch=FTR-${env.BRANCH_NAME}"
                            }
                        }
                    }
                    stage('backend tests - after obfuscation') {
                        sh "./mvnw prepare-package -Pprod -Dobfuscated=${obfuscated}"
                    }
                    stage('packaging') {
                        sh "./mvnw clean package -Pprod,prometheus,zipkin -Dobfuscated=${obfuscated} -DskipTests"
                        archiveArtifacts artifacts: '**/target/*.war', fingerprint: true
                    }
                } finally {
                    sh "chmod -R 777 ."
                }
            }
            def pom = readMavenPom file: 'pom.xml'
            if (env.BRANCH_NAME == "develop") {
                buildAndPushDocker(projectName, 'latest')
                if (projectName.endsWith("-ass")) {
                    integration(projectName, "${env.DOCKER_ASS_HOST}")
                } else {
                    integration(projectName, "${env.DOCKER_INT_HOST}")
                }
            }
            if (env.BRANCH_NAME == "master") {
                buildAndPushDocker(projectName, pom.version)
            }
            if ((env.BRANCH_NAME.startsWith("release"))||(env.BRANCH_NAME.startsWith("hotfix"))) {
                def version = pom.version.replace("SNAPSHOT","RC")
                buildAndPushDocker(projectName, version)
            }
        } catch (e) {
            currentBuild.result = "FAILED"
            notifyFailed()
            throw e
        } finally {
           deleteDir()
        }
    }
}
def buildAndPushDocker(projectName, version) {
    def dockerImage = "accretio-hub.advyteam.com/${projectName}:${version}"
    stage('build docker') {
        sh "cp -R src/main/docker target/"
        sh "cp target/*.war target/docker/"
        sh "docker build -t ${dockerImage} target/docker"
    }
    stage('publish docker') {
        sh "docker push ${dockerImage}"
    }
}

def integration(projectName, environment) {
    stage('integration')
        withEnv([
            "DOCKER_TLS_VERIFY=1",
            "DOCKER_HOST=${environment}",
            "DOCKER_CERT_PATH=${env.SSL_CERT_PATH}"
        ]) {
        sh "docker service update \
            --with-registry-auth \
            --image accretio-hub.advyteam.com/${projectName} \
            accretio_${projectName}"
    }
}

def notifyFailed() {
   emailext (
       to: "${env.MAILING_LIST}",
       subject: "[JENKINS] [WARNING] ${env.JOB_NAME} [${env.BUILD_NUMBER}]",
       body: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':Check console output at ${env.BUILD_URL}",
            )
}