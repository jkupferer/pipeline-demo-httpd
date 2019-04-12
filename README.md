# OpenShift Application Pipeline Demo - httpd

## Prerequisites

In order to run this pipeline demo you will need an OpenShift cluster with
user access. `oc` commands below assume that you are already logged into the
target cluster such as with `oc login`.

## Environment Setup

Clone git repository and change directory into it.
```
git clone https://github.com/jkupferer/pipeline-demo-httpd
cd pipeline-demo-httpd
```

Set variables for your cluster and demo application:
```
APP_NAME="example"
NAMESPACE_PREFIX="${APP_NAME}"
```

Windows command line users may use `%APP_NAME%` variable syntax in the
usage examples below.

Create sandbox and pipeline namespaces:
```
oc new-project ${NAMESPACE_PREFIX}-sandbox
oc new-project ${NAMESPACE_PREFIX}-build
oc new-project ${NAMESPACE_PREFIX}-dev
oc new-project ${NAMESPACE_PREFIX}-int
oc new-project ${NAMESPACE_PREFIX}-qa
oc new-project ${NAMESPACE_PREFIX}-uat
oc new-project ${NAMESPACE_PREFIX}-prd
```

Create service accounts:
```
oc create sa -n ${NAMESPACE_PREFIX}-build example-dev
oc create sa -n ${NAMESPACE_PREFIX}-build example-test
oc create sa -n ${NAMESPACE_PREFIX}-build example-prd
```

Grant access to all namespaces to pull from build namespace:
```
oc policy add-role-to-group -n ${NAMESPACE_PREFIX}-build system:image-puller system:serviceaccounts:${NAMESPACE_PREFIX}-dev
oc policy add-role-to-group -n ${NAMESPACE_PREFIX}-build system:image-puller system:serviceaccounts:${NAMESPACE_PREFIX}-int
oc policy add-role-to-group -n ${NAMESPACE_PREFIX}-build system:image-puller system:serviceaccounts:${NAMESPACE_PREFIX}-qa
oc policy add-role-to-group -n ${NAMESPACE_PREFIX}-build system:image-puller system:serviceaccounts:${NAMESPACE_PREFIX}-uat
oc policy add-role-to-group -n ${NAMESPACE_PREFIX}-build system:image-puller system:serviceaccounts:${NAMESPACE_PREFIX}-prd
```

Grant dev pipeline access to dev projects:
```
oc policy add-role-to-user -n ${NAMESPACE_PREFIX}-build edit system:serviceaccount:${NAMESPACE_PREFIX}-build:example-dev
oc policy add-role-to-user -n ${NAMESPACE_PREFIX}-dev edit system:serviceaccount:${NAMESPACE_PREFIX}-build:example-dev
oc policy add-role-to-user -n ${NAMESPACE_PREFIX}-int edit system:serviceaccount:${NAMESPACE_PREFIX}-build:example-dev
```

Grant test pipeline access to dev projects:
```
oc policy add-role-to-user -n ${NAMESPACE_PREFIX}-qa edit system:serviceaccount:${NAMESPACE_PREFIX}-build:${APP_NAME}-test
oc policy add-role-to-user -n ${NAMESPACE_PREFIX}-uat edit system:serviceaccount:${NAMESPACE_PREFIX}-build:${APP_NAME}-test
```

Grant production pipeline access to prd projects:
```
oc policy add-role-to-user -n ${NAMESPACE_PREFIX}-prd edit system:serviceaccount:${NAMESPACE_PREFIX}-build:${APP_NAME}-prd
```

Deploy persistent jenkins:
```
oc new-app -n ${NAMESPACE_PREFIX}-build jenkins-persistent
```

## Sandbox Development

Switch to sandbox namespace:
```
oc project ${NAMESPACE_PREFIX}-sandbox
```

Update build config in sandbox:
```
oc process -f build-template.yaml \
 -p APP_NAME=${APP_NAME} \
| oc apply -f -
```

Test bulid config in sandbox:
```
oc start-build example --from-dir=.
```

Perform deployment in sandbox:
```
oc process -f deploy-template.yaml \
 -p BUILD_NAMESPACE=${NAMESPACE_PREFIX}-sandbox \
 -p APP_NAME=${APP_NAME} \
| oc apply -f -
```

Get route for sandbox deployment:
```
oc get route
```

Test with web browser or `curl`.

Set `APP_URL` based on route output above and run test scripts:
```
APP_URL="http://..."
sh 01-check-content.sh
```

Run containerized testing:
```
oc create configmap ${APP_NAME}-test-scripts --from-file=test-scripts/
oc process -f test-template.yaml -p APP_NAME="${APP_NAME}" | oc create -f -
```

Check test pod for containerized test results:
```
oc get pod 
```

Test cleanup:
```
oc delete configmap ${APP_NAME}-test-scripts
oc delete pod ${APP_NAME}-test
```

## Jenkins Pipeline

Get the hostname of the Jenkins server in your build namespace:

