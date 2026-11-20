# vf-sync gateway. Build from the repository root:
#   docker build -f sync/Dockerfile -t vf-sync:<version> .
# Only sync/ and pack/schema/ are copied in; there are no npm dependencies.
FROM node:22-alpine

WORKDIR /app
COPY pack/schema/ /app/pack/schema/
COPY sync/package.json /app/sync/package.json
COPY sync/src/ /app/sync/src/
COPY sync/bin/ /app/sync/bin/

ENV NODE_ENV=production \
    VF_SYNC_CONFIG=/config/config.json
USER node
EXPOSE 8790
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:8790/healthz').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"]
CMD ["node", "/app/sync/bin/vf-sync.mjs"]
