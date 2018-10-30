set -x
NAMESPACE=$(cat /run/secrets/kubernetes.io/serviceaccount/namespace)
SERVICE_URL=http://httpd-demo.$NAMESPACE.svc:8080/
