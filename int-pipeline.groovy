/* Required parameters
 *
 * APP_NAME -
 * NAMESPACE_PREFIX -
 * DEV_TOKEN_SECRET -
 * DEV_OPENSHIFT_URL -
 *
 */
node('maven') {
    buildParam = null
    buildProject = "${NAMESPACE_PREFIX}-build"
    devProject = "${NAMESPACE_PREFIX}-dev"
    intProject = "${NAMESPACE_PREFIX}-int"
    testPod = "${APP_NAME}-test"
    testConfigMap = "${APP_NAME}-test-scripts"

    stage('Get Sources') {
        echo "## Login to dev cluster"
        withCredentials([string(credentialsId: DEV_TOKEN_SECRET, variable: 'DEV_TOKEN')]) {
            sh "oc login $DEV_OPENSHIFT_URL " +
               "--token=$DEV_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"
        }

        echo "## Read build parameters and template from last successful UAT build"
        sh "oc get configmap ${APP_NAME}-build-param -n ${devProject} " +
           "-o json --export >build-param.json"
        sh "oc get template ${APP_NAME}-deploy -n ${devProject} " +
           "-o json --export >deploy-template.json"
        sh "oc get template ${APP_NAME}-test -n ${devProject} " +
           "-o json --export >test-template.json"
        sh "oc get configmap ${APP_NAME}-test-scripts -n ${devProject} " +
           "-o json --export >test-scripts.json"
        buildParamConfigMap = readJSON file: 'build-param.json'
        buildParam = buildParamConfigMap.data
        writeYaml file: 'build-param.yaml', data: buildParam
    }

    stage('Deploy to integration') {
        echo "## Process deploy template to initiate deploy:"
        sh "oc project ${intProject}"
        sh "oc process -f deploy-template.json --ignore-unknown-parameters " +
           "--param-file=build-param.yaml " +
           "-p APP_NAME=${APP_NAME} " +
           "-p ENV=int " +
           "-p BUILD_NAMESPACE=${buildProject} " +
           "-p CPU_LIMIT=${buildParam.INT_CPU_LIMIT} " +
           "-p CPU_REQUEST=${buildParam.INT_CPU_REQUEST} " +
           "-p MEMORY_LIMIT=${buildParam.INT_MEMORY_LIMIT} " +
           "-p MEMORY_REQUEST=${buildParam.INT_MEMORY_REQUEST} " +
           "| oc apply -f -"

        echo "## Wait for deployment to complete:"
        sh "oc rollout status dc/${APP_NAME} -w"
    }

    stage('Test in integration') {
        // FIXME - Add rollback for test scripts or something of the sort
        echo "## Reset from previous testing:"
        sh "oc delete pod ${testPod} --ignore-not-found"
        sh "oc delete configmap ${testConfigMap} --ignore-not-found"

        echo "## Create ${testConfigMap} ConfigMap"
        sh "oc create -f test-scripts.json"

        echo "## Process test template to initiate tests:"
        getTestStatus = "oc get pod ${testPod} -o jsonpath='{.status.phase}'"
        sh "oc process -f test-template.yaml --ignore-unknown-parameters " +
           "--param-file=build-param.yaml " +
           "-p APP_NAME=${APP_NAME} " +
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
        sh "oc delete configmap ${APP_NAME}-build-param --ignore-not-found"
        sh "oc create -f build-param.json"

        echo "## Save deploy template in ${intProject} project:"
        sh "oc delete template ${APP_NAME}-deploy --ignore-not-found"
        sh "oc create -f deploy-template.json"

        echo "## Save deploy template in ${intProject} project:"
        sh "oc delete template ${APP_NAME}-test --ignore-not-found"
        sh "oc create -f test-template.json"
    }
}
