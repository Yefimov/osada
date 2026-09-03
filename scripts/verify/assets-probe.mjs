/** Visual check for the 2026-07-13 asset drop: STR bar icon, frozen-ground icon vs the snow
 *  weather icon in the top bar, and the replacement logo. Loads a winter scenario so the
 *  status bar actually shows Snow / Frozen. */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(8821,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[],failed=[];
page.on('pageerror',e=>errs.push(e.message.slice(0,160)));
page.on('requestfailed',r=>failed.push(r.url()));
page.on('response',r=>{ if(r.status()>=400 && /\.png$/.test(r.url())) failed.push(r.status()+' '+r.url()); });

await page.goto('http://localhost:8821/',{waitUntil:'networkidle2'}); await sleep(1800);
// volarm_00 "The Ice March": snowing + frozen ground, so both icons are on screen at once.
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('volarm_00.xml','x');});
await sleep(4000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(600);
// select a unit so the unit card (and its STR/AMMO/FUEL bars) is populated
await page.evaluate(()=>{
  const map=window.game.scenario.map, cur=map.currentPlayer;
  const u=map.getUnits().find(u=>u.player&&u.player.id===cur.id);
  window.game.ui.uiUnitSelect(u);
});
await sleep(700);

const info = await page.evaluate(()=>{
  const g=(sel)=>document.querySelector(sel);
  const strIco=g('#uStrBarFillRow .osada-bar__ico');
  const imgs=[...document.querySelectorAll('img')].filter(i=>i.naturalWidth===0 && i.getAttribute('src'));
  return {
    strIcoSrc: strIco?.getAttribute('src') || null,
    strIcoLoaded: strIco ? strIco.naturalWidth>0 : false,
    barIcons: [...document.querySelectorAll('#uc-bars .osada-bar__ico')].map(i=>i.getAttribute('src')),
    weatherHtml: g('#weathermsg')?.innerHTML.replace(/\s+/g,' ').trim().slice(0,220),
    logoLoaded: (()=>{const l=g('.osada-tb-brand-logo'); return l? l.naturalWidth+'x'+l.naturalHeight : 'none';})(),
    brokenImgs: imgs.map(i=>i.getAttribute('src')),
  };
});
console.log(info);
await page.screenshot({path:path.join(__dirname,'assets-topbar.png'), clip:{x:0,y:0,width:1100,height:40}});
await page.screenshot({path:path.join(__dirname,'assets-unitcard.png'), clip:{x:0,y:975,width:560,height:105}});
console.log('\npage errors:', errs.length, errs);
console.log('failed/404 requests:', failed.length, failed.slice(0,5));
await browser.close(); server.close();
