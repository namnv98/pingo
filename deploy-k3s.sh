
#!/usr/bin/env bash

# ============================================================================
# deploy-k3s.sh
#
# Build Maven -> Build Docker -> Push local registry -> Deploy Helm
#
# IMPORTANT:
#   MODULE_PATH, IMAGE_NAME and RELEASE_NAME are intentionally separated.
#
# Java services:
#   beacon
#   colony
#   hall
#   harbor
#
# Nginx:
#   file-server/fileserver
#
# Infrastructure:
#   hazelcast
#   postgres
#
# Total:
#   4 Java services + 1 Nginx + Hazelcast + PostgreSQL
#   = 7 Helm releases
#
# Local images:
#   localhost:5000/<IMAGE_NAME>:<IMAGE_TAG>
#
# Example:
#   file-server/fileserver
#       MODULE_PATH  = file-server/fileserver
#       IMAGE_NAME   = file-server
#       RELEASE_NAME = file-server
#
# Usage:
#
#   ./deploy-k3s.sh
#       Install/check k3s + helm
#       Build Maven
#       Build Docker images
#       Push registry
#       Deploy Helm
#
#   ./deploy-k3s.sh --skip-install
#   ./deploy-k3s.sh --skip-build
#   ./deploy-k3s.sh --skip-image
#   ./deploy-k3s.sh --skip-deploy
#   ./deploy-k3s.sh --uninstall
#
# Environment:
#
#   NAMESPACE=default
#   IMAGE_TAG=local
#
# Example:
#
#   IMAGE_TAG=dev ./deploy-k3s.sh
#   NAMESPACE=pingo IMAGE_TAG=local ./deploy-k3s.sh
#
# One-time local registry setup:
#
#   docker run -d \
#     -p 5000:5000 \
#     --restart=always \
#     --name local-registry \
#     registry:2
#
#   sudo tee /etc/rancher/k3s/registries.yaml <<'EOF'
#   mirrors:
#     "localhost:5000":
#       endpoint:
#         - "http://localhost:5000"
#   EOF
#
#   sudo systemctl restart k3s
#
# ============================================================================

set -euo pipefail


# ============================================================================
# 0. CONFIG
# ============================================================================

NAMESPACE="${NAMESPACE:-default}"
IMAGE_TAG="${IMAGE_TAG:-local}"

REGISTRY="${REGISTRY:-localhost:5000}"

CLUSTER_LABEL_KEY="lego/vertx-cluster"
CLUSTER_LABEL_VALUE="vertx-land-cluster"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"


# ============================================================================
# MODULE DEFINITIONS
#
# IMPORTANT:
#
#   PATH          = filesystem path
#   IMAGE         = Docker image repository name
#   RELEASE       = Helm release name
#
# KHONG dung filesystem path de suy ra image name.
# ============================================================================

MAVEN_MODULES=(
  "beacon"
  "colony"
  "hall"
  "harbor"
)

DEPLOY_MODULES=(
  "beacon"
  "colony"
  "hall"
  "harbor"
  "file-server/fileserver"
)


# ============================================================================
# MODULE PATH
# ============================================================================

module_path() {
  case "$1" in
    beacon)
      echo "beacon"
      ;;

    colony)
      echo "colony"
      ;;

    hall)
      echo "hall"
      ;;

    harbor)
      echo "harbor"
      ;;

    file-server)
      echo "file-server/fileserver"
      ;;

    file-server/fileserver)
      echo "file-server/fileserver"
      ;;

    *)
      die "Unknown module: $1"
      ;;
  esac
}


# ============================================================================
# DOCKER IMAGE NAME
#
# This is deliberately NOT the filesystem path.
#
# Example:
#
#   file-server/fileserver -> file-server
# ============================================================================

image_name() {
  case "$1" in
    beacon)
      echo "beacon"
      ;;

    colony)
      echo "colony"
      ;;

    hall)
      echo "hall"
      ;;

    harbor)
      echo "harbor"
      ;;

    file-server)
      echo "file-server"
      ;;

    file-server/fileserver)
      echo "file-server"
      ;;

    *)
      die "Unknown module for image: $1"
      ;;
  esac
}


# ============================================================================
# HELM RELEASE NAME
#
# Helm release names must be valid Kubernetes resource names.
# ============================================================================

release_name() {
  case "$1" in
    beacon)
      echo "beacon"
      ;;

    colony)
      echo "colony"
      ;;

    hall)
      echo "hall"
      ;;

    harbor)
      echo "harbor"
      ;;

    file-server)
      echo "file-server"
      ;;

    file-server/fileserver)
      echo "file-server"
      ;;

    *)
      die "Unknown module for Helm release: $1"
      ;;
  esac
}


