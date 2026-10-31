# Build from the repo root: docker build -f mcp/Dockerfile .
FROM node:24.21.0-alpine3.24 AS build
WORKDIR /app
RUN corepack enable
COPY mcp/package.json mcp/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY mcp/tsconfig.json ./
COPY mcp/src ./src
RUN pnpm build && pnpm prune --prod

FROM node:24.21.0-alpine3.24
WORKDIR /app
ARG VERSION=dev
ENV SAMU_VERSION=$VERSION
ENV NODE_ENV=production
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
# The capability manifest and skills live at the repo root, so the build context is the repo root (-f mcp/Dockerfile).
COPY capabilities ./capabilities
COPY skills ./skills
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
CMD ["node", "dist/index.js"]
