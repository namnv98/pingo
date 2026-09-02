#!/usr/bin/env bash
# Build code (Maven) -> build Docker image -> push len registry local -> deploy Helm chart,
# cho ca 3 service (beacon, colony, harbor) len 1 cum k3s chay local.
#
# Khong dung Docker Hub o buoc nao ca (khong docker login/push len Hub) -- image build local roi
# push len 1 registry chay ngay tren may (localhost:5000). Tranh dung build-image.sh cua tung module
# (co credential Docker Hub dang plaintext trong file do, khong lien quan gi toi deploy local).
#
# Setup 1 LAN DUY NHAT truoc khi chay script nay (can sudo, chi lam 1 lan cho ca doi):
#   docker run -d -p 5000:5000 --restart=always --name local-registry registry:2
#   sudo tee /etc/rancher/k3s/registries.yaml <<'EOF'
#   mirrors:
#     "localhost:5000":
#       endpoint: ["http://localhost:5000"]
#   EOF
#   sudo systemctl restart k3s
#   sudo chmod 644 /etc/rancher/k3s/k3s.yaml   # k3s reset lai quyen file nay moi lan restart
# Sau buoc setup 1 lan do, script chay lai bao nhieu lan cung KHONG can sudo nua.
#
# Usage:
#   ./deploy-k3s.sh                  # lam tat: cai dat (neu thieu) + build + image + deploy
#   ./deploy-k3s.sh --skip-install   # da co k3s/helm san roi, bo qua buoc cai dat
#   ./deploy-k3s.sh --skip-build     # dung dist/ da build san, chi build lai image + deploy
#   ./deploy-k3s.sh --skip-image     # dung image da push san, chi deploy lai helm (vd sua chart)
#   ./deploy-k3s.sh --uninstall      # go 3 helm release + configmap (KHONG go k3s, KHONG go registry)
#
# Bien moi truong (tuy chon):
#   NAMESPACE   namespace k8s de deploy (mac dinh: default -- xem ghi chu trong ensure_namespace())
#   IMAGE_TAG   tag cho image local (mac dinh: local)

set -euo pipefail

NAMESPACE="${NAMESPACE:-default}"
IMAGE_TAG="${IMAGE_TAG:-local}"
# Label chung ca 3 pod deu mang (xem global.vertx.mainCluster.label trong tung helm/values.yaml) --
# tu luc tach Hazelcast ra cluster doc lap (xem thu muc hazelcast/), label nay chi con y nghia
# gom nhom de xem trang thai (print_summary), khong con dung cho Hazelcast discovery nua.
CLUSTER_LABEL_KEY="lego/vertx-cluster"
CLUSTER_LABEL_VALUE="vertx-land-cluster"
MODULES=(beacon colony harbor) # thu tu deploy: control-plane (beacon) truoc, khong bat buoc nhung hop ly

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mxx %s\033[0m\n' "$*" >&2; exit 1; }

DO_INSTALL=1
DO_BUILD=1
DO_IMAGE=1
DO_DEPLOY=1
ACTION_UNINSTALL=0

for arg in "$@"; do
  case "$arg" in
    --skip-install) DO_INSTALL=0 ;;
    --skip-build) DO_BUILD=0 ;;
    --skip-image) DO_IMAGE=0 ;;
    --skip-deploy) DO_DEPLOY=0 ;;
    --uninstall) ACTION_UNINSTALL=1 ;;
    -h|--help)
      sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) die "Khong nhan dien duoc tham so: $arg (dung --help de xem huong dan)" ;;
  esac
done

# ============================================================================
# 1. Cai dat k3s + helm (idempotent -- bo qua neu da co)
# ============================================================================

install_k3s() {
  if command -v k3s >/dev/null 2>&1; then
    log "k3s da cai san, bo qua buoc cai dat"
    return
  fi
  command -v curl >/dev/null 2>&1 || die "Can 'curl' de cai k3s"
  log "Cai k3s (se hoi mat khau sudo)..."
  # --write-kubeconfig-mode 644: cho user thuong doc duoc kubeconfig ma khong can sudo moi lan.
  # --disable traefik: bo bot ingress controller mac dinh, khong dung toi trong project nay.
  curl -sfL https://get.k3s.io | \
    INSTALL_K3S_EXEC="--write-kubeconfig-mode 644 --disable traefik" sh -
  log "Doi k3s node Ready..."
  local tries=0
  until kubectl get node 2>/dev/null | grep -q ' Ready'; do
    tries=$((tries + 1))
    [ "$tries" -gt 60 ] && die "k3s khong Ready sau 2 phut, kiem tra: sudo systemctl status k3s"
    sleep 2
  done
  log "k3s da Ready"
}

