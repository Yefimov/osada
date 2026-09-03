/**
 * Probe for the 2026-08-16 Continue-button regression.
 *
 * The main menu's Continue button was keyed off `osada-scenario-<major>`, a legacy localStorage key
 * the per-campaign-run save repository no longer writes (and `clear()` deletes), so it stayed hidden
 * even with a perfectly good save in storage.
 *
 * It drives the same path the player does. Two things make that path specific:
 *   * `MainMenuButtonHandler.mainMenuButton` starts with `ui.game.scenario?.map ?: return`, so the
 *     Options button is inert until a scenario is loaded -- the probe loads one first.
 *   * On boot a valid save auto-restores straight into the game, so the menu is never shown and the
 *     button never consulted. Opening the menu mid-session is exactly the reported case
 *     ("in main menu Continue button not exists now").
 *
 * The first in-game check uses the save the ENGINE ITSELF wrote, not a hand-seeded one, so it tests
 * the real save -> Continue loop end to end. The remaining cases seed the two fallback sources
 * `savedGameSummary()` also honours (standalone session snapshot, legacy pre-repository keys).
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8823;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);

// Storage keys use the ENGINE version (Constants.kt VERSION, currently "3.3.0" -> "3.3"), which is
// deliberately NOT the "v0.5" shown in the menu -- that display string is decoupled precisely so it
// can change without orphaning saves. Read the real one rather than hardcoding it.
const CONSTANTS = path.resolve(__dirname,'..','..','src','jsMain','kotlin','org','osada','Constants.kt');
const major = (fs.readFileSync(CONSTANTS,'utf8').match(/const val VERSION = "(\d+)\.(\d+)/)||[]).slice(1,3).join('.');
ok('read the storage major version from Constants.kt', /^\d+\.\d+$/.test(major), major);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2500);

const readButton = () => page.evaluate(()=>{
  const b=document.getElementById('continuegame');
  if(!b) return {exists:false};
  return {exists:true, hidden:getComputedStyle(b).display==='none',
          sub:b.querySelector('.osada-menu-btn__sub')?.textContent||''};
});

// ---- 1. fresh boot, nothing stored -> Continue hidden ------------------------
const atBoot = await readButton();
ok('fresh boot with no save -> Continue hidden', atBoot.exists && atBoot.hidden, JSON.stringify(atBoot));

// ---- load a scenario so the Options button is live --------------------------
await page.evaluate(()=>window.game.ui.startMenuButton('newscenario'));
await sleep(600);
await page.evaluate(()=>window.game.ui.startNewScenario
  ? window.game.ui.startNewScenario('khalkin1.xml','probe')
  : window.game.newScenario('khalkin1.xml','probe'));
await sleep(4000);
const loaded = await page.evaluate(()=>({
  started: !!(window.game && window.game.gameStarted),
  scenario: window.game?.scenario?.name ?? null,
  turn: window.game?.scenario?.map?.turn ?? null,
  maxTurns: window.game?.scenario?.maxTurns ?? null,
}));
ok('scenario loaded for the in-game menu checks', loaded.started && !!loaded.scenario, JSON.stringify(loaded));

const menuVisible = () => page.evaluate(()=>getComputedStyle(document.getElementById('startmenu')).display!=='none');
const clickOptions = async () => { await page.evaluate(()=>document.getElementById('options').click()); await sleep(350); };

/**
 * Reads the button after forcing a fresh `applyContinueButtonState()`.
 *
 * Only the OPEN half of `onOptionsButton` recomputes the button; the close half just hides. So the
 * menu must be shut before the click that matters -- otherwise every reading is one step stale,
 * which is exactly what this probe did until the state check below was added.
 */
async function openMenuAndRead() {
  if (await menuVisible()) await clickOptions();   // close, so the next click is a real open
  await clickOptions();                            // open -> recompute
  const state = await readButton();
  await clickOptions();                            // leave it closed for the next case
  return state;
}

