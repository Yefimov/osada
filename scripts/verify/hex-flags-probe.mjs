/**
 * Probe for the 2026-08-16 wrong-flag reports.
 *
 * 103 scenario XMLs still carried `<hex flag="N">` values in their source efile's PRE-MERGE local
 * country numbering, so city/territory flags rendered as an unrelated nation:
 *   Sierra Maestra 1958       enemy hexes flew White Russia    ("enemy has Russian flag")
 *   Cuito Cuanavale           player New Zealand, enemy Spain  ("Australian" / "Spanish")
 *   Khalkhin Gol 2            enemy Poland                     ("flag of Poland")
 *   Spartacist Uprising       Freikorps Portugal, Reds India   ("flag of Portugal")
 *
 * `tools/eqp-merge/fix_hex_flags_from_owner.py` re-derived each from the hex's own `owner`, which is
 * what the engine itself does on capture (`CombatApplication` sets `hex.flag = player.country`).
 * This asserts in the running engine that every flagged hex now resolves to a country one of the
 * scenario's own players actually is, and that the specific wrong flags are gone.
 *
 * Model access note: GameMap is not @JsExport'ed, so its members are name-mangled (`players_1`).
 * The hex grid (`map.map[r][c]`) and `getPlayer(id)` survive unmangled and are what this uses.
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8825;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2500);

// [scenario file, label, flag names that must NOT appear any more]
const CASES = [
  ['cubali01.xml',     'Sierra Maestra 1958',             ['White Russia','Germany']],
  ['battle_cuito.xml', 'Battle of Cuito Cuanavale',       ['New Zealand','Spain']],
  ['khalkin2.xml',     "Khalkhin Gol 2 - Zhukov's Strike",['Poland','Soviet Union']],
  ['sparta.xml',       'Spartacist Uprising - Berlin',    ['Portugal','India']],
];

for (const [file, label, forbidden] of CASES) {
  const info = await page.evaluate(async (file)=>{
    window.game.campaign = null;
    window.game.newScenario(file, 'probe');
    for (let i=0;i<120;i++){ if (window.game.scenario && window.game.scenario.file===file) break;
                             await new Promise(r=>setTimeout(r,100)); }
    await new Promise(r=>setTimeout(r,1200));
    const sc = window.game.scenario;
    if (!sc || sc.file!==file) return {loaded:false, got:sc?sc.file:null};
    const m = sc.map, grid = m.map;

    // Player id -> country. `GameMap.players`/`getPlayer` are both mangled, but every unit holds an
    // unmangled reference to its own Player, and every scenario fields units for both sides.
    const players = {}; let borrow = null;
    for (let r=0;r<m.rows;r++) for (let c=0;c<m.cols;c++) {
      const h = grid[r] && grid[r][c];
      for (const u of [h && h.unit, h && h.airunit]) {
        if (u && u.player && players[u.player.id]===undefined) {
          players[u.player.id] = {country:u.player.country, name:u.player.getCountryName()};
          borrow = borrow || u.player;
        }
      }
    }
    if (!borrow) return {loaded:false, got:'no units found to read players from'};
    const playerCountries = new Set(Object.values(players).map(p=>p.country));

    // Every distinct hex flag, with the owner it sits on.
    const seen = new Map();
    for (let r=0;r<m.rows;r++) for (let c=0;c<m.cols;c++) {
      const h = grid[r] && grid[r][c];
      if (!h || h.flag===undefined || h.flag===-1) continue;
      const k = h.flag+'|'+h.owner;
      if (!seen.has(k)) seen.set(k, {flag:h.flag, owner:h.owner, count:0});
      seen.get(k).count++;
    }
    // Resolve flag ids to names through the engine's own table, borrowing a Player object.
    const saved = borrow.country;
    const entries = [...seen.values()].map(e=>{
      borrow.country = e.flag; const name = borrow.getCountryName();
      return {...e, name};
    });
    borrow.country = saved;

    return {
      loaded:true, name:sc.name, players,
      entries,
      stray: entries.filter(e=>!playerCountries.has(e.flag)),
      mismatched: entries.filter(e=>players[e.owner] && players[e.owner].country!==e.flag),
    };
  }, file);

  if (!info.loaded) { ok(`${label}: scenario loaded`, false, JSON.stringify(info)); continue; }

  const names = info.entries.map(e=>`${e.name}(x${e.count})`).join(' / ');
  const playerDesc = Object.entries(info.players).map(([id,p])=>`p${id}=${p.name}`).join(', ');

  ok(`${label}: every hex flag is one of its own player countries`,
     info.stray.length===0,
     info.stray.length ? 'stray: '+JSON.stringify(info.stray) : `${playerDesc}  ->  ${names}`);
  ok(`${label}: every hex flag matches its own owner's country`,
     info.mismatched.length===0,
     info.mismatched.length ? 'mismatched: '+JSON.stringify(info.mismatched) : 'all consistent');
  for (const bad of forbidden) {
    ok(`${label}: no "${bad}" flag any more`, !info.entries.some(e=>e.name===bad), names);
  }
}

// ---- strategic-zoom unit flag column ----------------------------------------
// strategicUnitFlag returned GameUnit.flag raw as a sprite column, but that value is ONE-BASED
// (the XML writes country + 1). Confirm the premise holds on real scenario data.
const strat = await page.evaluate(()=>{
  const m = window.game.scenario.map, grid = m.map, out = [];
  for (let r=0;r<m.rows && out.length<5;r++) for (let c=0;c<m.cols && out.length<5;c++) {
    const h=grid[r]&&grid[r][c], u=h&&h.unit;
    if (u && u.player) out.push({unitFlag:u.flag, ownerCountry:u.player.country});
  }
  return out;
});
ok('GameUnit.flag really is one-based against its owner country (premise of the fix)',
   strat.length>0 && strat.every(u=>u.unitFlag===u.ownerCountry+1),
   JSON.stringify(strat));

ok('no runtime JS errors', errs.length===0, errs.join(' | '));
console.log('\n=== Hex flag probe ===');
for (const [s,n,e] of results) console.log(`${s}  ${n}${e?'\n        '+e:''}`);
const failed = results.filter(r=>r[0]==='FAIL').length;
console.log(`\n${results.length-failed}/${results.length} passed`);
await browser.close(); server.close();
process.exit(failed?1:0);