install_helm() {
  if command -v helm >/dev/null 2>&1; then
    log "helm da cai san, bo qua buoc cai dat"
    return
  fi
  log "Cai helm..."
  curl -sfL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
}

# ============================================================================
# 2. Build code bang Maven -> sinh dist/<module> cho tung service
# ============================================================================

build_code() {
  log "mvn clean package (beacon, colony, harbor + core/discovery)..."
  (cd "$REPO_ROOT" && mvn -q clean package -pl beacon,colony,harbor -am -DskipTests)
}

# ============================================================================
# 3. Build Docker image local + day len registry local (khong dung Docker Hub)
#    Can setup 1 lan (ngoai script, can sudo):
#      docker run -d -p 5000:5000 --restart=always --name local-registry registry:2
#      sudo tee /etc/rancher/k3s/registries.yaml <<'EOF'
#      mirrors:
#        "localhost:5000":
#          endpoint: ["http://localhost:5000"]
#      EOF
#      sudo systemctl restart k3s
#    Sau buoc setup 1 lan do, moi lan build/deploy ve sau KHONG can sudo nua.
# ============================================================================

REGISTRY="localhost:5000"

ensure_local_registry() {
  if ! curl -sf "http://${REGISTRY}/v2/_catalog" >/dev/null 2>&1; then
    if docker ps -a --format '{{.Names}}' | grep -qx local-registry; then
      log "Container local-registry co san nhung dang khong chay, start lai..."
      docker start local-registry >/dev/null
    else
      log "Chua co registry local, tao moi (docker run registry:2)..."
      docker run -d -p 5000:5000 --restart=always --name local-registry registry:2 >/dev/null
    fi
    sleep 1
    curl -sf "http://${REGISTRY}/v2/_catalog" >/dev/null 2>&1 \
      || die "Registry local o ${REGISTRY} khong phan hoi -- kiem tra 'docker logs local-registry'"
  fi
}

build_images() {
  for m in "${MODULES[@]}"; do
    log "docker build ${REGISTRY}/${m}:${IMAGE_TAG}"
    docker build -t "${REGISTRY}/${m}:${IMAGE_TAG}" "$REPO_ROOT/$m"
  done
}

import_images() {
  for m in "${MODULES[@]}"; do
    log "docker push ${REGISTRY}/${m}:${IMAGE_TAG}"
    docker push "${REGISTRY}/${m}:${IMAGE_TAG}"
  done
}

# ============================================================================
# 4. Toan bo ha tang Hazelcast -- helm chart rieng o hazelcast/helm (nhat quan voi
#    beacon/colony/harbor, thay vi kubectl apply file tinh: duoc versioning, rollback, va quan
#    trong nhat la "helm uninstall" don dep dung theo release, khong so sot tai nguyen nhu truoc):
#      - backbone 3 pod FULL member co dinh, tu ghep cluster qua tcp-ip tinh (StatefulSet DNS on
#        dinh), khong dinh gi toi vong doi scale/restart cua beacon/colony/harbor.
#      - ConfigMap hazelcast-files-cfm -- beacon/colony/harbor mount file nay, tu embed 1
#        lite-member roi join vao backbone qua tcp-ip.
# ============================================================================

ensure_hazelcast_cluster() {
  log "helm upgrade -i hazelcast (namespace=${NAMESPACE})"
  helm upgrade -i hazelcast "$REPO_ROOT/hazelcast/helm" -n "$NAMESPACE" --wait --timeout 2m
}

# ============================================================================
# 4b. Postgres cho colony (bang conversation_members) -- helm chart rieng o postgres/helm, cung
#     pattern voi hazelcast/helm: image day san vao registry local (postgres:17 -> localhost:5000),
#     schema.sql tu dong chay 1 lan qua ConfigMap mount vao /docker-entrypoint-initdb.d/ (co san cua
#     image postgres chinh thuc), du lieu ben qua PersistentVolumeClaim (local-path-provisioner
#     mac dinh cua k3s).
# ============================================================================

ensure_postgres() {
  log "helm upgrade -i postgres (namespace=${NAMESPACE})"
  helm upgrade -i postgres "$REPO_ROOT/postgres/helm" -n "$NAMESPACE" --wait --timeout 2m
}

