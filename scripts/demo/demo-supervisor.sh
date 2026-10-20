#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/bin:/bin
umask 077

refuse() { printf '%s\n' 'Demo supervisor refused; maintenance is not automatically reopened.' >&2; exit 78; }
case "${SSH_ORIGINAL_COMMAND-}" in
  '') ;;
  status|maintenance-on|stop-writers|start-writers|maintenance-off)
    if [ "$#" -eq 0 ]; then exec /usr/bin/sudo -n -- "$0" "$SSH_ORIGINAL_COMMAND"; fi ;;
  *) refuse ;;
esac
[ "$#" -eq 1 ] || refuse
case "$1" in status|maintenance-on|stop-writers|start-writers|maintenance-off) action=$1 ;; *) refuse ;; esac
[ ! -L "$0" ] || refuse
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
owner=$(id -u)
trusted() {
  [ -e "$1" ] && [ ! -L "$1" ] || refuse
  [ "$(stat -c %u -- "$1")" = "$owner" ] || refuse
  local mode; mode=$(stat -c %a -- "$1")
  (( (8#$mode & 8#022) == 0 )) || refuse
}
trusted "$root"; trusted "$0"; trusted "$root/policy"
seen=' '
while IFS='=' read -r key value || [ -n "${key-}" ]; do
  case "$seen" in *" $key "*) refuse ;; esac
  seen="$seen$key "
  case "$key" in
    PROJECT) PROJECT=$value ;; DOCKER) DOCKER=$value ;; CURL) CURL=$value ;;
    BACKEND_NETWORK) BACKEND_NETWORK=$value ;; BACKEND_NETWORK_ID) BACKEND_NETWORK_ID=$value ;;
    PROXY_NETWORK) PROXY_NETWORK=$value ;; PROXY_NETWORK_ID) PROXY_NETWORK_ID=$value ;;
    MAINTENANCE_IMAGE) MAINTENANCE_IMAGE=$value ;; DYNAMIC_DIRECTORY) DYNAMIC_DIRECTORY=$value ;;
    PROXY_HTTPS_PORT) PROXY_HTTPS_PORT=$value ;; CA_FILE) CA_FILE=$value ;;
    *) refuse ;;
  esac
