import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core';
import { getChromePath } from 'chrome-launcher';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, '..', '..');
const DIST_DIR = process.env.OPENPANZER_DIST_DIR
  ? path.resolve(process.env.OPENPANZER_DIST_DIR)
  : path.resolve(ROOT_DIR, 'build', 'dist', 'js', 'productionExecutable');
const PORT = parseInt(process.env.OPENPANZER_VERIFY_PORT || '8765', 10);

const MIME_TYPES = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.xml': 'application/xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.wav': 'audio/wav',
  '.mp3': 'audio/mpeg',
  '.ogg': 'audio/ogg',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.eot': 'application/vnd.ms-fontobject',
  '.ico': 'image/x-icon',
};

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function startServer() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const rawUrl = req.url.split('?')[0];
      const filePath = path.join(DIST_DIR, rawUrl === '/' ? 'index.html' : rawUrl);
      const resolved = path.resolve(filePath);
      if (!resolved.startsWith(path.resolve(DIST_DIR))) {
        res.writeHead(403);
        res.end('Forbidden');
        return;
      }
      fs.readFile(filePath, (err, data) => {
        if (err) {
          res.writeHead(404);
          res.end(`Not found: ${req.url}`);
          return;
        }
        const ext = path.extname(filePath).toLowerCase();
        res.writeHead(200, { 'Content-Type': MIME_TYPES[ext] || 'application/octet-stream' });
        res.end(data);
      });
    });
    server.listen(PORT, () => resolve(server));
    server.on('error', reject);
  });
}

async function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  try {
    return await getChromePath();
  } catch (e) {
    console.error('Could not find Chrome. Set CHROME_PATH or install Chrome.');
    throw e;
  }
}

async function main() {
  console.log(`Serving ${DIST_DIR} at http://localhost:${PORT}`);

  if (!fs.existsSync(DIST_DIR)) {
    console.error(`Distribution directory not found: ${DIST_DIR}`);
    console.error('Run: ./gradlew jsBrowserDistribution');
    process.exit(1);
  }

  const server = await startServer();
  const logs = [];
  const errors = [];
  const failedRequests = [];
  const badResponses = [];

  function log(level, text) {
    const entry = `[${level}] ${text}`;
    logs.push(entry);
    console.log(entry);
  }

  let browser;
  try {
    const chromePath = await findChrome();
    console.log(`Using Chrome: ${chromePath}`);

    browser = await puppeteer.launch({
      executablePath: chromePath,
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox'],
    });

    const page = await browser.newPage();

    page.on('console', msg => {
      const text = msg.text();
      const type = msg.type();
      log(type, text);
      if (type === 'error') errors.push(text);
    });

    page.on('pageerror', err => {
      const text = `PAGEERROR: ${err.message}`;
      log('error', text);
      errors.push(text);
    });

    page.on('requestfailed', req => {
      const failure = req.failure();
      const text = `FAILED ${req.url()} : ${failure?.errorText || 'unknown'}`;
      log('warn', text);
      failedRequests.push(text);
    });

    page.on('response', res => {
      const status = res.status();
      if (status >= 400) {
        const text = `HTTP ${status} ${res.url()}`;
        log('warn', text);
        badResponses.push(text);
      }
    });

    await page.goto(`http://localhost:${PORT}/index.html`, {
      waitUntil: 'networkidle0',
      timeout: 30000,
    });

    // Give async scenario/UI initialization time to complete.
    await sleep(10000);

    const state = await page.evaluate(() => {
      const startmenu = document.getElementById('startmenu');
      const smMain = document.getElementById('smMain');
      const smButtons = document.getElementById('smButtons');
      const game = typeof window.game !== 'undefined' ? window.game : null;
      return {
        startmenuExists: !!startmenu,
        startmenuDisplay: startmenu ? startmenu.style.display : null,
        smMainExists: !!smMain,
        smButtonsChildren: smButtons ? smButtons.childElementCount : 0,
        gameExists: !!game,
        gameStarted: game ? game.gameStarted : null,
        gameEnded: game ? game.gameEnded : null,
        scenarioLoaded: game && game.scenario ? game.scenario.isLoaded : null,
        // The dark-mode-extension opt-out (see index.html). Asserted here rather than left to a
        // reader's good intentions: it is two invisible <meta> tags that nothing else references,
        // so a future head edit can drop them without any visible symptom -- until the next player
        // running Dark Reader reports the unit icons missing, which is how this was found.
        darkReaderLock: !!document.querySelector('meta[name="darkreader-lock"]'),
        colorSchemeDark: getComputedStyle(document.documentElement).colorScheme === 'dark',
      };
    });

    log('info', `Page state: ${JSON.stringify(state)}`);

    const success = state.startmenuExists && state.smMainExists &&
      state.darkReaderLock && state.colorSchemeDark && errors.length === 0;

    console.log('\n=== Smoke test result ===');
    console.log(`Start menu built: ${state.startmenuExists}`);
    console.log(`smMain built: ${state.smMainExists}`);
    console.log(`smButtons children: ${state.smButtonsChildren}`);
    console.log(`Game object exists: ${state.gameExists}`);
    console.log(`Scenario loaded: ${state.scenarioLoaded}`);
    console.log(`Dark-mode opt-out (darkreader-lock + color-scheme): ${state.darkReaderLock && state.colorSchemeDark}`);
    console.log(`Runtime JS errors: ${errors.length}`);
    if (errors.length) console.log(errors.join('\n'));
    console.log(`Failed requests: ${failedRequests.length}`);
    if (failedRequests.length) console.log(failedRequests.join('\n'));
    console.log(`HTTP 4xx/5xx responses: ${badResponses.length}`);
    if (badResponses.length) console.log(badResponses.join('\n'));
    console.log(`Overall: ${success ? 'PASS' : 'FAIL'}`);

    await browser.close();
    server.close();
    process.exit(success ? 0 : 1);
  } catch (e) {
    console.error('Smoke test crashed:', e);
    if (browser) await browser.close();
    server.close();
    process.exit(1);
  }
}

main();
