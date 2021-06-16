def call() {

    pipeline {
        agent {
            kubernetes {
                yaml libraryResource('maven-template.yaml')
            }
        }

        options {
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '5', artifactNumToKeepStr: '1'))
            timeout(time: 1, unit: 'HOURS')
            disableConcurrentBuilds() 
            skipStagesAfterUnstable()
        }

        environment {
            ARTIFACT = readMavenPom().getArtifactId()
            TEAMLEAD = readMavenPom().getProperties().getProperty('projectManager')
            VERSION = readMavenPom().getVersion()
            USER_CREDENTIALS= credentials('jenkins-user')
        }    
        
        stages {
                    
            stage('Check java') {
                steps {
                    container('jnlp') {
                        sh 'java -version'
                    }
                }
            }

            stage('clean') {
                steps {
                    container('maven') {
                        sh "chmod +x mvnw"
                        sh "./mvnw clean"
                    }
                }
            }

            stage('packaging') {
                steps {
                    container('maven') {
                        sh "./mvnw clean package -Pprod -Dobfuscated=false -DskipTests"
                    }
                }
            }
        
            stage('Build docker') {
                when {
                    branch 'delfingen-sso'
                }
                steps {
                    container('docker') {
                        sh '''
                            cp -R src/main/docker target/
                            cp target/*.war target/*.jar target/docker/ || true
                            docker build -t registry.accretio.io/${ARTIFACT}:${BRANCH_NAME} target/docker
                        '''
                    }
                }
            }

            stage('Push docker') {
                when {
                    branch 'delfingen-sso'
                }
                steps {
                    container('docker') {
                        retry (5) {
                            sh '''
                                docker login -u $USER_CREDENTIALS_USR -p $USER_CREDENTIALS_PSW registry.accretio.io
                                docker push registry.accretio.io/${ARTIFACT}:${BRANCH_NAME}
                            '''
                        }
                    }
                }
            } 
            
            stage('Build docker jib') {
                when {
                    branch 'migration-sb-2'
                }
                steps {
                    container('maven') {
                        retry (5) {
                            sh '''
                                ./mvnw jib:build -Djib.to.image=registry.accretio.io/${ARTIFACT}:${BRANCH_NAME}
                            '''
                        }
                    }
                }
            }            
            
            stage('Deploy to k8s') {
                when {
                    anyOf {
                        branch 'delfingen-sso'
                        branch 'migration-sb-2'
                    }
                }
                steps {
                    container('kubectl') {
                        sh '''
                            kubectl -n ${BRANCH_NAME} patch deployment ${ARTIFACT} -p \
                                \"{\\"spec\\":{\\"template\\":{\\"metadata\\":{\\"labels\\":{\\"date\\":\\"`date +\'%s\'`\\"}}}}}\"
                            kubectl -n ${BRANCH_NAME} rollout status deployment ${ARTIFACT} --timeout=5m
                        '''
                    }
                }
            }
        }
        
        post {
            failure {
                sh 'git log --format="%ae" | head -1 > commit-author.txt'   
                echo "committer: "+ readFile('commit-author.txt').trim()
                mail cc:"${TEAMLEAD}" , body: "<br>Build tag : ${env.BUILD_TAG}<br>Build Status: ${currentBuild.result}<br>Build Url: ${env.BUILD_URL}<br>triggered by: ${readFile('commit-author.txt').trim()}<br><br>We're Sorry the build failed :( , Goodluck next time ",
                charset: 'UTF-8', from: '', mimeType: 'text/html', replyTo: '', subject: "Jenkins Build Number ${env.BUILD_NUMBER} for: ${env.JOB_NAME}", to: readFile('commit-author.txt').trim()
            }
            unstable {
                echo 'This Build is unstable'
            }
        }
    }
}