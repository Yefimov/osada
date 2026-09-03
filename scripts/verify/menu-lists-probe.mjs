/**
 * Probe for the 2026-07-13 round: scenario list regrouped by campaign, campaign rename,
 * register sort/filter toolbars, dossier frame revert, and the empty-briefing fix.
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8817;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2000);

// ---- open Scenario Selection -------------------------------------------------
await page.evaluate(()=>window.game.ui.startMenuButton('newscenario'));
await sleep(400);

const scen = await page.evaluate(()=>{
  const list=document.getElementById('osadaScenList');
  const rows=[...list.children];
  const groups=rows.filter(r=>r.classList.contains('osadaListRow--group')).map(r=>r.textContent);
  const first=rows.find(r=>!r.classList.contains('osadaListRow--group'));
  return {
    groups, rowCount:rows.length,
    hasToolbar: !!document.querySelector('#smScenRegister .osadaListTools'),
    sortButtons: [...document.querySelectorAll('#smScenRegister .osadaListSorts .osada-seg')].map(s=>s.textContent),
    firstRowName: first?.querySelector('.osadaListRowName')?.textContent,
    firstRowSub: first?.querySelector('.osadaListRowSub')?.textContent,
  };
});
ok('scenario groups are campaigns, not efiles', !scen.groups.some(g=>g.includes('Open General Imports')), scen.groups.length+' groups');
ok('Denikin campaign group present + renamed',
   scen.groups.some(g=>g.includes('The Defeat of Denikin')) && !scen.groups.some(g=>g.includes('Red Volunteer Army')));
ok('scenario titles lost the "(Label)" suffix', !/\(LXF Red Army\)/.test(scen.firstRowName||''), JSON.stringify(scen.firstRowName));
ok('scenario row shows its campaign as subtitle', !!scen.firstRowSub, JSON.stringify(scen.firstRowSub));
ok('scenario toolbar built', scen.hasToolbar && scen.sortButtons.length===2, scen.sortButtons.join('/'));

// ---- filter --------------------------------------------------------------------
const filtered = await page.evaluate(async ()=>{
  const inp=document.querySelector('#smScenRegister .osadaListFilter');
  inp.value='denikin'; inp.oninput();
  const rows=[...document.getElementById('osadaScenList').children];
  const visible=rows.filter(r=>r.style.display!=='none');
  const visibleGroups=visible.filter(r=>r.classList.contains('osadaListRow--group')).map(r=>r.textContent);
  return {visible:visible.length, visibleGroups};
});
// "denikin" only appears in the campaign name -> its group header + all 19 of its scenarios match
ok('filter matches scenarios by their campaign name',
   filtered.visibleGroups.length===1 && filtered.visible===20,
   `${filtered.visible} rows, groups=${JSON.stringify(filtered.visibleGroups)}`);

const cleared = await page.evaluate(()=>{
  const inp=document.querySelector('#smScenRegister .osadaListFilter');
  inp.value=''; inp.oninput();
  return [...document.getElementById('osadaScenList').children].filter(r=>r.style.display!=='none').length;
});
ok('clearing the filter restores every row', cleared===scen.rowCount, `${cleared}/${scen.rowCount}`);

// ---- A-Z sort ------------------------------------------------------------------
const sorted = await page.evaluate(()=>{
  const segs=[...document.querySelectorAll('#smScenRegister .osadaListSorts .osada-seg')];
  segs.find(s=>s.textContent==='A–Z').click();
  const rows=[...document.getElementById('osadaScenList').children];
  const items=rows.filter(r=>!r.classList.contains('osadaListRow--group'));
  const groupsHidden=rows.filter(r=>r.classList.contains('osadaListRow--group')).every(r=>r.style.display==='none');
  const names=items.map(r=>r.querySelector('.osadaListRowName').textContent.toLowerCase());
  const isSorted=names.every((n,i)=>i===0||names[i-1]<=n);
  return {groupsHidden,isSorted,first:items[0].querySelector('.osadaListRowName').textContent,count:items.length};
});
ok('A–Z sorts scenarios alphabetically', sorted.isSorted, 'first: '+sorted.first);
ok('A–Z hides the campaign group headers', sorted.groupsHidden);

// selection must still work after sorting (rows keep their optionIndex; select is untouched)
const afterSort = await page.evaluate(()=>{
  const rows=[...document.getElementById('osadaScenList').children].filter(r=>!r.classList.contains('osadaListRow--group'));
  rows[3].click();
  // #smScenSel is a CONTAINER; StartMenuBuilder appends the real <select> inside it.
  const sel=document.querySelector('#smScenSel select');
  return {
    selectedIdx: sel.selectedIndex,
    selectedText: sel.options[sel.selectedIndex].text.trim(),
    clickedText: rows[3].querySelector('.osadaListRowName').textContent,
    highlighted: rows[3].classList.contains('osadaListRow--selected'),
    descFilled: (document.getElementById('smScenDesc').innerHTML||'').length>0,
  };
});
ok('clicking a row after sorting selects the RIGHT scenario',
   afterSort.selectedText===afterSort.clickedText && afterSort.highlighted && afterSort.descFilled,
   `${afterSort.clickedText} -> select[${afterSort.selectedIdx}]=${afterSort.selectedText}`);

// ---- campaign screen -----------------------------------------------------------
await page.evaluate(()=>{document.getElementById('smScen').style.display='none';window.game.ui.startMenuButton('newcampaign');});
await sleep(400);
const camp = await page.evaluate(()=>{
  const names=()=>[...document.getElementById('osadaCampList').children].map(r=>r.querySelector('.osadaListRowName').textContent);
  const segs=[...document.querySelectorAll('#smCampRegister .osadaListSorts .osada-seg')];
  const out={buttons:segs.map(s=>s.textContent), default:names()};
  segs.find(s=>s.textContent==='Year').click(); out.byYear=names();
  segs.find(s=>s.textContent==='A–Z').click(); out.byName=names();
  segs.find(s=>s.textContent==='Length').click(); out.bySize=names();
  segs.find(s=>s.textContent==='Default').click(); out.backToDefault=names();
  return out;
});
ok('campaign toolbar has 4 sorts', camp.buttons.length===4, camp.buttons.join('/'));
ok('campaign rename visible in the register', camp.default.some(n=>n.includes('The Defeat of Denikin')));
ok('Year sort puts Spartacus (73-71 BC) first', camp.byYear[0].includes('Spartacus'), camp.byYear.slice(0,3).join(' | '));
ok('A–Z sorts campaigns alphabetically',
   camp.byName.map(n=>n.toLowerCase()).every((n,i)=>i===0||camp.byName[i-1].toLowerCase()<=n), camp.byName[0]);
ok('Length sort puts the shortest campaign first', camp.bySize[0].includes('November Revolution'), camp.bySize.slice(0,3).join(' | '));
ok('Default restores campaignlist order', JSON.stringify(camp.backToDefault)===JSON.stringify(camp.default));

// ---- start the Denikin campaign: briefing must NOT be empty ---------------------
const campIdx = await page.evaluate(()=>{
  const sel=document.querySelector('#smCampSel select');
  return [...sel.options].findIndex(o=>o.text.includes('The Defeat of Denikin'));
});
await page.evaluate((i)=>window.game.newCampaign(i,0), campIdx);
await sleep(5000);
const briefing = await page.evaluate(()=>({
  title: document.getElementById('title').textContent,
  body: (document.getElementById('message').textContent||'').trim(),
  visible: getComputedStyle(document.getElementById('ui-message')).display!=='none',
}));
ok('campaign start shows a NON-EMPTY briefing', briefing.visible && briefing.body.length>20,
   `"${briefing.title}" / ${briefing.body.length} chars: "${briefing.body.slice(0,60)}…"`);

// ---- dossier: no sprite frame, close button still a sprite ----------------------
await page.evaluate(()=>{document.getElementById('uiokbut').click();});
await sleep(300);
// The dossier is only reachable from the Turn Report's "Campaign Dossier" button, so open the
// Turn Report (uiEndTurnInfo -> showStatusExtension -> toggleCombatLog) and click it.
await page.evaluate(()=>window.game.ui.uiEndTurnInfo());
await sleep(500);
const dossier = await page.evaluate(()=>{
  [...document.querySelectorAll('#combatLog .combatLogInfoButton')]
    .find(b=>b.title==='Campaign Dossier').click();
  const d=document.getElementById('dossier');
  const cs=getComputedStyle(d);
  const close=document.getElementById('dossierCloseBut');
  const closeCs=close?getComputedStyle(close):null;
  const medal=document.querySelector('.osada-dsr-medal');
  return {
    visible: cs.display!=='none',
    borderImage: cs.borderImageSource,
    borderWidth: cs.borderTopWidth,
    borderColor: cs.borderTopColor,
    closeBg: closeCs?.backgroundImage,
    closeW: closeCs?.width,
    medalBg: medal?getComputedStyle(medal,'::before').backgroundImage:'(no medals yet)',
    title: document.querySelector('.osada-dsr-title')?.textContent,
  };
});
ok('dossier no longer uses the frame_turn_report sprite frame',
   dossier.borderImage==='none' && dossier.borderWidth==='1px', `${dossier.borderWidth} / ${dossier.borderImage}`);
ok('dossier close button still draws the sprite cross',
   (dossier.closeBg||'').includes('hud_icons_grid') && dossier.closeW==='26px', dossier.closeW);
ok('dossier title shows the renamed campaign', (dossier.title||'').includes('The Defeat of Denikin'), dossier.title);
// Regression guard: the volarm/simpob side-flip was lost by a deploy_campaigns.py re-run, leaving
// the human playing Denikin's WHITES in the campaign about beating them. Re-applied 2026-07-13.
ok('human plays the RED side in the Denikin campaign',
   (dossier.title||'').includes('Red Russia'), dossier.title);

await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';
  document.getElementById('smCamp').style.display='none';});
await page.screenshot({path:path.join(__dirname,'menu-lists-dossier.png')});

console.log('\n=== RESULTS ===');
for(const [s,n,e] of results) console.log(`${s}  ${n}${e?`   [${e}]`:''}`);
console.log(`\npage errors: ${errs.length}`); errs.slice(0,5).forEach(e=>console.log('  '+e));
const failed=results.filter(r=>r[0]==='FAIL').length;
console.log(`\n${results.length-failed}/${results.length} passed`);
await browser.close(); server.close();
process.exit(failed?1:0);