done < "$root/policy"
[[ ${PROJECT-} =~ ^[a-z0-9]{12,64}$ && ${BACKEND_NETWORK-} = "$PROJECT-backend" ]] || refuse
[[ ${BACKEND_NETWORK_ID-} =~ ^[a-f0-9]{64}$ && ${PROXY_NETWORK_ID-} =~ ^[a-f0-9]{64}$ ]] || refuse
[[ ${PROXY_NETWORK-} =~ ^[a-z0-9-]+$ && ${PROXY_HTTPS_PORT-} =~ ^[0-9]{1,5}$ ]] || refuse
[ "${MAINTENANCE_IMAGE-}" = 'nginx:1.30.6-alpine@sha256:970364228b1e4f932abc22d321932816782cca8a20b85a77552a7cfe9acfb3b5' ] || refuse
[[ ${DOCKER-} = /* && ${CURL-} = /* && ${DYNAMIC_DIRECTORY-} = /* ]] || refuse
[ -x "$DOCKER" ] && [ -x "$CURL" ] || refuse
trusted "$DYNAMIC_DIRECTORY"
if [ -n "${CA_FILE-}" ]; then trusted "$CA_FILE"; fi
if [ ! -f "$root/approved" ]; then
  [ "$action" = status ] && { printf '%s\n' 'unarmed'; exit 0; }
  refuse
fi
trusted "$root/approved"
read -r fingerprint code_hash policy_hash extra < "$root/approved"
[[ $fingerprint =~ ^[a-f0-9]{64}$ && $code_hash =~ ^[a-f0-9]{64}$ && $policy_hash =~ ^[a-f0-9]{64}$ && -z ${extra-} ]] || refuse
[ "$(sha256sum < "$0" | cut -d' ' -f1)" = "$code_hash" ] || refuse
[ "$(sha256sum < "$root/policy" | cut -d' ' -f1)" = "$policy_hash" ] || refuse
mkdir -p -- "$root/state"
trusted "$root/state"
mkdir -- "$root/state/action.lock" 2>/dev/null || refuse
trap 'rmdir -- "$root/state/action.lock" 2>/dev/null || true' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

docker_cmd() { timeout -k 5 65 "$DOCKER" "$@"; }
[ "$(docker_cmd network inspect "$BACKEND_NETWORK" --format '{{.Id}}')" = "$BACKEND_NETWORK_ID" ] || refuse
[ "$(docker_cmd network inspect "$PROXY_NETWORK" --format '{{.Id}}')" = "$PROXY_NETWORK_ID" ] || refuse
writers=(api-gateway user-service catalog-service order-service payment-service messaging-service)
protected=(discovery-server mysql-app redis rabbitmq meilisearch demo-runner)
ids=(); protected_ids=()
lookup() {
  local service=$1 found project actual network_id
  found=$(docker_cmd ps -aq --no-trunc --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$service")
  [[ $found =~ ^[a-f0-9]{64}$ ]] || refuse
  project=$(docker_cmd inspect "$found" --format '{{index .Config.Labels "com.docker.compose.project"}}')
  actual=$(docker_cmd inspect "$found" --format '{{index .Config.Labels "com.docker.compose.service"}}')
  network_id=$(docker_cmd inspect "$found" --format "{{with index .NetworkSettings.Networks \"$BACKEND_NETWORK\"}}{{.NetworkID}}{{end}}")
  [ "$project" = "$PROJECT" ] && [ "$actual" = "$service" ] && [ "$network_id" = "$BACKEND_NETWORK_ID" ] || refuse
  printf '%s' "$found"
}
for service in "${writers[@]}"; do ids+=("$(lookup "$service")"); done
for service in "${protected[@]}"; do
  id=$(lookup "$service"); protected_ids+=("$id")
  [ "$(docker_cmd inspect "$id" --format '{{.State.Running}}')" = true ] || refuse
  case "$service" in
    mysql-app) suffix=mysql-data; destination=/var/lib/mysql ;;
    redis) suffix=redis-data; destination=/data ;;
    rabbitmq) suffix=rabbitmq-data; destination=/var/lib/rabbitmq ;;
    meilisearch) suffix=meili-data; destination=/meili_data ;;
    *) continue ;;
  esac
  mount=$(docker_cmd inspect "$id" --format "{{range .Mounts}}{{if eq .Destination \"$destination\"}}{{.Type}} {{.Name}}{{end}}{{end}}")
  [ "$mount" = "volume $PROJECT-$suffix" ] || refuse
done
snapshot=$(printf '%s\n' "${ids[@]}" "${protected_ids[@]}")
actual_ids=$(docker_cmd ps -aq --no-trunc --filter "label=com.docker.compose.project=$PROJECT" | LC_ALL=C sort)
[ "$actual_ids" = "$(printf '%s\n' "$snapshot" | LC_ALL=C sort)" ] || refuse
route="$DYNAMIC_DIRECTORY/rebyte-demo-maintenance-$PROJECT.yml"
maintenance="rebyte-demo-maintenance-$PROJECT"
tls_args=()
if [ -n "${CA_FILE-}" ]; then tls_args=(--cacert "$CA_FILE"); fi
probe() {
  local path=$1 method=$2 output status
  output=$("$CURL" --noproxy '*' --silent --show-error --max-time 5 "${tls_args[@]}" --connect-to "api.rebyte.tech:443:127.0.0.1:$PROXY_HTTPS_PORT" -X "$method" -H 'Content-Type: application/json' --data '{}' -D - -o /dev/null -w '%{http_code}' "https://api.rebyte.tech$path") || return 1
  status=${output: -3}
  [ "$status" = 503 ] && [[ $output = *'X-Rebyte-Maintenance: rebyte-demo'* || $output = *'x-rebyte-maintenance: rebyte-demo'* ]]
}
maintenance_active() {
  [ -f "$route" ] || return 1
  cmp -s -- "$root/state/route" "$route" || return 1
  probe /categories GET && probe /payments/webhook POST
}
check_snapshot() {
  [ -f "$root/state/containers" ] || refuse
  [ "$(cat -- "$root/state/containers")" = "$snapshot" ] || refuse
}
healthy() {
  local state
  for id in "${ids[@]}"; do
    state=$(docker_cmd inspect "$id" --format '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}')
    [ "$state" = 'true healthy' ] || return 1
  done
}

case "$action" in
  status)
    if [ -e "$route" ]; then check_snapshot; maintenance_active || refuse; state=maintenance; else state=open; fi
    printf '{"state":"%s","fingerprint":"%s"}\n' "$state" "$fingerprint" ;;
  maintenance-on)
    if [ -f "$route" ]; then check_snapshot; maintenance_active || refuse; printf '%s\n' 'maintenance'; exit 0; fi
    # Capture all writer and protected identities before the first Docker mutation.
    printf '%s\n' "$snapshot" > "$root/state/containers"
    cat > "$root/state/maintenance.conf" <<'NGINX'
pid /tmp/nginx.pid;
error_log /dev/stderr warn;
events { worker_connections 64; }
http {
  access_log off;
  client_body_temp_path /tmp/client;
  proxy_temp_path /tmp/proxy;
  fastcgi_temp_path /tmp/fastcgi;
  uwsgi_temp_path /tmp/uwsgi;
  scgi_temp_path /tmp/scgi;
  server {
    listen 8080;
    add_header X-Rebyte-Maintenance rebyte-demo always;
    add_header Retry-After 120 always;
    add_header Cache-Control no-store always;
    location = /health { return 200 'ok'; }
    location / { default_type text/plain; return 503 'Rebyte demo maintenance. Please try again shortly.\n'; }
  }
}
NGINX
    chmod 644 -- "$root/state/maintenance.conf"
    existing=$(docker_cmd ps -aq --no-trunc --filter "name=^/$maintenance$")
    if [ -n "$existing" ]; then
      [[ $existing =~ ^[a-f0-9]{64}$ ]] || refuse
      [ "$(docker_cmd inspect "$existing" --format '{{index .Config.Labels "rebyte.supervisor"}}')" = "$PROJECT" ] || refuse
      [ "$(docker_cmd inspect "$existing" --format '{{.Config.Image}}')" = "$MAINTENANCE_IMAGE" ] || refuse
      docker_cmd start "$existing" >/dev/null
    else
      docker_cmd run -d --name "$maintenance" --label "rebyte.supervisor=$PROJECT" --label traefik.enable=false --network "$PROXY_NETWORK" --network-alias "$maintenance" --memory 64m --pids-limit 64 --read-only --cap-drop ALL --security-opt no-new-privileges --user nginx --tmpfs /tmp:rw,noexec,nosuid,size=16m --mount "type=bind,src=$root/state/maintenance.conf,dst=/etc/nginx/maintenance.conf,readonly" --entrypoint nginx "$MAINTENANCE_IMAGE" -c /etc/nginx/maintenance.conf -g 'daemon off;' >/dev/null
    fi
    cat > "$root/state/route" <<EOF
{"http":{"routers":{"rebyte-demo-maintenance-$PROJECT":{"rule":"Host(\`api.rebyte.tech\`) || Host(\`rebyte.tech\`) || Host(\`www.rebyte.tech\`)","entryPoints":["https"],"priority":1000000,"tls":{},"service":"rebyte-demo-maintenance-$PROJECT"}},"services":{"rebyte-demo-maintenance-$PROJECT":{"loadBalancer":{"servers":[{"url":"http://$maintenance:8080"}]}}}}}
EOF
    [ ! -e "$route" ] || refuse
    cp -- "$root/state/route" "$route.pending"
    mv -- "$route.pending" "$route"
    # Notify the watched parent when this dedicated directory was added after proxy startup.
    touch -- "$DYNAMIC_DIRECTORY"
    deadline=$((SECONDS+25))
    until maintenance_active; do [ "$SECONDS" -lt "$deadline" ] || refuse; sleep 0.25; done
    printf '%s\n' 'maintenance' ;;
  stop-writers)
    check_snapshot; maintenance_active || refuse
    docker_cmd stop --time 30 "${ids[@]}" >/dev/null
    for id in "${ids[@]}"; do [ "$(docker_cmd inspect "$id" --format '{{.State.Running}}')" = false ] || refuse; done
    # The delayed-message plugin retains deliveries outside queue/exchange contents.
    docker_cmd exec "${protected_ids[3]}" rabbitmqctl eval '{atomic,ok}=mnesia:clear_table(rabbit_delayed_message:table_name()), {atomic,ok}=mnesia:clear_table(rabbit_delayed_message:index_table_name()), 0=mnesia:table_info(rabbit_delayed_message:table_name(),size), 0=mnesia:table_info(rabbit_delayed_message:index_table_name(),size), ok.' >/dev/null
    printf '%s\n' quiescent > "$root/state/phase"
    printf '%s\n' 'quiescent' ;;
  start-writers)
    check_snapshot; maintenance_active || refuse
    [ "$(cat -- "$root/state/phase")" = quiescent ] || refuse
    docker_cmd start "${ids[@]:1}" >/dev/null
    docker_cmd start "${ids[0]}" >/dev/null
    deadline=$((SECONDS+180))
    until healthy; do [ "$SECONDS" -lt "$deadline" ] || refuse; maintenance_active || refuse; sleep 0.25; done
    printf '%s\n' restarted > "$root/state/phase"
    printf '%s\n' 'ready-for-private-seed' ;;
  maintenance-off)
    check_snapshot; maintenance_active || refuse
    [ "$(cat -- "$root/state/phase")" = restarted ] && healthy || refuse
    # The locked runner calls this only after successful seed and explicit readiness.
    mv -- "$route" "$root/state/closed-route"
    touch -- "$DYNAMIC_DIRECTORY"
    printf '%s\n' open > "$root/state/phase"
    printf '%s\n' 'open' ;;
esac
