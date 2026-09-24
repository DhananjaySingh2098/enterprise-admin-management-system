# syntax=docker/dockerfile:1.7
# Enterprise Admin frontend — production image: Angular production build served by unprivileged nginx, which also
# reverse-proxies /api/** to the backend. No dev server, no Node.js and no sources in the final image.
#   build:  docker build -t enterprise-admin-frontend ./frontend

# ---------------------------------------------------------------- build
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
# The CSP check runs before and after the build: headers, boot-script hash, no inline styles/scripts, no source maps.
RUN npm run -s check:csp \
 && npx ng build --configuration production \
 && npm run -s check:csp

# ---------------------------------------------------------------- runtime
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime

LABEL org.opencontainers.image.title="enterprise-admin-frontend" \
      org.opencontainers.image.description="Enterprise Admin Management System — Angular SPA on nginx (serves /api via reverse proxy)" \
      org.opencontainers.image.licenses="Proprietary"

USER root
RUN rm -f /usr/share/nginx/html/* /etc/nginx/conf.d/default.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist/frontend/browser/ /usr/share/nginx/html/
RUN chown -R 101:101 /etc/nginx/conf.d
USER 101

# Defaults for docker-compose.prod.yml; override per environment.
ENV BACKEND_UPSTREAM=backend:8080 \
    NGINX_RESOLVER=127.0.0.11 \
    NGINX_TRUST_FORWARDED_PROTO=off

EXPOSE 8080

# Proves nginx really serves the application (not just that the process is alive).
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/ | grep -q '<app-root' || exit 1
