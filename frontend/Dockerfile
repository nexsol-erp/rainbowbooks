# syntax=docker/dockerfile:1
#
# Built from the repository root, not from frontend/, because the image needs
# nginx/default.conf as well as the app. Compose sets `context: .` and
# `dockerfile: frontend/Dockerfile`; .dockerignore keeps the context small.

# ---------------------------------------------------------------------------
# Build
#
# Vite inlines VITE_* variables at build time, so the store name and WhatsApp
# number have to be present here rather than at run time. They are build
# arguments, not secrets - everything in this bundle is public by definition.
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /build

# lockfile first, so a source edit does not reinstall every dependency
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/ ./

ARG VITE_STORE_NAME="Rainbow Books"
ARG VITE_STORE_TAGLINE="Handwoven coir craft"
ARG VITE_CURRENCY=INR
ARG VITE_LOCALE=en-IN

ENV VITE_STORE_NAME=$VITE_STORE_NAME \
    VITE_STORE_TAGLINE=$VITE_STORE_TAGLINE \
    VITE_CURRENCY=$VITE_CURRENCY \
    VITE_LOCALE=$VITE_LOCALE

RUN npm run build

# ---------------------------------------------------------------------------
# Serve
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

COPY --from=build /build/dist /usr/share/nginx/html
# default.conf plus the two snippets it includes
COPY nginx/default.conf nginx/security-headers.conf nginx/proxy.conf /etc/nginx/conf.d/

# The master process runs as root so it can bind :80; workers drop to the
# unprivileged nginx user, which the base image already configures.
EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  # 127.0.0.1 rather than localhost: explicit about the family, so the check
  # cannot be defeated by name resolution preferring IPv6
  CMD wget -qO- http://127.0.0.1/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