# ============================================================================
# CHART PATH
# ============================================================================

chart_path() {
  local path

  path="$(module_path "$1")"

  echo "$REPO_ROOT/$path/helm"
}


# ============================================================================
# IMAGE
# ============================================================================

image_ref() {
  local module="$1"
  local image

  image="$(image_name "$module")"

  echo "${REGISTRY}/${image}:${IMAGE_TAG}"
}


# ============================================================================
# LOGGING
# ============================================================================

log() {
  printf '\n\033[1;36m==> %s\033[0m\n' "$*"
}


warn() {
  printf '\033[1;33m!! %s\033[0m\n' "$*" >&2
}


die() {
  printf '\033[1;31mxx %s\033[0m\n' "$*" >&2
  exit 1
}


# ============================================================================
# FLAGS
# ============================================================================

DO_INSTALL=1
DO_BUILD=1
DO_IMAGE=1
DO_DEPLOY=1
ACTION_UNINSTALL=0


for arg in "$@"; do
  case "$arg" in

    --skip-install)
      DO_INSTALL=0
      ;;

    --skip-build)
      DO_BUILD=0
      ;;

    --skip-image)
      DO_IMAGE=0
      ;;

    --skip-deploy)
      DO_DEPLOY=0
      ;;

    --uninstall)
      ACTION_UNINSTALL=1
      ;;

    -h|--help)
      sed -n '2,75p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;

    *)
      die "Khong nhan dien duoc tham so: $arg (dung --help de xem huong dan)"
      ;;

  esac
done


# ============================================================================
# 1. CHECK DOCKER
# ============================================================================

command -v docker >/dev/null 2>&1 \
  || die "Can 'docker' de build image."


# ============================================================================
# 2. FIREWALLD
# ============================================================================

ensure_firewalld_zones() {

  command -v firewall-cmd >/dev/null 2>&1 || return 0

  systemctl is-active --quiet firewalld 2>/dev/null || return 0


  local ifaces_to_fix=()

  # KHONG dua docker0 vao day: Docker (>=20.10 co tich hop firewalld) tu quan ly zone
  # rieng "docker" cho docker0 -- ep no sang "trusted" gay ZONE_CONFLICT luc dockerd
  # khoi dong lai ("'docker0' already bound to 'trusted'"), lam dockerd crash-loop.
  # Chi can cni0/flannel.1 (interface CNI/VXLAN that su cua k3s) nam trong trusted.
  for iface in cni0 flannel.1; do

    ip link show "$iface" >/dev/null 2>&1 || continue

    local zone

    # --get-zone-of-interface la lenh DOC, khong can root (polkit cho user thuong query
    # duoc) -- sudo o day thua, va trong shell khong co TTY thi sudo luon fail (khong
    # hoi duoc password), khien zone luon rong -> tuong nham moi interface deu sai zone.
    zone="$(
      firewall-cmd \
        --get-zone-of-interface="$iface" \
        2>/dev/null || true
    )"


    if [ "$zone" != "trusted" ]; then
      ifaces_to_fix+=("$iface")
    fi

  done


  [ "${#ifaces_to_fix[@]}" -eq 0 ] && return 0


  warn \
    "firewalld dang chan traffic qua: ${ifaces_to_fix[*]}."


  for iface in "${ifaces_to_fix[@]}"; do

    sudo firewall-cmd \
      --permanent \
      --zone=trusted \
      --add-interface="$iface" \
      >/dev/null

  done


  sudo firewall-cmd --reload >/dev/null


  log \
    "Da chuyen ${ifaces_to_fix[*]} sang trusted."


  log "Restart k3s..."


  sudo systemctl restart k3s


  local tries=0


  until kubectl get node 2>/dev/null | grep -q ' Ready'; do

    tries=$((tries + 1))


    if [ "$tries" -gt 60 ]; then

      die \
        "k3s khong Ready sau restart. " \
        "Kiem tra: sudo systemctl status k3s"

    fi


    sleep 2

  done


  log "k3s da Ready"
}


# ============================================================================
# 3. INSTALL K3S
# ============================================================================

install_k3s() {

  if command -v k3s >/dev/null 2>&1; then

    log "k3s da cai san."

    return

  fi


  command -v curl >/dev/null 2>&1 \
    || die "Can 'curl' de cai k3s."


  log "Cai k3s..."


  curl -sfL https://get.k3s.io | \
    INSTALL_K3S_EXEC="--write-kubeconfig-mode 644 --disable traefik" \
    sh -


  log "Doi k3s node Ready..."


  local tries=0


  until kubectl get node 2>/dev/null | grep -q ' Ready'; do

    tries=$((tries + 1))


    if [ "$tries" -gt 60 ]; then

      die \
        "k3s khong Ready sau 2 phut. " \
        "Kiem tra: sudo systemctl status k3s"

    fi


    sleep 2

  done


  log "k3s da Ready"
}


