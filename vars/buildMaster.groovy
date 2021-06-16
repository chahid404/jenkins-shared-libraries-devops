#!/usr/bin/env groovy

def call(String projectName = 'color') {
    sh "cp -R src/main/docker target/"
    sh "cp target/*.jar target/docker/"
    def dockerImage = "accretio-hub.advyteam.com/${projectName}:latest"
    def repo
		withCredentials([usernamePassword(credentialsId: 'docker-cred', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
		  repo = "http://${GIT_USERNAME}:${GIT_PASSWORD}@gitlab.advyteam.com/test/${projectName}.git"
	      sh "docker build -t ${dockerImage} target/docker"
		}
		sh "docker push ${dockerImage}"
	}