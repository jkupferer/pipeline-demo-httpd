/* Required parameters
 *
 * SERVICE_NAME -
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
    devBuildProject = "${SERVICE_NAME}-build"
    stageBuildProject = "${SERVICE_NAME}-build"
    intProject = "${SERVICE_NAME}-int"
    qaProject = "${SERVICE_NAME}-qa"
    testConfigMap = "${SERVICE_NAME}-test-scripts"
    testPod = "${SERVICE_NAME}-test"

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
            sh "oc get configmap build-param -n ${intProject} -o json --export >build-param.json"
            buildParamConfigMap = readJSON file: 'build-param.json'
            buildParam = buildParamConfigMap.data
            writeYaml file: 'build-param.yaml', data: buildParam

            //echo "## Get source registry hostname"
            //devClusterRegistry = sh (
            //    script: "oc get route -n default docker-registry -o jsonpath='{.spec.host}'",
            //    returnStdout: true
            //)

            echo "## Get pipeline build source"
            dir('src') {
                git url: buildParam.PIPELINE_BUILD_SOURCE, branch: buildParam.PIPELINE_BUILD_BRANCH
                //sh "git checkout ${buildParam.PIPELINE_BUILD_COMMIT}"
            }
        }

        stage('Promote image to stage') {
            echo "## Login to stageopenshift-master.libvirt cluster"
            sh "oc login $TEST_OPENSHIFT_URL " +
               "--token=$TEST_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"
        //    stageClusterRegistry = sh (
        //        script: "oc get route -n default docker-registry -o jsonpath='{.spec.host}'",
        //        returnStdout: true
        //    )
        //    sh "skopeo copy " +
        //       "--dest-creds=token:$TEST_TOKEN " +
        //       "--dest-cert-dir=/run/secrets/kubernetes.io/serviceaccount/ " +
        //       "--src-creds=token:$DEV_TOKEN " +
        //       "--src-cert-dir=/run/secrets/kubernetes.io/serviceaccount/ " +
        //       "docker://${devClusterRegistry}/${devBuildProject}/${SERVICE_NAME}:${buildParam.PIPELINE_BUILD_NUMBER} " +
        //       "docker://${stageClusterRegistry}/${stageBuildProject}/${SERVICE_NAME}:${buildParam.PIPELINE_BUILD_NUMBER}"
        }
    }

    try {
        stage('Deploy to qa') {
            echo "## Process deploy template to initiate deploy:"
            sh "oc project ${qaProject}"
            sh "oc process -f src/deploy-template.yaml --ignore-unknown-parameters " +
               "--param-file=build-param.yaml " +
               "-p ENV=qa " +
               "-p BUILD_NAMESPACE=${stageBuildProject} " +
               "-p CPU_LIMIT=${buildParam.QA_CPU_LIMIT} " +
               "-p CPU_REQUEST=${buildParam.QA_CPU_REQUEST} " +
               "-p MEMORY_LIMIT=${buildParam.QA_MEMORY_LIMIT} " +
               "-p MEMORY_REQUEST=${buildParam.QA_MEMORY_REQUEST} " +
               "| oc apply -f -"

            echo "## Wait for deployment to complete:"
            sh "oc rollout status dc/${SERVICE_NAME} -w"
        }

        stage('Test in qa') {
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
            sh "oc delete configmap build-param --ignore-not-found"
            sh "oc create -f build-param.json"

            echo "## Save deploy template in ${qaProject} project:"
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
               "-p ENV=qa " +
               "-p BUILD_NAMESPACE=${stageBuildProject} " +
               "-p CPU_LIMIT=${buildParam.QA_CPU_LIMIT} " +
               "-p CPU_REQUEST=${buildParam.QA_CPU_REQUEST} " +
               "-p MEMORY_LIMIT=${buildParam.QA_MEMORY_LIMIT} " +
               "-p MEMORY_REQUEST=${buildParam.QA_MEMORY_REQUEST} " +
               "| oc apply -f -"

            error("Rolled back...")
        }
    }
}
