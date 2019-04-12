/* Required parameters
 *
 * APP_NAME -
 * NAMESPACE_PREFIX -
 * DEV_TOKEN_SECRET - name of dev token jenkins credential
 * DEV_OPENSHIFT_URL -
 * TEST_TOKEN_SECRET -
 * TEST_OPENSHIFT_URL -
 *
 */
node('maven') {
    buildParam = null
    devClusterRegistry = null
    stageClusterRegistry = null
    devBuildProject = "${NAMESPACE_PREFIX}-build"
    stageBuildProject = "${NAMESPACE_PREFIX}-build"
    intProject = "${NAMESPACE_PREFIX}-int"
    qaProject = "${NAMESPACE_PREFIX}-qa"
    testConfigMap = "${APP_NAME}-test-scripts"
    testPod = "${APP_NAME}-test"

    withCredentials([
        string(credentialsId: DEV_TOKEN_SECRET, variable: 'DEV_TOKEN'),
        string(credentialsId: TEST_TOKEN_SECRET, variable: 'TEST_TOKEN')
    ]) {
        stage('Get Sources') {
            echo "## Login to dev cluster"
            sh "oc login $DEV_OPENSHIFT_URL " +
               "--token=$DEV_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"

            echo "## Read build parameters from last successful integration build"
            sh "oc get configmap ${APP_NAME}-build-param -n ${intProject} " +
               "-o json --export >build-param.json"
            sh "oc get template ${APP_NAME}-deploy -n ${intProject} " +
               "-o json --export >deploy-template.json"
            sh "oc get template ${APP_NAME}-test -n ${intProject} " +
               "-o json --export >test-template.json"
            sh "oc get configmap ${APP_NAME}-test-scripts -n ${intProject} " +
               "-o json --export >test-scripts.json"
            buildParamConfigMap = readJSON file: 'build-param.json'
            buildParam = buildParamConfigMap.data
            writeYaml file: 'build-param.yaml', data: buildParam

            //echo "## Get source registry hostname"
            //devClusterRegistry = sh (
            //    script: "oc get route -n default docker-registry -o jsonpath='{.spec.host}'",
            //    returnStdout: true
            //)
        }

        stage('Login for test cluster') {
            echo "## Login to test cluster"
            sh "oc login $TEST_OPENSHIFT_URL " +
               "--token=$TEST_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"
        }

        //stage('Promote image to test cluster') {
        //    stageClusterRegistry = sh (
        //        script: "oc get route -n default docker-registry -o jsonpath='{.spec.host}'",
        //        returnStdout: true
        //    )
        //    sh "skopeo copy " +
        //       "--dest-creds=token:$TEST_TOKEN " +
        //       "--dest-cert-dir=/run/secrets/kubernetes.io/serviceaccount/ " +
        //       "--src-creds=token:$DEV_TOKEN " +
        //       "--src-cert-dir=/run/secrets/kubernetes.io/serviceaccount/ " +
        //       "docker://${devClusterRegistry}/${devBuildProject}/${APP_NAME}:${buildParam.PIPELINE_BUILD_NUMBER} " +
        //       "docker://${stageClusterRegistry}/${stageBuildProject}/${APP_NAME}:${buildParam.PIPELINE_BUILD_NUMBER}"
        //}
    }

    try {
        stage('Deploy to qa') {
            echo "## Process deploy template to initiate deploy:"
            sh "oc project ${qaProject}"
            sh "oc process -f deploy-template.json --ignore-unknown-parameters " +
               "--param-file=build-param.yaml " +
               "-p APP_NAME=${APP_NAME} " +
               "-p ENV=qa " +
               "-p BUILD_NAMESPACE=${stageBuildProject} " +
               "-p CPU_LIMIT=${buildParam.QA_CPU_LIMIT} " +
               "-p CPU_REQUEST=${buildParam.QA_CPU_REQUEST} " +
               "-p MEMORY_LIMIT=${buildParam.QA_MEMORY_LIMIT} " +
               "-p MEMORY_REQUEST=${buildParam.QA_MEMORY_REQUEST} " +
               "| oc apply -f -"

            echo "## Wait for deployment to complete:"
            sh "oc rollout status dc/${APP_NAME} -w"
        }

        stage('Test in qa') {
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
               "-p ENV=qa " +
               "| oc apply -f -"

            echo "## Wait for test Pod to start:"
            sh "while [[ 'Pending' = `${getTestStatus}` ]]; do sleep 10; done"

            echo "## Get test logs:"
            sh "oc logs -f ${testPod}"

            echo "## Check for test success:"
            sh "[[ 'Succeeded' = `${getTestStatus}` ]]"
        }

        stage('Record success in qa') {
            echo "## Save build parameters in ${qaProject} project:"
            sh "oc delete configmap ${APP_NAME}-build-param --ignore-not-found"
            sh "oc create -f build-param.json"

            echo "## Save deploy template in ${qaProject} project:"
            sh "oc delete template ${APP_NAME}-deploy --ignore-not-found"
            sh "oc create-f deploy-template.json"

            echo "## Save deploy template in ${qaProject} project:"
            sh "oc delete template ${APP_NAME}-test --ignore-not-found"
            sh "oc create -f test-template.json"
        }
    } catch(Exception ex) {
        stage('Roll back to previous build') {
            echo "## Get build-param from previous build"
            sh "oc get configmap ${APP_NAME}-build-param -o json --export >build-param-revert.json"
            buildParamConfigMap = readJSON file: 'build-param-revert.json'
            buildParam = buildParamConfigMap.data
            writeYaml file: 'build-param-revert.yaml', data: buildParam

            echo "## Revert deploy to previous build"
            sh "oc process deploy --ignore-unknown-parameters " +
               "--param-file=build-param-revert.yaml " +
               "-p APP_NAME=${APP_NAME} " +
               "-p ENV=qa " +
               "-p BUILD_NAMESPACE=${stageBuildProject} " +
               "-p CPU_LIMIT=${buildParam.QA_CPU_LIMIT} " +
               "-p CPU_REQUEST=${buildParam.QA_CPU_REQUEST} " +
               "-p MEMORY_LIMIT=${buildParam.QA_MEMORY_LIMIT} " +
               "-p MEMORY_REQUEST=${buildParam.QA_MEMORY_REQUEST} " +
               "| oc apply -f -"

            echo "## Revert test scripts"
            sh "oc delete configmap ${testConfigMap} --ignore-not-found"
            sh "[[ ! -s test-scripts.json.save ]] || " +
               "oc create -f test-scripts.json.save"

            error("Rolled back...")
        }
    }
}