# ============================================================================
# 4. INSTALL HELM
# ============================================================================

install_helm() {

  if command -v helm >/dev/null 2>&1; then

    log "helm da cai san."

    return

  fi


  command -v curl >/dev/null 2>&1 \
    || die "Can 'curl' de cai Helm."


  log "Cai Helm..."


  curl -sfL \
    https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 \
    | bash
}


# ============================================================================
# 5. NAMESPACE
# ============================================================================

ensure_namespace() {

  if [ "$NAMESPACE" = "default" ]; then
    return
  fi


  if kubectl get ns "$NAMESPACE" >/dev/null 2>&1; then
    return
  fi


  log "Tao namespace ${NAMESPACE}..."


  kubectl create ns "$NAMESPACE"
}


# ============================================================================
# 6. MAVEN BUILD
#
# Only Maven reactor modules.
#
# file-server/fileserver is NOT Maven.
# ============================================================================

build_code() {

  log \
    "mvn clean package (${MAVEN_MODULES[*]} + dependencies)..."


  local pl

  pl="$(IFS=,; echo "${MAVEN_MODULES[*]}")"


  (
    cd "$REPO_ROOT"

    mvn -q \
      clean package \
      -pl "$pl" \
      -am \
      -DskipTests
  )
}


# ============================================================================
# 7. LOCAL REGISTRY
# ============================================================================

ensure_local_registry() {

  if curl -sf \
    "http://${REGISTRY}/v2/_catalog" \
    >/dev/null 2>&1; then

    log "Local registry ${REGISTRY} dang chay."

    return

  fi


  if docker ps -a \
    --format '{{.Names}}' \
    | grep -qx local-registry; then

    log "Start local-registry..."


    docker start local-registry >/dev/null

  else

    log "Tao local-registry..."


    docker run -d \
      -p 5000:5000 \
      --restart=always \
      --name local-registry \
      registry:2 \
      >/dev/null

  fi


  sleep 1


  curl -sf \
    "http://${REGISTRY}/v2/_catalog" \
    >/dev/null 2>&1 \
    || die \
      "Registry ${REGISTRY} khong phan hoi. " \
      "Kiem tra: docker logs local-registry"
}


# ============================================================================
# 8. BUILD DOCKER IMAGES
#
# Filesystem path:
#
#   file-server/fileserver
#
# Docker image:
#
#   localhost:5000/file-server:local
# ============================================================================

build_images() {

  for module in "${DEPLOY_MODULES[@]}"; do

    local path
    local image

    path="$(module_path "$module")"
    image="$(image_ref "$module")"


    log "docker build ${image}"


    docker build \
      -t "$image" \
      "$REPO_ROOT/$path"

  done
}


# ============================================================================
# 9. PUSH DOCKER IMAGES
# ============================================================================

push_images() {

  for module in "${DEPLOY_MODULES[@]}"; do

    local image

    image="$(image_ref "$module")"


    log "docker push ${image}"


    docker push "$image"

  done
}


# ============================================================================
# 10. PRINT IMAGE MAP
#
# Useful for debugging path/name mismatch.
# ============================================================================

print_module_map() {

  log "Module mapping:"


  printf '\n'
  printf '%-28s %-32s %-20s\n' \
    "MODULE PATH" \
    "DOCKER IMAGE" \
    "HELM RELEASE"


  printf '%-28s %-32s %-20s\n' \
    "----------------------------" \
    "--------------------------------" \
    "--------------------"


  for module in "${DEPLOY_MODULES[@]}"; do

    printf '%-28s %-32s %-20s\n' \
      "$(module_path "$module")" \
      "$(image_ref "$module")" \
      "$(release_name "$module")"

  done


  printf '\n'
}


# ============================================================================
# 11. HAZELCAST
# ============================================================================

ensure_hazelcast_cluster() {

  log \
    "helm upgrade -i hazelcast " \
    "(namespace=${NAMESPACE})"


  helm upgrade -i \
    hazelcast \
    "$REPO_ROOT/hazelcast/helm" \
    -n "$NAMESPACE" \
    --wait \
    --timeout 2m
}


# ============================================================================
# 12. POSTGRES
# ============================================================================

ensure_postgres() {

  log \
    "helm upgrade -i postgres " \
    "(namespace=${NAMESPACE})"


  helm upgrade -i \
    postgres \
    "$REPO_ROOT/postgres/helm" \
    -n "$NAMESPACE" \
    --wait \
    --timeout 2m
}


