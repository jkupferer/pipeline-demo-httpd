/* Required parameters
 *
 * SERVICE_NAME -
 * TEST_TOKEN_SECRET -
 * TEST_OPENSHIFT_URL -
 * PRD_TOKEN_SECRET - name of dev token jenkins credential
 * PRD_OPENSHIFT_URL -
 *
 */
node('maven') {
    buildParam = null
    stageClusterRegistry = null
    prodClusterRegistry = null
    stageBuildProject = "${SERVICE_NAME}-build"
    prodBuildProject = "${SERVICE_NAME}-build"
    uatProject = "${SERVICE_NAME}-uat"
    prodProject = "${SERVICE_NAME}-prd"

    withCredentials([
        string(credentialsId: TEST_TOKEN_SECRET, variable: 'TEST_TOKEN'),
        string(credentialsId: PRD_TOKEN_SECRET, variable: 'PRD_TOKEN')
    ]) {
        stage('Get Sources') {
            echo "## Login to stage cluster"
            sh "oc login $TEST_OPENSHIFT_URL " +
               "--token=$TEST_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"

            echo "## Read build parameters from last successful UAT build"
            sh "oc get configmap build-param -n ${uatProject} -o json --export >build-param.json"
            buildParamConfigMap = readJSON file: 'build-param.json'
            buildParam = buildParamConfigMap.data
            writeYaml file: 'build-param.yaml', data: buildParam

            //echo "## Get source registry hostname"
            //stageClusterRegistry = sh (
            //    script: "oc get route -n default docker-registry -o jsonpath='{.spec.host}'",
            //    returnStdout: true
            //)

            echo "## Get pipeline build source"
            dir('src') {
                git url: buildParam.PIPELINE_BUILD_SOURCE, branch: buildParam.PIPELINE_BUILD_BRANCH
                //sh "git checkout ${buildParam.PIPELINE_BUILD_COMMIT}"
            }
        }

        stage('Promote image to production') {
            echo "## Login to stageopenshift-master.libvirt cluster"
            sh "oc login $PRD_OPENSHIFT_URL " +
               "--token=$PRD_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"
        //    prodClusterRegistry = sh (
        //        script: "oc get route -n default docker-registry -o jsonpath='{.spec.host}'",
        //        returnStdout: true
        //    )
        //    sh "skopeo copy " +
        //       "--dest-creds=token:$PRD_TOKEN " +
        //       "--dest-cert-dir=/run/secrets/kubernetes.io/serviceaccount/ " +
        //       "--src-creds=token:$TEST_TOKEN " +
        //       "--src-cert-dir=/run/secrets/kubernetes.io/serviceaccount/ " +
        //       "docker://${stageClusterRegistry}/${stageBuildProject}/${SERVICE_NAME}:${buildParam.PIPELINE_BUILD_NUMBER} " +
        //       "docker://${prodClusterRegistry}/${prodBuildProject}/${SERVICE_NAME}:${buildParam.PIPELINE_BUILD_NUMBER}"
        }
    }

    stage('Deploy to production') {
        echo "## Process deploy template to initiate deploy:"
        sh "oc project ${prodProject}"
        sh "oc process -f src/deploy-template.yaml --ignore-unknown-parameters " +
           "--param-file=build-param.yaml " +
           "-p ENV=prd " +
           "-p BUILD_NAMESPACE=${prodBuildProject} " +
           "-p CPU_LIMIT=${buildParam.PRD_CPU_LIMIT} " +
           "-p CPU_REQUEST=${buildParam.PRD_CPU_REQUEST} " +
           "-p MEMORY_LIMIT=${buildParam.PRD_MEMORY_LIMIT} " +
           "-p MEMORY_REQUEST=${buildParam.PRD_MEMORY_REQUEST} " +
           "| oc apply -f -"

        echo "## Wait for deployment to complete:"
        sh "oc rollout status dc/${SERVICE_NAME} -w"
    }

    stage('Record production deployment') {
        echo "## Save build parameters in ${prodProject} project:"
        sh "oc delete configmap build-param --ignore-not-found"
        sh "oc create -f build-param.json"

        echo "## Save deploy template in ${prodProject} project:"
        sh "oc apply -f src/deploy-template.yaml"
    }
}
