node('maven') {
    buildParam = null
    buildProject = null
    devProject = null
    testConfigMap = null
    testPod = null
    
    stage('Get Source from dev') {
        echo "## Read data from last successful run in dev"
    }
}
