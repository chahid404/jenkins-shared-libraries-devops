#!/usr/bin/env groovy

def call(projectName, value){
node {
    properties([
        disableConcurrentBuilds(),
        buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '5', artifactNumToKeepStr: '1'))
    ])
    try {
        stage('checkout') {
            checkout scm
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
                stage('packaging') {
                    sh "./mvnw clean package -Pprod,prometheus,zipkin -Dobfuscated=${value} -DskipTests"
                    archiveArtifacts artifacts: '**/target/*.war', fingerprint: true
                }
            } finally {
                sh "chmod -R 777 ."
            }
        }
        if (env.BRANCH_NAME == "develop") {
            buildAndPushDocker('latest', projectName)
            if (projectName.contains("associative")) {
                integration("associative", projectName)
            }
            if (!projectName.contains("associative")) {
                integration("integration", projectName)
            }
        }
        if (env.BRANCH_NAME == "master") {
            def pom = readMavenPom file: 'pom.xml'
            buildAndPushDocker(pom.version, projectName)
        }
        if ((env.BRANCH_NAME.startsWith("release"))||(env.BRANCH_NAME.startsWith("hotfix"))) {
            def pom = readMavenPom file: 'pom.xml'
            def version = pom.version.replace("SNAPSHOT","RC")
            buildAndPushDocker(version, projectName)
        }
    } catch (e) {
        currentBuild.result = "FAILED"
        //notifyFailed()
        throw e
    } finally {
       deleteDir()
    }
}
}
def buildAndPushDocker(version, projectName) {
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

def integration(projectName) {
    stage('integration')
        withEnv([
            "DOCKER_TLS_VERIFY=1",
            "DOCKER_HOST=${env.DOCKER_ASSOCIATIVE_HOST}",
            "DOCKER_CERT_PATH=${env.SSL_CERT_PATH}"
        ]) {

        sh "docker service update \
            --with-registry-auth \
            --image accretio-hub.advyteam.com/${projectName}-associative \
            accretio_${projectName}"
    }
}

def integration(environment, projectName) {
    if (environment  == "integration" ) {
            stage('integration')
            withEnv([
                "DOCKER_TLS_VERIFY=1",
                "DOCKER_HOST=${env.DOCKER_INT_HOST}",
                "DOCKER_CERT_PATH=${env.SSL_CERT_PATH}"
            ]) {

            sh "docker service update \
                --with-registry-auth \
                --image accretio-hub.advyteam.com/${projectName} \
                accretio_${projectName}"
        }
    }

    if (environment  == "associative" ) {
            stage('integration')
            withEnv([
                "DOCKER_TLS_VERIFY=1",
                "DOCKER_HOST=${env.DOCKER_ASSOCIATIVE_HOST}",
                "DOCKER_CERT_PATH=${env.SSL_CERT_PATH}"
            ]) {
                
            
            def serviceName = "${projectName}".replace('-associative', '')
            sh "docker service update \
                --with-registry-auth \
                --image accretio-hub.advyteam.com/${projectName} \
                accretio_${serviceName}"
        }
    }    
}

def notifyFailed() {
   emailext (
       to: "${env.MAILING_LIST}",
       subject: "[JENKINS] [WARNING] ${env.JOB_NAME} [${env.BUILD_NUMBER}]",
       body: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':Check console output at ${env.BUILD_URL}",
    )
}