# ============================================================================
# 13. DEPLOY HELM
#
# Each module:
#
#   MODULE_PATH
#       |
#       +--> Helm chart
#
#   IMAGE_NAME
#       |
#       +--> Docker image
#
#   RELEASE_NAME
#       |
#       +--> Helm release
# ============================================================================

deploy_helm() {
  for module in "${DEPLOY_MODULES[@]}"; do
    local release
    local chart
    local image

    release="$(release_name "$module")"
    chart="$(chart_path "$module")"
    image="$(image_ref "$module")"

    log \
      "helm upgrade -i ${release} " \
      "(namespace=${NAMESPACE}, image=${image})"
    helm upgrade -i \
      "$release" \
      "$chart" \
      -n "$NAMESPACE" \
      --set-string imageId="$image" \
      --set-string imagePullPolicy=Always \
      --wait \
      --timeout 3m

  done
}

# ============================================================================
# 14. SUMMARY
# ============================================================================

print_summary() {
  log "Trang thai pod:"
  kubectl get pods \
    -n "$NAMESPACE" \
    -o wide
  cat <<EOF

===============================================================================

Docker images:

  docker images | grep '${REGISTRY}'

Registry:

  curl http://${REGISTRY}/v2/_catalog

Helm releases:

  helm list -n ${NAMESPACE}

Pods:

  kubectl get pods -n ${NAMESPACE} -o wide

Services:

  kubectl get svc -n ${NAMESPACE}

Harbor WebSocket:

  ws://localhost:31003/connect

Harbor logs:

  kubectl logs -n ${NAMESPACE} deploy/harbor -f

File server logs:

  kubectl logs -n ${NAMESPACE} deploy/file-server -f

Uninstall Helm:

  ./deploy-k3s.sh --uninstall

===============================================================================

EOF
}

# ============================================================================
# 15. UNINSTALL
#
# Does NOT:
#   - remove k3s
#   - remove local registry
#   - remove PostgreSQL PVC
# ============================================================================

do_uninstall() {

  log \
    "Go Helm releases trong namespace=${NAMESPACE}..."


  for module in "${DEPLOY_MODULES[@]}"; do

    local release

    release="$(release_name "$module")"


    log "helm uninstall ${release}"


    helm uninstall \
      "$release" \
      -n "$NAMESPACE" \
      --ignore-not-found \
      || true

  done


  log "helm uninstall hazelcast"


  helm uninstall \
    hazelcast \
    -n "$NAMESPACE" \
    --ignore-not-found \
    || true


  log "helm uninstall postgres"


  helm uninstall \
    postgres \
    -n "$NAMESPACE" \
    --ignore-not-found \
    || true


  log "Da go xong Helm releases."


  cat <<EOF

PostgreSQL PVC KHONG bi xoa.

Neu muon xoa PostgreSQL data:

  kubectl delete pvc postgres-data -n ${NAMESPACE}

Local Docker registry KHONG bi xoa.

k3s KHONG bi xoa.

Neu muon go han k3s:

  sudo /usr/local/bin/k3s-uninstall.sh

EOF
}


# ============================================================================
# 16. UNINSTALL
# ============================================================================

if [ "$ACTION_UNINSTALL" -eq 1 ]; then

  command -v helm >/dev/null 2>&1 \
    || die "Can 'helm' de uninstall."

  do_uninstall

  exit 0
fi


# ============================================================================
# 17. INSTALL
# ============================================================================

if [ "$DO_INSTALL" -eq 1 ]; then

  install_k3s

  install_helm

fi


# ============================================================================
# 18. CHECK KUBECTL / HELM
# ============================================================================

command -v kubectl >/dev/null 2>&1 \
  || die \
    "Can 'kubectl'."


command -v helm >/dev/null 2>&1 \
  || die \
    "Can 'helm'. Chay lai khong co --skip-install."


# ============================================================================
# 19. PREPARE K8S
# ============================================================================

ensure_firewalld_zones
ensure_namespace


# ============================================================================
# 20. PRINT MAPPING
# ============================================================================

print_module_map

# ============================================================================
# 21. MAVEN BUILD
# ============================================================================

if [ "$DO_BUILD" -eq 1 ]; then
  build_code
fi

# ============================================================================
# 22. DOCKER BUILD + PUSH
# ============================================================================

if [ "$DO_IMAGE" -eq 1 ]; then
  ensure_local_registry
  build_images
  push_images

fi
# ============================================================================
# 23. HELM DEPLOY
# ============================================================================

if [ "$DO_DEPLOY" -eq 1 ]; then
  ensure_hazelcast_cluster
  ensure_postgres
  deploy_helm
  print_summary
fi