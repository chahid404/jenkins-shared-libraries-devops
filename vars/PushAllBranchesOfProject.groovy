#!/usr/bin/env groovy

def call(String projectName = 'color') {
	def repo
	withCredentials([usernamePassword(credentialsId: 'docker-cred', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
		repo = "http://${GIT_USERNAME}:${GIT_PASSWORD}@gitlab.advyteam.com/test/${projectName}.git"
		sh "git push ${repo} master"
		sh "git push ${repo} develop"
		sh "git push ${repo} --tags"
	}
}