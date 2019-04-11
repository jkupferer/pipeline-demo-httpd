# OpenShift Application Pipeline Demo - httpd

## Environment Setup
```
SERVICE_NAME=example
oc new-project $SERVICE_NAME-sandbox
oc new-project $SERVICE_NAME-build
oc new-project $SERVICE_NAME-dev
oc new-project $SERVICE_NAME-int
oc new-project $SERVICE_NAME-qa
oc new-project $SERVICE_NAME-uat
oc new-project $SERVICE_NAME-prd
oc create sa -n $SERVICE_NAME-build example-dev
oc create sa -n $SERVICE_NAME-build example-test
oc create sa -n $SERVICE_NAME-build example-prd
```

Grant access to all namespaces to pull from build namespace:
```
oc policy add-role-to-group -n $SERVICE_NAME-build system:image-puller system:serviceaccounts:$SERVICE_NAME-dev
oc policy add-role-to-group -n $SERVICE_NAME-build system:image-puller system:serviceaccounts:$SERVICE_NAME-int
oc policy add-role-to-group -n $SERVICE_NAME-build system:image-puller system:serviceaccounts:$SERVICE_NAME-qa
oc policy add-role-to-group -n $SERVICE_NAME-build system:image-puller system:serviceaccounts:$SERVICE_NAME-uat
oc policy add-role-to-group -n $SERVICE_NAME-build system:image-puller system:serviceaccounts:$SERVICE_NAME-prd
```

Grant dev pipeline access to dev projects:
```
oc policy add-role-to-user -n $SERVICE_NAME-build edit system:serviceaccount:$SERVICE_NAME-build:$SERVICE_NAME-dev
oc policy add-role-to-user -n $SERVICE_NAME-dev edit system:serviceaccount:$SERVICE_NAME-build:$SERVICE_NAME-dev
oc policy add-role-to-user -n $SERVICE_NAME-int edit system:serviceaccount:$SERVICE_NAME-build:$SERVICE_NAME-dev
```

Grant test pipeline access to dev projects:
```
oc policy add-role-to-user -n $SERVICE_NAME-qa edit system:serviceaccount:$SERVICE_NAME-build:$SERVICE_NAME-test
oc policy add-role-to-user -n $SERVICE_NAME-uat edit system:serviceaccount:$SERVICE_NAME-build:$SERVICE_NAME-test
```

Grant production pipeline access to prd projects:
```
oc policy add-role-to-user -n $SERVICE_NAME-prd edit system:serviceaccount:$SERVICE_NAME-build:$SERVICE_NAME-prd
```

Deploy persistent jenkins:
```
oc new-app -n $SERVICE_NAME-build jenkins-persistent
```

## Sandbox Development

Setup build in sandbox:

```
SANDBOX_NAMESPACE=$SERVICE_NAME-sandbox
oc process -f build-template.yaml \
 --param=SERVICE_NAME=$SERVICE_NAME \
| oc apply -n $SANDBOX_NAMESPACE -f -
oc start-build example --from-dir=.
```

```
oc process -f deploy-template.yaml \
 --param=BUILD_NAMESPACE=$SANDBOX_NAMESPACE \
 --param=SERVICE_NAME=$SERVICE_NAME \
| oc apply -n $SANDBOX_NAMESPACE -f -
```

Testing
```
oc create configmap -n $SANDBOX_NAMESPACE ${SERVICE_NAME}-test-scripts --from-file=test-scripts/
oc process -f test-template.yaml --param=SERVICE_NAME=$SERVICE_NAME | oc create -f -
```

Test cleanup
```
oc delete configmap ${SERVICE_NAME}-test-scripts
oc delete pod ${SERVICE_NAME}-test
```

## Jenkins Pipeline

https://master.ibm.example.opentlc.com/

https://jenkins-userXX-build.apps.ibm.example.opentlc.com/view/all/newJob

Get tokens

```
oc sa get-token example-dev
oc sa get-token example-test
oc sa get-token example-prd
```

Store tokens as Jenkins credentials as secret text as example-dev, example-test, and example-prd.
Be sure to set credentials id.

### Create dev pipeline

Create new job, "example-dev", type Pipeline. Click OK.

Select "This project is parameterized and add parameters:

* `ARTIFACT_URL` = String parametr, default "http://artifacts.apps.ibm.example.opentlc.com/artifact0.zip"
* `SERVICE_NAME` = String parameter, default "example"
* `DEV_OPENSHIFT_URL` = String parameter, default "https://master.ibm.example.opentlc.com/"
* `DEV_TOKEN_SECRET` = Credentials parameter with credentials type "Secret text", default "example-dev"

For Pipeline Definition, select "Pipeline script from SCM".

* SCM - "Git"
* Repository URL - "http://gogs-example.apps.ibm.example.opentlc.com/jkupferer/httpd-example.git"
* Script Path - "dev-pipeline.groovy"

Test run with defaults. Expect it to fail in stage "Test in dev"

Test again with `ARTIFACT_URL` "http://artifacts.apps.ibm.example.opentlc.com/artifact1.zip"

### Create dev pipeline
