import puppeteer from 'puppeteer-core';
import { getChromePath } from 'chrome-launcher';
const browser = await puppeteer.launch({
  executablePath: await getChromePath(),
  headless: false,
  args: ['--no-sandbox', '--disable-setuid-sandbox'],
});
const page = await browser.newPage();
page.on('console', msg => console.log('CONSOLE', msg.type(), msg.text()));
page.on('pageerror', err => console.log('PAGEERROR', err.message));
page.on('request', req => console.log('REQ', req.method(), req.url()));
page.on('requestfailed', req => console.log('REQFAIL', req.url(), req.failure()?.errorText));
await page.goto('http://localhost:9876/?id=debug123');
await new Promise(r => setTimeout(r, 5000));
await browser.close();
