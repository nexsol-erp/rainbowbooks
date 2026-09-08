#!/usr/bin/env bash
#
# Deploys one release on the instance: pulls the backend and web images for
# IMAGE_TAG from GHCR and restarts the stack on them.
#
# Run by the Deploy workflow only. There is no source checkout on this box to
# run it from by hand - the workflow syncs the compose files here itself
# before invoking this script, and pipes a short-lived GHCR read token on
# stdin (never as an argument or env var, so it cannot appear in this host's
# `ps` output - other accounts share this machine).
#
# It does not roll back. Compose replaces the containers before anything can
# be checked, so if the new image is broken it is the one left running, and
# the site is down until someone acts. To roll back, re-run the Deploy
# workflow (workflow_dispatch) against the previous commit - there is nothing
# to fix on this box directly, because it holds no state about what "current"
# means beyond whatever is running.

set -euo pipefail

APP_DIR="${APP_DIR:?set APP_DIR}"
IMAGE_TAG="${IMAGE_TAG:?set IMAGE_TAG}"
GHCR_ACTOR="${GHCR_ACTOR:?set GHCR_ACTOR}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

cd "$APP_DIR"

if [[ ! -f .env ]]; then
    echo "No .env in $APP_DIR. Copy .env.example and fill it in before deploying." >&2
    exit 1
fi

read -r ghcr_token
echo "$ghcr_token" | docker login ghcr.io -u "$GHCR_ACTOR" --password-stdin

export BACKEND_IMAGE="ghcr.io/nexsol-erp/rainbowbooks-backend:${IMAGE_TAG}"
export WEB_IMAGE="ghcr.io/nexsol-erp/rainbowbooks-web:${IMAGE_TAG}"

# Mode is switched by which of these is filled in, not by a separate command.
#
# SHARED_HOST means another app's Caddy already owns 80/443 on this host:
# this app joins its `edge` network instead and is routed by container name,
# so it does not run its own Caddy or publish anything itself.
#
# Otherwise SITE_DOMAIN means this app owns the host outright: Caddy cannot
# get a certificate until the name resolves here and 443 is open, and it
# redirects http to https the moment it starts - so bringing it up too early
# takes the site down rather than securing it. Leave both empty to serve
# plain HTTP on port 80 until DNS and the security group are ready.
site_domain="$(grep -E '^SITE_DOMAIN=' .env | cut -d= -f2- | tr -d '"' | xargs || true)"
shared_host="$(grep -E '^SHARED_HOST=' .env | cut -d= -f2- | tr -d '"' | xargs || true)"

if [[ "$shared_host" == "true" ]]; then
    COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.shared.yml)
    echo "==> shared-host mode: joining the edge network, routed by this host's existing Caddy"
elif [[ -n "$site_domain" ]]; then
    COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)
    echo "==> TLS mode: Caddy will serve $site_domain"
else
    COMPOSE=(docker compose -f docker-compose.yml)
    echo "==> plain HTTP: SITE_DOMAIN is empty, so nginx serves port 80 directly"
fi

echo "==> pulling ${IMAGE_TAG}"
"${COMPOSE[@]}" pull backend web

echo "==> starting"
# Flyway runs inside the backend on startup, so there is no separate migration
# step. The backend's own health check does not report ready until it has
# finished, which is what the wait below is watching for.
"${COMPOSE[@]}" up -d --remove-orphans

echo "==> waiting for readiness (up to ${HEALTH_TIMEOUT}s)"
deadline=$(( SECONDS + HEALTH_TIMEOUT ))
# Checked from inside the containers via exec, not curl against a host port:
# in shared-host mode nginx publishes nothing on the host at all, so a host
# port is not something that is always there to check.
until "${COMPOSE[@]}" exec -T web wget -qO- --timeout=5 http://127.0.0.1/healthz >/dev/null 2>&1 \
   && "${COMPOSE[@]}" exec -T backend \
        wget -qO- http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null \
        | grep -q '"status":"UP"'; do
    if (( SECONDS > deadline )); then
        echo "!! did not become ready in ${HEALTH_TIMEOUT}s" >&2
        "${COMPOSE[@]}" ps >&2
        "${COMPOSE[@]}" logs --tail=80 backend web >&2
        exit 1
    fi
    sleep 3
done

echo "==> ready at ${IMAGE_TAG}"

# Images accumulate quickly with a pull on every deploy and will fill a small
# root volume. Only dangling/untagged layers, so a tagged image is never
# removed - a previous release's image needs to stay pullable-free (already
# on disk) in case a rollback lands on it again soon.
docker image prune -f >/dev/null

"${COMPOSE[@]}" ps