/** Replaces storage wholesale, then re-opens the menu. */
async function seedAndRead(seed) {
  await page.evaluate((s)=>{ localStorage.clear(); (0, eval)(s); }, seed);
  return openMenuAndRead();
}

// ---- 2. the save the engine itself just wrote -> Continue shown --------------
await page.evaluate(()=>window.game.state.save());
await sleep(500);
const realSave = await openMenuAndRead();
ok('engine-written save -> Continue visible', realSave.exists && !realSave.hidden, JSON.stringify(realSave));
ok('...annotated with the real scenario name',
   !!loaded.scenario && realSave.sub.includes(loaded.scenario), JSON.stringify(realSave.sub));

// ---- 3. a campaign run in the repository -> Continue shown + annotated -------
const payload = JSON.stringify({fmt:4, scenario:{file:'probe.xml',name:'Probe Scenario',turn:3,maxTurns:20},
                                players:[], campaign:{id:0,file:'probe.json',scenario:2}});
const snapshot = JSON.stringify({id:'probe-1',campaignRunId:'probe.json',kind:'autosave',createdAt:1000,
  gameVersion:'probe',saveFormat:4,scenarioFile:'probe.xml',scenarioName:'Probe Scenario',
  turn:7,maxTurns:20,phase:'playerTurn',campaignFile:'probe.json',campaignScenario:2,payload});
const index = JSON.stringify({rows:[{campaignRunId:'probe.json',campaignFile:'probe.json',
  campaignName:'probe.json',scenarioName:'Probe Scenario',campaignScenario:2,phase:'playerTurn',
  lastPlayedAt:1000,completed:false,turn:7,maxTurns:20}]});
const run = await seedAndRead(
  `localStorage.setItem('osada-save-index-${major}', ${JSON.stringify(index)});` +
  `localStorage.setItem('osada-save-run-${major}-probe.json-current', ${JSON.stringify(snapshot)});`
);
ok('campaign run in repository -> Continue visible', run.exists && !run.hidden, JSON.stringify(run));
ok('...annotated with that run\'s scenario name', /Probe Scenario/.test(run.sub), JSON.stringify(run.sub));
ok('...and its turn position, read from the index row', /7/.test(run.sub) && /20/.test(run.sub), JSON.stringify(run.sub));

// ---- 4. standalone/tutorial session snapshot -> Continue shown ---------------
const standalone = await seedAndRead(
  `localStorage.setItem('osada-standalone-session-${major}', ${JSON.stringify(payload)});`);
ok('standalone session snapshot -> Continue visible', standalone.exists && !standalone.hidden, JSON.stringify(standalone));
ok('...annotated from the standalone payload', /Probe Scenario/.test(standalone.sub), JSON.stringify(standalone.sub));

// ---- 5. a legacy pre-repository save still offers Continue -------------------
const legacy = await seedAndRead(
  `localStorage.setItem('osada-scenario-${major}', ${JSON.stringify(JSON.stringify({name:'Legacy Scenario',turn:5,maxTurns:12}))});` +
  `localStorage.setItem('osada-players-${major}', '[]');`);
ok('legacy pre-repository save -> Continue visible', legacy.exists && !legacy.hidden, JSON.stringify(legacy));
ok('...annotated from the legacy key', /Legacy Scenario/.test(legacy.sub), JSON.stringify(legacy.sub));

// ---- 6. back to empty -> hidden again (no sticky state) ----------------------
const emptyAgain = await seedAndRead(`void 0;`);
ok('clearing storage hides Continue again', emptyAgain.exists && emptyAgain.hidden, JSON.stringify(emptyAgain));

// ---- report ------------------------------------------------------------------
ok('no runtime JS errors', errs.length===0, errs.join(' | '));
console.log('\n=== Continue button probe ===');
for (const [s,n,e] of results) console.log(`${s}  ${n}${e?'   -- '+e:''}`);
const failed = results.filter(r=>r[0]==='FAIL').length;
console.log(`\n${results.length-failed}/${results.length} passed`);
await browser.close(); server.close();
process.exit(failed?1:0);
