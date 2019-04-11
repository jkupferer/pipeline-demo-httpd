/* Required parameters
 *
 * SERVICE_NAME -
 * TEST_TOKEN_SECRET -
 * TEST_OPENSHIFT_URL -
 *
 */
node('maven') {
    buildParam = null
    buildProject = "${SERVICE_NAME}-build"
    qaProject = "${SERVICE_NAME}-qa"
    uatProject = "${SERVICE_NAME}-uat"
    testConfigMap = "${SERVICE_NAME}-test-scripts"
    testPod = "${SERVICE_NAME}-test"

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
        sh "oc get configmap build-param -n ${qaProject} -o json --export >build-param.json"
        buildParamConfigMap = readJSON file: 'build-param.json'
        buildParam = buildParamConfigMap.data
        writeYaml file: 'build-param.yaml', data: buildParam

        echo "## Get pipeline build source"
        dir('src') {
            git url: buildParam.PIPELINE_BUILD_SOURCE, branch: buildParam.PIPELINE_BUILD_BRANCH
            //sh "git checkout ${buildParam.PIPELINE_BUILD_COMMIT}"
        }
    }

    try {
        stage('Deploy to uat') {
            echo "## Process deploy template to initiate deploy:"
            sh "oc project ${uatProject}"
            sh "oc process -f src/deploy-template.yaml --ignore-unknown-parameters " +
               "--param-file=build-param.yaml " +
               "-p ENV=uat " +
               "-p BUILD_NAMESPACE=${buildProject} " +
               "-p CPU_LIMIT=${buildParam.UAT_CPU_LIMIT} " +
               "-p CPU_REQUEST=${buildParam.UAT_CPU_REQUEST} " +
               "-p MEMORY_LIMIT=${buildParam.UAT_MEMORY_LIMIT} " +
               "-p MEMORY_REQUEST=${buildParam.UAT_MEMORY_REQUEST} " +
               "| oc apply -f -"

            echo "## Wait for deployment to complete:"
            sh "oc rollout status dc/${SERVICE_NAME} -w"
        }

        stage('Test in uat') {
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
            sh "oc apply -f src/deploy-template.yaml"
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

            error("Rolled back...")
        }
    }
}
