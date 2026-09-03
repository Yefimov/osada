/**
 * Probe for the scenario wallpapers round: per-operation chapter art on the Scenario
 * Selection dossier banner and on the campaign briefing backdrop.
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
const missing=[]; page.on('response',r=>{ if(r.status()>=400 && /wallpapers/.test(r.url())) missing.push(r.url()); });
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2000);

// ---- the generated table is loaded -------------------------------------------
const table = await page.evaluate(()=>({
  present: typeof scenarioWallpapers !== 'undefined',
  count: typeof scenarioWallpapers === 'undefined' ? 0 : Object.keys(scenarioWallpapers).length,
  sample: typeof scenarioWallpapers === 'undefined' ? null : scenarioWallpapers['bn8s00.xml'],
}));
ok('scenarioWallpapers global loaded', table.present && table.count > 250, table.count+' entries');
ok('YUG chapter 1 mapped', table.sample === 'resources/ui/wallpapers/yug-1.jpg', String(table.sample));

// ---- every referenced image actually exists ----------------------------------
const fetched = await page.evaluate(async ()=>{
  const urls=[...new Set(Object.values(scenarioWallpapers))];
  const bad=[];
  for (const u of urls) { const r = await fetch(u, {method:'HEAD'}); if(!r.ok) bad.push(u); }
  return {total:urls.length, bad};
});
ok('every mapped wallpaper resolves', fetched.bad.length===0, fetched.total+' distinct, bad: '+JSON.stringify(fetched.bad));

// ---- Scenario Selection dossier banner ---------------------------------------
await page.evaluate(()=>window.game.ui.startMenuButton('newscenario'));
await sleep(400);
const banner = await page.evaluate(async ()=>{
  const sel=document.querySelector('#smScenSel select');
  const list=window.scenariolist;
  const pick=(file)=>{ const i=list.findIndex(r=>r.length>1 && r[0]===file); sel.selectedIndex=Array.prototype.findIndex.call(sel.options,o=>o.value===String(i)); sel.onchange(); };
  const read=()=>document.querySelector('#smScenDossierHead .osadaTheater').style.background;
  pick('bn8s00.xml'); const yug=read();
  pick('ruscam19.xml'); const gpw=read();
  pick('acampzbo.xml'); const none=read();     // Czech Legion: no art yet
  pick('tutorial.xml'); const standalone=read(); // standalone: never had art
  return {yug, gpw, none, standalone};
});
await page.evaluate(()=>{
  const sel=document.querySelector('#smScenSel select');
  const i=window.scenariolist.findIndex(r=>r.length>1 && r[0]==='bn8s07.xml');
  sel.selectedIndex=Array.prototype.findIndex.call(sel.options,o=>o.value===String(i)); sel.onchange();
});
await sleep(500);
await page.screenshot({path:path.join(__dirname,'wallpapers-scenario-dossier.png')});
ok('Yugoslav opening shows yug-1', banner.yug.includes('wallpapers/yug-1.jpg'));
ok('Berlin 1945 shows gpw-4', banner.gpw.includes('wallpapers/gpw-4.jpg'));
ok('art-less campaign falls back to placeholder',
   !banner.none.includes('wallpapers/') && banner.none.includes('dossier_map_placeholder'));
ok('standalone scenario falls back to placeholder',
   !banner.standalone.includes('wallpapers/') && banner.standalone.includes('dossier_map_placeholder'));

// ---- campaign briefing backdrop ----------------------------------------------
// Yugoslav Front, first operation: the briefing should open on the same yug-1 art.
await page.evaluate(()=>{
  const i=campaignlist.findIndex(c=>c.file==='camp6bn8.json');
  const sel=document.querySelector('#smCampSel select');
  window.game.ui.startMenuButton('newcampaign');
  sel.value=String(i); sel.onchange();
});
await sleep(600);
await page.evaluate(()=>document.getElementById('smCPlayBut').onclick(new MouseEvent('click')));
await sleep(9000);
const brief = await page.evaluate(()=>{
  const b=document.querySelector('.osada-briefing__backdrop');
  return {shown: !!b, bg: b ? b.style.backgroundImage : null};
});
await page.screenshot({path:path.join(__dirname,'wallpapers-briefing.png')});
ok('campaign briefing opened', brief.shown);
ok('briefing backdrop uses yug-1', !!brief.bg && brief.bg.includes('wallpapers/yug-1.jpg'), String(brief.bg));

ok('no page errors', errs.length===0, errs.join(' | '));
ok('no 404s on wallpaper assets', missing.length===0, missing.join(' | '));

console.log(results.map(([s,n,e])=>`${s}  ${n}${e?'  ['+e+']':''}`).join('\n'));
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
