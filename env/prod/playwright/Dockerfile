# Q5.15: Playwright Chromium sidecar for PDF generation.
#
# Uses the official Playwright image which already ships with all supported
# browsers + system dependencies in the correct layout. Separating this image
# from the main JRE image saves ~500MB on the backend image.
#
# The main backend image is built with PLAYWRIGHT_REMOTE=true so it skips the
# Chromium install and connects here over WebSocket via APP_PDF_BROWSER_WS_ENDPOINT.

FROM mcr.microsoft.com/playwright:v1.62.0-noble

WORKDIR /app

# We only need the playwright library + a tiny server script. playwright is
# already present in the base image, but we pin the version to match the client.
RUN npm install --omit=dev playwright@1.62.0 \
    && npm cache clean --force

COPY server.js .

# The base image runs as root; drop to the pre-existing pwuser account.
RUN chown -R pwuser:pwuser /app
USER pwuser

EXPOSE 3000

# Healthcheck: probe the TCP port. Playwright's launchServer() speaks WS-only,
# so HTTP GET / returns a 400 but the TCP connection still succeeds, which is
# what bash's /dev/tcp test exercises. Node one-liner used so we don't rely
# on bash inside the container.
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD node -e "require('net').connect(3000,'127.0.0.1').on('connect',()=>process.exit(0)).on('error',()=>process.exit(1))" || exit 1

CMD ["node", "server.js"]
