// Q5.15: Playwright browser server for PDF generation sidecar.
//
// Launches a persistent headless Chromium and exposes it over WebSocket at a
// fixed path (ws://host:PORT/WS_PATH) so the Java client can connect with a
// deterministic URL set via APP_PDF_BROWSER_WS_ENDPOINT.
//
// Uses the "playwright" package (bundles browsers) instead of playwright-core
// so the browser binary and library stay in lockstep.
const { chromium } = require('playwright');

const PORT = parseInt(process.env.PLAYWRIGHT_PORT || '3000', 10);
const WS_PATH = process.env.PLAYWRIGHT_WS_PATH || 'playwright';

(async () => {
    try {
        const server = await chromium.launchServer({
            headless: true,
            port: PORT,
            wsPath: WS_PATH,
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--disable-gpu',
            ],
        });

        console.log(`Playwright browser server listening at ${server.wsEndpoint()}`);

        const shutdown = async (signal) => {
            console.log(`Received ${signal}, shutting down Playwright server...`);
            try {
                await server.close();
            } catch (err) {
                console.error('Error closing server:', err);
            }
            process.exit(0);
        };
        process.on('SIGTERM', () => shutdown('SIGTERM'));
        process.on('SIGINT', () => shutdown('SIGINT'));
    } catch (err) {
        console.error('Failed to start Playwright server:', err);
        process.exit(1);
    }
})();
