set -x
NAMESPACE=$(cat /run/secrets/kubernetes.io/serviceaccount/namespace)
SERVICE_URL=http://$SERVICE_NAME.$NAMESPACE.svc:8080/
