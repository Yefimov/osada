/**
 * Probe for the 2026-08-19 UI feedback round: goofy/glyph buttons, back-button overflow,
 * restart-mission native confirm, undo/ammo placement, campaign page Rules button.
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8905;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1400,height:900});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));

const SHOT_DIR = path.resolve(__dirname, '..', '..', 'tmp', 'feedback-shots');
fs.mkdirSync(SHOT_DIR, {recursive:true});
async function shot(name){ await page.screenshot({path: path.join(SHOT_DIR, name+'.png')}); }

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2000);

// ---- Main menu -> Controls card (F1) ----
await page.keyboard.press('F1'); await sleep(300);
await shot('01-controls-card');

await page.keyboard.press('Escape'); await sleep(300);

// ---- Campaign selection ----
await page.evaluate(()=>window.game.ui.startMenuButton('newcampaign'));
await sleep(500);
await shot('02-campaign-selection');
const campInfo = await page.evaluate(()=>{
  const back = document.getElementById('smCBackBut');
  const rules = document.getElementById('osadaRulesButton-campaign');
  const exportBtn = document.getElementById('campaignRunExport');
  const r = back ? back.getBoundingClientRect() : null;
  const after = back ? getComputedStyle(back, '::after') : null;
  return {
    backRect: r ? [r.left, r.top, r.width, r.height] : null,
    backAfterWidth: after ? after.width : null,
    rulesButtonPresent: !!rules,
    rulesButtonText: rules ? rules.textContent : null,
    exportPresent: !!exportBtn,
    exportText: exportBtn ? exportBtn.textContent : null,
  };
});
console.log('campInfo', JSON.stringify(campInfo));

// open Rules window from campaign selection
await page.evaluate(()=>document.getElementById('osadaRulesButton-campaign')?.click());
await sleep(300);
await shot('03-rules-window');
const rulesInfo = await page.evaluate(()=>{
  const win = document.getElementById('osadaRulesWindow');
  const select = document.querySelector('.osadaRulesRow');
  const aa = document.querySelector('[data-rule="aa_intercept_mode"]');
  return {
    windowPresent: !!win,
    closeBtnClass: document.querySelector('.osadaRulesWindow__close')?.className,
    closeBtnText: document.querySelector('.osadaRulesWindow__close')?.textContent,
  };
});
console.log('rulesInfo', JSON.stringify(rulesInfo));
// close rules, open editor to see aa_intercept_mode select (first .osadaRulesAction = "edit copy")
await page.evaluate(()=>{
  const acts = [...document.querySelectorAll('.osadaRulesAction')];
  acts[0]?.click();
});
await sleep(300);
await shot('04-rules-editor');
const editorInfo = await page.evaluate(()=>{
  const row = document.querySelector('[data-rule="aa_intercept_mode"]');
  const sel = row?.querySelector('select');
  const r = sel?.getBoundingClientRect();
  const winRect = document.getElementById('osadaRulesEditorWindow')?.getBoundingClientRect()
    || document.querySelector('.osadaRulesWindow')?.getBoundingClientRect();
  const cancel = document.querySelector('.osadaRulesActions button');
  return {
    selectRect: r ? [r.left, r.top, r.width, r.height] : null,
    winRect: winRect ? [winRect.left, winRect.top, winRect.width, winRect.height] : null,
    cancelClass: cancel?.className,
    cancelText: cancel?.textContent,
  };
});
console.log('editorInfo', JSON.stringify(editorInfo));

await page.evaluate(()=>{document.getElementById('osadaRulesWindow') && document.querySelectorAll('button').forEach(b=>{ if(/close/i.test(b.className)) b.click(); });});
await sleep(300);

// ---- Scenario selection ----
await page.evaluate(()=>{window.game.ui.startMenuButton('newscenario');});
await sleep(500);
await shot('05-scenario-selection');
const scenInfo = await page.evaluate(()=>{
  const back = document.getElementById('smSBackBut');
  const r = back ? back.getBoundingClientRect() : null;
  const after = back ? getComputedStyle(back, '::after') : null;
  const rules = document.getElementById('osadaRulesButton-scenario');
  return {
    backRect: r ? [r.left, r.top, r.width, r.height] : null,
    backAfterWidth: after ? after.width : null,
    rulesPresent: !!rules,
  };
});
console.log('scenInfo', JSON.stringify(scenInfo));

// ---- Restart mission confirm (native window.confirm would hang puppeteer unless handled) ----
page.on('dialog', async d => { console.log('NATIVE DIALOG FIRED:', d.type(), d.message()); await d.dismiss(); });
await page.evaluate(()=>window.game.ui.startMenuButton('newgame'));
await sleep(300);
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('drpzop01.xml','x');});
await sleep(3000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut')?.click();});
await sleep(500);
// open options -> restart mission
await page.evaluate(()=>{document.getElementById('options')?.click();});
await sleep(300);
await page.evaluate(()=>window.game.ui.startMenuButton('restartmission'));
await sleep(300);
await shot('08-restart-confirm');
const restartInfo = await page.evaluate(()=>{
  const card = document.getElementById('osadaConfirmCard');
  return { present: !!card, text: card?.textContent };
});
console.log('restartInfo', JSON.stringify(restartInfo));
await page.keyboard.press('Escape'); await sleep(200);

// ---- Start a scenario to check in-game HUD (Undo, Ammo) ----
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('drpzop01.xml','x');});
await sleep(3000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut')?.click();});
await sleep(600);
await shot('06-ingame-hud');

const hudInfo = await page.evaluate(()=>{
  const undo = document.getElementById('undo') || [...document.querySelectorAll('*')].find(e=>e.id && /undo/i.test(e.id));
  const reinforce = [...document.querySelectorAll('*')].find(e=>e.id && /reinforce/i.test(e.id));
  const ammoLine = [...document.querySelectorAll('*')].find(e=>e.textContent && e.textContent.trim().toLowerCase().startsWith('ammo') && e.children.length===0);
  const vh = window.innerHeight;
  const r = el => el ? { id: el.id, rect: (()=>{const b=el.getBoundingClientRect(); return [Math.round(b.left),Math.round(b.top),Math.round(b.width),Math.round(b.height)];})() } : null;
  return { undo: r(undo), reinforce: r(reinforce), ammoLine: r(ammoLine), viewportHeight: vh };
});
console.log('hudInfo', JSON.stringify(hudInfo));

// select a unit to reveal action bar (undo/reinforce/ammo), try clicking first visible unit on canvas
await page.evaluate(()=>{
  // try common hooks used by other probes
  if (window.game && window.game.ui && window.game.selectedUnit===undefined) { /* no-op */ }
});
await sleep(300);
await shot('07-ingame-hud-2');

console.log('PAGE ERRORS', JSON.stringify(errs));
await browser.close(); server.close();
