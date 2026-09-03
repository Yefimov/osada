/**
 * Probe for the P0 save-recovery gaps closed on 2026-08-16
 * (`docs/player-comfort-roadmap.md` -> "P0 - progress safety").
 *
 * Each check corresponds to one gap found reviewing the workstream:
 *   1. restore() tried the campaign repository first and returned unconditionally, so a standalone
 *      scenario refresh came back to an unrelated CAMPAIGN once any campaign run existed...
 *   2. ...and an unloadable campaign run took the whole restore down with it, never reaching the
 *      standalone/legacy snapshots written directly below it in the same function.
 *   3. A generation was only phase-validated on WRITE, so an imported/migrated one containing no
 *      units was restored instead of falling back to the recovery generation.
 *   4. pruneOrphans documented removing generation keys with no index row but never did, leaking
 *      ~130 KB of quota per orphan forever.
 *   5. "Completed" was derived from `phase == "scenarioEnd"`, which derivePhase never produces.
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8832;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);

const CONSTANTS = path.resolve(__dirname,'..','..','src','jsMain','kotlin','org','osada','Constants.kt');
const major = (fs.readFileSync(CONSTANTS,'utf8').match(/const val VERSION = "(\d+)\.(\d+)/)||[]).slice(1,3).join('.');

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2500);

const state = () => page.evaluate(()=>({
  started: !!window.game.gameStarted, scenario: window.game.scenario?.name ?? null,
}));

// ---- 1 & 2: recency ordering and fall-through -------------------------------
// Play a standalone scenario so the engine writes its own disposable session snapshot.
await page.evaluate(()=>{ localStorage.clear(); window.game.campaign=null; window.game.newScenario('khalkin1.xml','probe'); });
await sleep(4000);
await page.evaluate(()=>window.game.state.save());
await sleep(400);
const played = await state();
ok('standalone scenario played and saved', played.started && !!played.scenario, JSON.stringify(played));

// A campaign run that is NEWER by timestamp but whose only generation is unloadable. Before the
// fix this both outranked and then killed the standalone snapshot.
const seedBadCampaign = (major) => {
  const snap = JSON.stringify({id:'x',campaignRunId:'old.json',kind:'autosave',createdAt:1,gameVersion:'p',
    saveFormat:4,scenarioFile:'probe.xml',scenarioName:'BROKEN CAMPAIGN',turn:9,maxTurns:20,
    phase:'playerTurn',campaignFile:'old.json',campaignScenario:2,payload:'{"fmt":4,"scenario":{"name":"BROKEN"}}'});
  const idx = JSON.stringify({rows:[{campaignRunId:'old.json',campaignFile:'old.json',campaignName:'old.json',
    scenarioName:'BROKEN CAMPAIGN',campaignScenario:2,phase:'playerTurn',lastPlayedAt:Date.now()+999999,
    completed:false,turn:9,maxTurns:20}]});
  localStorage.setItem('osada-save-index-'+major, idx);
  localStorage.setItem('osada-save-run-'+major+'-old.json-current', snap);
};
await page.evaluate(seedBadCampaign, major);
await page.reload({waitUntil:'networkidle2'}); await sleep(4500);
const afterBadCampaign = await state();
ok('an unloadable campaign run falls through to the standalone session',
   afterBadCampaign.started && afterBadCampaign.scenario === played.scenario,
   JSON.stringify(afterBadCampaign));

// ---- 3: a units-less generation is rejected on READ --------------------------
await page.evaluate((major)=>{
  const empty = JSON.stringify({fmt:4, scenario:{turn:1,maxTurns:20,map:{hexes:[[{}]]},reinforcements:{}},
                                players:[{coreUnits:[]}]});
  const snap = JSON.stringify({id:'e',campaignRunId:'empty.json',kind:'autosave',createdAt:1,gameVersion:'p',
    saveFormat:4,scenarioFile:'probe.xml',scenarioName:'EMPTY',turn:1,maxTurns:20,phase:'playerTurn',
    campaignFile:'empty.json',campaignScenario:0,payload:empty});
  const idx = JSON.stringify({rows:[{campaignRunId:'empty.json',campaignFile:'empty.json',campaignName:'empty.json',
    scenarioName:'EMPTY',campaignScenario:0,phase:'playerTurn',lastPlayedAt:Date.now()+999999,completed:false,
    turn:1,maxTurns:20}]});
  localStorage.setItem('osada-save-index-'+major, idx);
  localStorage.setItem('osada-save-run-'+major+'-empty.json-current', snap);
}, major);
await page.reload({waitUntil:'networkidle2'}); await sleep(4500);
const afterEmpty = await state();
ok('a generation containing no units is rejected on read, not restored',
   afterEmpty.scenario === played.scenario,
   JSON.stringify(afterEmpty));

// ---- 4: pruneOrphans clears unreferenced generation keys --------------------
const pruned = await page.evaluate((major)=>{
  const orphanKey = 'osada-save-run-'+major+'-ghost.json-current';
  localStorage.setItem(orphanKey, '{"id":"g","campaignRunId":"ghost.json","payload":"{}"}');
  const before = !!localStorage.getItem(orphanKey);
  window.game.state.restore(()=>{}, ()=>{});   // restore() runs the startup prune
  return {before, after: !!localStorage.getItem(orphanKey)};
}, major);
ok('pruneOrphans removes a generation key with no index row', pruned.before && !pruned.after, JSON.stringify(pruned));

// ---- 5: Completed is reachable and honest about defeat ----------------------
const completed = await page.evaluate((major)=>{
  localStorage.clear();
  const payload = JSON.stringify({fmt:4,scenario:{name:'X',turn:1,maxTurns:9,map:{hexes:[[{}]]},reinforcements:{}},
                                  players:[{coreUnits:[{}]}],campaign:{id:0,file:'c.json',scenario:0}});
  const snap = JSON.stringify({id:'c1',campaignRunId:'c.json',kind:'autosave',createdAt:5,gameVersion:'p',
    saveFormat:4,scenarioFile:'x.xml',scenarioName:'X',turn:1,maxTurns:9,phase:'playerTurn',
    campaignFile:'c.json',campaignScenario:0,payload});
  const idx = JSON.stringify({rows:[{campaignRunId:'c.json',campaignFile:'c.json',campaignName:'c.json',
    scenarioName:'X',campaignScenario:0,phase:'playerTurn',lastPlayedAt:5,completed:false,turn:1,maxTurns:9}]});
  localStorage.setItem('osada-save-index-'+major, idx);
  localStorage.setItem('osada-save-run-'+major+'-c.json-current', snap);
  // Kotlin returns a List, not a JS array: round-trip it so the rows are plain objects. Property
  // names keep their `_1` mangling suffix because CampaignRunMetadata is not @JsExport'ed.
  const rows = () => JSON.parse(JSON.stringify(window.game.state.listCampaignRuns()));
  const before = rows()[0];
  window.game.state.markCampaignRunCompleted('c.json','lose');
  const after = rows()[0];
  return {beforeCompleted: before.completed_1, afterCompleted: after.completed_1, afterOutcome: after.outcome_1};
}, major);
ok('a run can be marked completed at campaign end',
   pruned && completed.beforeCompleted === false && completed.afterCompleted === true, JSON.stringify(completed));
ok('a campaign that ended in defeat records that outcome',
   completed.afterOutcome === 'lose', JSON.stringify(completed));

ok('no runtime JS errors', errs.length===0, errs.join(' | '));
console.log('\n=== Save-recovery P0 probe ===');
for (const [s,n,e] of results) console.log(`${s}  ${n}${e?'\n        '+e:''}`);
const failed = results.filter(r=>r[0]==='FAIL').length;
console.log(`\n${results.length-failed}/${results.length} passed`);
await browser.close(); server.close();
process.exit(failed?1:0);
