import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8822;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=rq.url.split('?')[0];const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage();
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('bn5s02.xml','x');});
await sleep(3500);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(500);

const before = await page.evaluate(() => window.game.scenario.map.currentPlayer.country);
console.log('current player country before end-turn:', before);

// End turn via the real UI mechanism (same as hud-probe.mjs) to advance to the next player.
await page.evaluate(async () => {
  window.game.ui.onEndTurnClick();
  await new Promise(r => setTimeout(r, 50));
  const yes = document.querySelector('#osadaEndTurn .osada-et__yes');
  if (yes) yes.click(); else document.getElementById('osadaEndTurn')?.click();
});
await sleep(600);

const after = await page.evaluate(() => window.game.scenario.map.currentPlayer.country);
console.log('current player country after end-turn:', after);

const filterCompare = await page.evaluate(() => {
  window.game.ui.mainMenuButton('buy');
  const withFilter = document.querySelectorAll('#eqUnitList .eqUnitBox').length;
  window.game.ui.mainMenuButton('buy'); // close
  window.Equipment.set_availabilityFilterEnabled_6gz0jo_k$(false);
  window.game.ui.mainMenuButton('buy'); // reopen -> re-render
  const withoutFilter = document.querySelectorAll('#eqUnitList .eqUnitBox').length;
  window.Equipment.set_availabilityFilterEnabled_6gz0jo_k$(true);
  return { withFilter, withoutFilter };
});
console.log('buy list: filtered =', filterCompare.withFilter, ' unfiltered =', filterCompare.withoutFilter);

const results = [];
const ok = (n,c,extra) => results.push([c?'PASS':'FAIL', n, extra]);
ok('turn advanced to a different player/country', after !== before, `${before} -> ${after}`);
ok('buy list non-empty', filterCompare.withFilter > 0, filterCompare.withFilter);
ok('disabling filter changes list size', filterCompare.withoutFilter !== filterCompare.withFilter,
   `filtered=${filterCompare.withFilter} unfiltered=${filterCompare.withoutFilter}`);
ok('no page errors', errs.length === 0, errs.slice(0,3).join(' | '));
console.log('\n==== REAL-TURN FILTER CHECK ====');
for (const [s,n,extra] of results) console.log(`${s}  ${n}  [${extra}]`);
console.log(`${results.filter(r=>r[0]==='PASS').length}/${results.length} passed`);
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
