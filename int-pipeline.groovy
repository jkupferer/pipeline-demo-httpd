/* Required parameters
 *
 * SERVICE_NAME -
 * DEV_TOKEN_SECRET -
 * DEV_OPENSHIFT_URL -
 *
 */
node('maven') {
    buildParam = null
    buildProject = "${SERVICE_NAME}-build"
    devProject = "${SERVICE_NAME}-dev"
    intProject = "${SERVICE_NAME}-int"
    testConfigMap = "${SERVICE_NAME}-test-scripts"
    testPod = "${SERVICE_NAME}-test"

    stage('Get Sources') {
        echo "## Login to dev cluster"
        withCredentials([string(credentialsId: DEV_TOKEN_SECRET, variable: 'DEV_TOKEN')]) {
            sh "oc login $DEV_OPENSHIFT_URL " +
               "--token=$DEV_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"
        }

        echo "## Read build parameters from last successful dev build"
        sh "oc get configmap ${SERVICE_NAME}-build-param -n ${devProject} " +
           "-o json --export >build-param.json"
        buildParamConfigMap = readJSON file: 'build-param.json'
        buildParam = buildParamConfigMap.data
        writeYaml file: 'build-param.yaml', data: buildParam

        echo "## Get pipeline build source"
        dir('src') {
            git url: buildParam.PIPELINE_BUILD_SOURCE, branch: buildParam.PIPELINE_BUILD_BRANCH
            //sh "git checkout ${buildParam.PIPELINE_BUILD_COMMIT}"
        }
    }

    stage('Deploy to integration') {
        echo "## Process deploy template to initiate deploy:"
        sh "oc project ${intProject}"
        sh "oc process -f src/deploy-template.yaml --ignore-unknown-parameters " +
           "--param-file=build-param.yaml " +
           "-p SERVICE_NAME=${SERVICE_NAME} " +
           "-p ENV=int " +
           "-p BUILD_NAMESPACE=${buildProject} " +
           "-p CPU_LIMIT=${buildParam.INT_CPU_LIMIT} " +
           "-p CPU_REQUEST=${buildParam.INT_CPU_REQUEST} " +
           "-p MEMORY_LIMIT=${buildParam.INT_MEMORY_LIMIT} " +
           "-p MEMORY_REQUEST=${buildParam.INT_MEMORY_REQUEST} " +
           "| oc apply -f -"

        echo "## Wait for deployment to complete:"
        sh "oc rollout status dc/${SERVICE_NAME} -w"
    }

    stage('Test in integration') {
        echo "## Reset from previous testing:"
        sh "oc delete pod ${testPod} --ignore-not-found"
        sh "oc delete configmap ${testConfigMap} --ignore-not-found"

        echo "## Create ${testConfigMap} ConfigMap"
        sh "oc create configmap ${testConfigMap} --from-file=src/test-scripts/"
        sh "oc label configmap ${testConfigMap} service=${SERVICE_NAME}"

        echo "## Process test template to initiate tests:"
        getTestStatus = "oc get pod ${testPod} -o jsonpath='{.status.phase}'"
        sh "oc process -f src/test-template.yaml --ignore-unknown-parameters " +
           "--param-file=build-param.yaml " +
           "-p SERVICE_NAME=${SERVICE_NAME} " +
           "-p ENV=int " +
           "| oc apply -f -"

        echo "## Wait for test Pod to start:"
        sh "while [[ 'Pending' = `${getTestStatus}` ]]; do sleep 10; done"

        echo "## Get test logs:"
        sh "oc logs -f ${testPod}"

        echo "## Check for test success:"
        sh "[[ 'Succeeded' = `${getTestStatus}` ]]"
    }

    stage('Record success in integration') {
        echo "## Save build parameters in ${intProject} project:"
        sh "oc delete configmap ${SERVICE_NAME}-build-param --ignore-not-found"
        sh "oc create -f build-param.json"

        echo "## Save deploy template in ${devProject} project:"
        deployTemplate = readYaml file: "src/deploy-template.yaml"
        deployTemplate.metadata.name = "${SERVICE_NAME}-deploy"
        writeYaml file: 'deploy-template.yaml', data: deployTemplate
        sh "oc apply -f deploy-template.yaml"
    }
}