ensure_namespace() {
  # Mac dinh "default": K8sClientConfig ben beacon (BeaconAppModule) fallback ve "default" khi
  # bien moi truong K8S_NAMESPACE khong duoc set -- va deployment.yml hien khong set bien do --
  # nen deploy sang namespace khac "default" se khien beacon watch nham namespace. Chi doi
  # NAMESPACE neu ban da tu chinh sua them K8S_NAMESPACE vao deployment.yml.
  if [ "$NAMESPACE" != "default" ]; then
    warn "NAMESPACE=${NAMESPACE} khac 'default' -- beacon hien KHONG doc bien K8S_NAMESPACE tu" \
         "deployment.yml nen se van watch pod o namespace 'default', khong phai '${NAMESPACE}'."
    kubectl get ns "$NAMESPACE" >/dev/null 2>&1 || kubectl create ns "$NAMESPACE"
  fi
}

# ============================================================================
# 5. Deploy 3 helm chart, tro image ve tag local vua build
# ============================================================================

deploy_helm() {
  for m in "${MODULES[@]}"; do
    log "helm upgrade -i ${m} (namespace=${NAMESPACE}, image=${REGISTRY}/${m}:${IMAGE_TAG})"
    # imagePullPolicy=Always: tag ":local" la mutable (push de len cung tag moi lan build), IfNotPresent
    # (mac dinh cua chart, hop ly cho prod voi tag version co dinh) se khien kubelet dung ban cache cu
    # sau lan pull dau tien, khong bao gio thay code moi. Deploy local nen luon force pull lai.
    helm upgrade -i "$m" "$REPO_ROOT/$m/helm" \
      -n "$NAMESPACE" \
      --set imageId="${REGISTRY}/${m}:${IMAGE_TAG}" \
      --set imagePullPolicy=Always \
      --wait --timeout 3m
  done
}

print_summary() {
  log "Trang thai pod:"
  kubectl get pods -n "$NAMESPACE" -l "${CLUSTER_LABEL_KEY}=${CLUSTER_LABEL_VALUE}" -o wide
  cat <<EOF

Test thu:
  - Mo harbor/demo.html tren trinh duyet, doi URL WebSocket thanh:
      ws://localhost:31003/connect/websocket
    (NodePort 31003 -> containerPort 8888 cua harbor, xem harbor/helm/templates/services.yaml)
  - Xem log 1 pod:  kubectl logs -n ${NAMESPACE} deploy/harbor -f
  - Xoa sach:        ./deploy-k3s.sh --uninstall
EOF
}

# ============================================================================
# Uninstall
# ============================================================================

do_uninstall() {
  log "Go 5 helm release (namespace=${NAMESPACE})..."
  for m in "${MODULES[@]}" hazelcast postgres; do
    helm uninstall "$m" -n "$NAMESPACE" --ignore-not-found || true
  done
  log "Da go xong 5 helm release (beacon/colony/harbor + hazelcast + postgres)."
  echo "PersistentVolumeClaim postgres-data KHONG tu bi xoa (du lieu conversation_members van con) --"
  echo "xoa tay neu muon mat het du lieu that su: kubectl delete pvc postgres-data -n ${NAMESPACE}"
  echo "k3s ban than KHONG bi go. Neu muon go han k3s: sudo /usr/local/bin/k3s-uninstall.sh"
}

# ============================================================================
# main
# ============================================================================

command -v docker >/dev/null 2>&1 || die "Can 'docker' de build image (chua thay trong PATH)"

if [ "$ACTION_UNINSTALL" -eq 1 ]; then
  do_uninstall
  exit 0
fi

if [ "$DO_INSTALL" -eq 1 ]; then
  install_k3s
  install_helm
fi

command -v kubectl >/dev/null 2>&1 || die "Can 'kubectl' (thuong di kem k3s hoac cai rieng)"
command -v helm >/dev/null 2>&1 || die "Can 'helm' -- chay lai khong co --skip-install de tu cai"

ensure_namespace

if [ "$DO_BUILD" -eq 1 ]; then
  build_code
fi

if [ "$DO_IMAGE" -eq 1 ]; then
  ensure_local_registry
  build_images
  import_images
fi

if [ "$DO_DEPLOY" -eq 1 ]; then
  ensure_hazelcast_cluster
  ensure_postgres
  deploy_helm
  print_summary
fi
