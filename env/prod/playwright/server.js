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

// launchServer's host defaults to 'localhost', which resolves to [::1] and
// accepts connections only from inside this container — the API, reaching us
// by service name over the compose network, gets ECONNREFUSED and every PDF
// request fails. 1.51.0 bound broadly and hid this; the 1.62.0 upgrade did not
// change the default so much as start honouring it.
//
// Playwright's own types warn that an explicit address exposes the browser RPC
// to anything that can reach the port. Port 3000 is exposed but never
// published (`"3000/tcp": null`), so the blast radius is the compose network,
// which is exactly who needs to connect.
const HOST = process.env.PLAYWRIGHT_HOST || '0.0.0.0';

(async () => {
    try {
        const server = await chromium.launchServer({
            headless: true,
            host: HOST,
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
