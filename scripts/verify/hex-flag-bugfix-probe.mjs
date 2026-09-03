import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8820;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=rq.url.split('?')[0];const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage();
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);

// The user's exact bug report: "Great Patriotic War" campaign, Operation Uranus (ruscam00.xml,
// eqp-adlerkorps), opponent hexes showed a Netherlands flag instead of Romania.
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('ruscam00.xml','x');});
for (let i = 0; i < 40; i++) {
  const remaining = await page.evaluate(() => window.Equipment.equipmentToLoad_uwdlva_k$ ? window.Equipment.equipmentToLoad_uwdlva_k$() : 0);
  if (remaining === 0) break;
  await sleep(250);
}
await sleep(1000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(500);

const info = await page.evaluate(() => {
  const map = window.game.scenario.map;
  const players = map.getPlayers();
  const E = window.Equipment;
  // Find a hex owned by player 1 (the opponent, Romania) and check its resolved flag/country name.
  let ownedHex = null;
  const grid = map.map;
  outer:
  for (let r = 0; r < grid.length; r++) {
    for (let c = 0; c < grid[r].length; c++) {
      const hex = grid[r][c];
      if (hex && hex.flag !== -1 && hex.owner === 1) { ownedHex = hex; break outer; }
    }
  }
  return {
    players: players.map(p => ({ id: p.id, country: p.country, name: E.getCountryName_56hbzh_k$(p.country) })),
    ownedHexFlag: ownedHex ? ownedHex.flag : null,
    ownedHexCountryName: ownedHex ? E.getCountryName_56hbzh_k$(ownedHex.flag) : null,
  };
});
console.log(JSON.stringify(info, null, 2));

const results = [];
const ok = (n,c,extra) => results.push([c?'PASS':'FAIL', n, extra]);
ok('player 1 (opponent) resolves to Romania', info.players.find(p=>p.id===1)?.name === 'Romania', JSON.stringify(info.players));
ok('an opponent-owned hex flag resolves to Romania (not Netherlands)', info.ownedHexCountryName === 'Romania', info.ownedHexCountryName);
ok('no page errors', errs.length===0, errs.slice(0,3).join(' | '));

console.log('\n==== HEX FLAG BUGFIX PROBE ====');
for (const [s,n,extra] of results) console.log(`${s}  ${n}${extra!==undefined?'  ['+extra+']':''}`);
console.log(`${results.filter(r=>r[0]==='PASS').length}/${results.length} passed`);
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
