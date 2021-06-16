#!/usr/bin/env groovy


def call(String projectName = 'color') {
    
    def pom = readMavenPom file: 'pom.xml'
	def version = pom.version.replace("SNAPSHOT","")
	def filePath = "src/main/resources/data/${projectName}-${version}update.db"

	 if (fileExists(filePath)) {
	     sh "mongo accretio-2-int.advyteam.com/${projectName} -u '${projectName}user' -p 'user${projectName}&2017' ${filePath}"
	 }
}