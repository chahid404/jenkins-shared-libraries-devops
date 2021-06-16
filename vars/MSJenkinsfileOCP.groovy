#!/usr/bin/env groovy

def call(){
	node('maven') { 
		properties([
			disableConcurrentBuilds(),
			buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '5', artifactNumToKeepStr: '1'))
		])
		try {

			def projectName 
			def obfuscated
			def multitenancy
			def password

			stage('Checkout from Gitlab') {

				checkout scm
				def pom = readMavenPom file: 'pom.xml'
				projectName = pom.artifactId
				obfuscated = pom.properties['obfuscated']
				multitenancy = pom.properties['multitenancy']
			}

			container('jnlp'){

				stage('Checking Java'){
					sh "java -version"
				}

				stage('Cleaning workspace'){
					sh "chmod +x mvnw"
					sh "./mvnw clean"
				}

				stage('Backend tests') {
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

				stage('Packaging'){
					sh "./mvnw clean package -Pprod,prometheus,zipkin -Dobfuscated=${obfuscated} -DskipTests"
					archiveArtifacts artifacts: '**/target/*.war', fingerprint: true
				}

				password = sh (
					script: "oc whoami -t",
					returnStdout: true
				).trim()
			}

			container('docker'){
				stage('Containerization'){
					def dockerImage
					authentication('jenkins', password)
					if (env.BRANCH_NAME == "develop") {
						dockerImage = "docker-registry.accretio.io/accretio/${projectName}:latest"
						buildAndPushDocker(dockerImage)
					}
					if (env.BRANCH_NAME == "master") {
						def pom = readMavenPom file: 'pom.xml'
						dockerImage = "docker-registry.accretio.io/accretio/${projectName}:${pom.version}"
						buildAndPushDocker(dockerImage)
					}
					if ((env.BRANCH_NAME.startsWith("release"))||(env.BRANCH_NAME.startsWith("hotfix"))) {
						def pom = readMavenPom file: 'pom.xml'
						def version = pom.version.replace("SNAPSHOT","RC")
						dockerImage = "docker-registry.accretio.io/accretio/${projectName}:${version}"
						buildAndPushDocker(dockerImage)
					}
					if (multitenancy) {
						dockerImage = "docker-registry.accretio.io/accretio-mt/${projectName}:latest"
						buildAndPushDocker(dockerImage)
					}
				}
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

def buildAndPushDocker(dockerImage) {
    sh "cp -R src/main/docker target/"
    sh "cp target/*.war target/docker/"
    sh "docker build -t ${dockerImage} target/docker"
    sh "docker push ${dockerImage}"
}

def authentication(username, password){
    sh "docker login -u ${username} -p ${password} docker-registry.accretio.io"
}
