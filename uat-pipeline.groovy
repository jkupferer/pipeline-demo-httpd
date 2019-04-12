/* Required parameters
 *
 * APP_NAME -
 * NAMESPACE_PREFIX -
 * TEST_TOKEN_SECRET -
 * TEST_OPENSHIFT_URL -
 *
 */
node('maven') {
    buildParam = null
    buildProject = "${NAMESPACE_PREFIX}-build"
    qaProject = "${NAMESPACE_PREFIX}-qa"
    uatProject = "${NAMESPACE_PREFIX}-uat"
    testConfigMap = "${APP_NAME}-test-scripts"
    testPod = "${APP_NAME}-test"

    stage('Get Sources') {
        withCredentials([
            string(credentialsId: TEST_TOKEN_SECRET, variable: 'TEST_TOKEN')
        ]) {
            echo "## Login to dev cluster"
            sh "oc login $TEST_OPENSHIFT_URL " +
               "--token=$TEST_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"
        }

        echo "## Read build parameters from last successful qa build"
        sh "oc get configmap ${APP_NAME}-build-param -n ${qaProject} " +
           "-o json --export >build-param.json"
        sh "oc get template ${APP_NAME}-deploy -n ${qaProject} " +
           "-o json --export >deploy-template.json"
        sh "oc get template ${APP_NAME}-test -n ${qaProject} " +
           "-o json --export >test-template.json"
        sh "oc get configmap ${APP_NAME}-test-scripts -n ${qaProject} " +
           "-o json --export >test-scripts.json"
        buildParamConfigMap = readJSON file: 'build-param.json'
        buildParam = buildParamConfigMap.data
        writeYaml file: 'build-param.yaml', data: buildParam
    }

    try {
        stage('Deploy to uat') {
            echo "## Process deploy template to initiate deploy:"
            sh "oc project ${uatProject}"
            sh "oc process -f src/deploy-template.yaml --ignore-unknown-parameters " +
               "--param-file=build-param.yaml " +
               "-p APP_NAME=${APP_NAME} " +
               "-p ENV=uat " +
               "-p BUILD_NAMESPACE=${buildProject} " +
               "-p CPU_LIMIT=${buildParam.UAT_CPU_LIMIT} " +
               "-p CPU_REQUEST=${buildParam.UAT_CPU_REQUEST} " +
               "-p MEMORY_LIMIT=${buildParam.UAT_MEMORY_LIMIT} " +
               "-p MEMORY_REQUEST=${buildParam.UAT_MEMORY_REQUEST} " +
               "| oc apply -f -"

            echo "## Wait for deployment to complete:"
            sh "oc rollout status dc/${APP_NAME} -w"
        }

        stage('Test in uat') {
            echo "## Save previous test scripts
            sh "oc get configmap ${testConfigMap} -o json --export >test-scripts.json.save"

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
               "-p ENV=uat " +
               "| oc apply -f -"

            echo "## Wait for test Pod to start:"
            sh "while [[ 'Pending' = `${getTestStatus}` ]]; do sleep 10; done"

            echo "## Get test logs:"
            sh "oc logs -f ${testPod}"

            echo "## Check for test success:"
            sh "[[ 'Succeeded' = `${getTestStatus}` ]]"
        }

        stage('Record success in uat') {
            echo "## Save build parameters in ${uatProject} project:"
            sh "oc delete configmap build-param --ignore-not-found"
            sh "oc create -f build-param.json"

            echo "## Save deploy template in ${uatProject} project:"
            sh "oc delete template ${APP_NAME}-deploy --ignore-not-found"
            sh "oc create-f deploy-template.json"

            echo "## Save deploy template in ${qaProject} project:"
            sh "oc delete template ${APP_NAME}-test --ignore-not-found"
            sh "oc create -f test-template.json"
        }
    } catch(Exception ex) {
        stage('Roll back to previous build') {
            echo "## Get build-param from previous build"
            sh "oc get configmap build-param -o json --export >build-param-revert.json"
            buildParamConfigMap = readJSON file: 'build-param-revert.json'
            buildParam = buildParamConfigMap.data
            writeYaml file: 'build-param-revert.yaml', data: buildParam

            echo "## Revert deploy to previous build"
            sh "oc process deploy --ignore-unknown-parameters " +
               "--param-file=build-param-revert.yaml " +
               "-p ENV=uat " +
               "-p BUILD_NAMESPACE=${buildProject} " +
               "-p CPU_LIMIT=${buildParam.UAT_CPU_LIMIT} " +
               "-p CPU_REQUEST=${buildParam.UAT_CPU_REQUEST} " +
               "-p MEMORY_LIMIT=${buildParam.UAT_MEMORY_LIMIT} " +
               "-p MEMORY_REQUEST=${buildParam.UAT_MEMORY_REQUEST} " +
               "| oc apply -f -"

            echo "## Revert test scripts"
            sh "oc delete configmap ${testConfigMap} --ignore-not-found"
            sh "[[ ! -s test-scripts.json.save ]] || " +
               "oc create -f test-scripts.json.save"

            error("Rolled back...")
        }
    }
}