```
oc get route -n ${NAMESPACE_PREFIX}-build
```

Log into the Jenkins server with a web browser using the hostname shown.

Get tokens

```
oc sa get-token -n ${NAMESPACE_PREFIX}-build example-dev
oc sa get-token -n ${NAMESPACE_PREFIX}-build example-test
oc sa get-token -n ${NAMESPACE_PREFIX}-build example-prd
```

Store these service accounts tokens in Jenkins as credentials
(https://jenkins.io/doc/book/using/using-credentials/).  Store as global
credentials of type secret text. Set credentials id as "example-dev",
"example-test", and "example-prd".

### Create dev pipeline

Create new job (New item), "example-dev", of type Pipeline. Click OK.

Select "This project is parameterized and add parameters:

* `ARTIFACT_URL` = String parameter, default to "https://raw.githubusercontent.com/jkupferer/pipeline-demo-httpd/master/artifacts/artifact-0.1.zip"
* `APP_NAME` = String parameter, default to same value as APP\_NAME
* `NAMESPACE_PREFIX` = String parameter, default to same value as NAMESPACE\_PREFIX
* `DEV_OPENSHIFT_URL` = String parameter, default to url of cluster API (`oc whoami --show-server`)
* `DEV_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-dev"

For Pipeline Definition, select "Pipeline script from SCM".

* SCM - "Git"
* Repository URL - "https://github.com/jkupferer/pipeline-demo-httpd.git"
* Script Path - "dev-pipeline.groovy"

Test run with defaults. Expect it to fail in stage "Test in dev".

Test again with `ARTIFACT_URL` "https://raw.githubusercontent.com/jkupferer/pipeline-demo-httpd/master/artifacts/artifact-0.2.zip"

### Create integration pipeline

Create new job (New item), "example-int", of type Pipeline. Click OK.

Select "This project is parameterized and add parameters:

* `APP_NAME` = String parameter, default to same value as APP\_NAME
* `NAMESPACE_PREFIX` = String parameter, default to same value as NAMESPACE\_PREFIX
* `DEV_OPENSHIFT_URL` = String parameter, default to url of cluster API (`oc whoami --show-server`)
* `DEV_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-dev"

For Pipeline Definition, select "Pipeline script from SCM".

* SCM - "Git"
* Repository URL - "https://github.com/jkupferer/pipeline-demo-httpd.git"
* Script Path - "int-pipeline.groovy"

### Create qa pipeline

Create new job (New item), "example-qa", of type Pipeline. Click OK.

Select "This project is parameterized and add parameters:

* `APP_NAME` = String parameter, default to same value as APP\_NAME
* `NAMESPACE_PREFIX` = String parameter, default to same value as NAMESPACE\_PREFIX
* `DEV_OPENSHIFT_URL` = String parameter, default to url of cluster API (`oc whoami --show-server`)
* `DEV_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-dev"
* `TEST_OPENSHIFT_URL` = String parameter, default to url of cluster API (`oc whoami --show-server`)
* `TEST_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-test"

(Note: `TEST_OPENSHIFT_URL` and `TEST_TOKEN_SECRET` can point to a different
cluster to test cross-cluster deployment. This will require enabling skopeo
image promotion between clusters).

For Pipeline Definition, select "Pipeline script from SCM".

* SCM - "Git"
* Repository URL - "https://github.com/jkupferer/pipeline-demo-httpd.git"
* Script Path - "qa-pipeline.groovy"

### Create uat pipeline

Create new job (New item), "example-uat", of type Pipeline. Click OK.

Select "This project is parameterized and add parameters:

* `APP_NAME` = String parameter, default to same value as APP\_NAME
* `NAMESPACE_PREFIX` = String parameter, default to same value as NAMESPACE\_PREFIX
* `TEST_OPENSHIFT_URL` = String parameter, default to url of cluster API (`oc whoami --show-server`)
* `TEST_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-test"

For Pipeline Definition, select "Pipeline script from SCM".

* SCM - "Git"
* Repository URL - "https://github.com/jkupferer/pipeline-demo-httpd.git"
* Script Path - "uat-pipeline.groovy"

### Create prod pipeline

Create new job (New item), "example-prd", of type Pipeline. Click OK.

Select "This project is parameterized and add parameters:

* `APP_NAME` = String parameter, default to same value as APP\_NAME
* `NAMESPACE_PREFIX` = String parameter, default to same value as NAMESPACE\_PREFIX
* `TEST_OPENSHIFT_URL` = String parameter, default to url of cluster API (`oc whoami --show-server`)
* `TEST_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-test"
* `PRD_OPENSHIFT_URL` = String parameter, default to url of cluster API (`oc whoami --show-server`)
* `PRD_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-test"

For Pipeline Definition, select "Pipeline script from SCM".

* SCM - "Git"
* Repository URL - "https://github.com/jkupferer/pipeline-demo-httpd.git"
* Script Path - "prod-pipeline.groovy"
