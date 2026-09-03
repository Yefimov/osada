import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8831;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf','.wav':'audio/wav','.mp3':'audio/mpeg','.ogg':'audio/ogg','.gif':'image/gif','.svg':'image/svg+xml','.ico':'image/x-icon'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const SCEN = process.argv[2] || 'bn4s19.xml';
const WANT = (process.argv[3] || '').toLowerCase();
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1800);
await page.evaluate(s=>{window.game.campaign=null;window.game.newScenario(s,'x');}, SCEN);
await sleep(3500);
await page.evaluate(()=>{const sm=document.getElementById('startmenu'); if(sm) sm.style.display='none'; const ok=document.getElementById('uiokbut'); if(ok) ok.click();});
await sleep(700);

await page.evaluate(()=>{
  window.__units = () => {
    const map=window.game.scenario.map;
    const l = map.units_1;
    if (Array.isArray(l)) return l;
    if (typeof l.toArray === 'function') return l.toArray();
    for (const k of Object.keys(l)) if (Array.isArray(l[k])) return l[k];
    return [];
  };
});
const list = await page.evaluate(()=>window.__units().filter(u=>!u.destroyed).map((u,i)=>({i, name:(u.unitData(true)||{}).name, player:u.player&&u.player.id})));
console.log('units:', list.length);
if(!WANT){ console.log(JSON.stringify(list.slice(0,50))); }

const targets = WANT ? list.filter(u=>(u.name||'').toLowerCase().includes(WANT)) : list.filter(u=>u.player===0).slice(0,2);
for (const t of targets.slice(0,3)) {
  const geo = await page.evaluate((idx)=>{
    const u=window.__units().filter(x=>!x.destroyed)[idx];
    const ui=window.game.ui; window.game.scenario.map.currentUnit=u; ui.uiUnitSelect(u);
    const p=document.getElementById('unit-info'); if(p) p.style.display='block';
    if(ui.unitInfoPanel_1&&ui.unitInfoPanel_1.showUnitInfo) try{ui.unitInfoPanel_1.showUnitInfo(u);}catch(e){}
    const r=id=>{const e=document.getElementById(id); if(!e) return null; const b=e.getBoundingClientRect();
      return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height), txt:(e.textContent||'').slice(0,60)};};
    const out={name:(u.unitData(true)||{}).name};
    for (const id of ['unit-info','uc-inner','uc-main','uc-nameline','uName','uc-commandline','uc-statusline','uc-bars','uc-fuel','osadaUcMarkings','osada-bottomzone'])
      out[id]=r(id);
    const bars=document.getElementById('uc-bars');
    out.barIds = bars ? [...bars.children].map(c=>({id:c.id, cls:c.className, y:Math.round(c.getBoundingClientRect().y), h:Math.round(c.getBoundingClientRect().height), txt:(c.textContent||'').replace(/\s+/g,' ').slice(0,40)})) : null;
    out.marks = [...document.querySelectorAll('#osadaUcMarkings .osada-capability-mark')].map(m=>m.textContent);
    const cs=(id,props)=>{const e=document.getElementById(id); if(!e) return null; const s=getComputedStyle(e); const o={}; props.forEach(k=>o[k]=s[k]); return o;};
    out.css={
      'uc-inner':cs('uc-inner',['display','flexDirection','gap','width']),
      'uc-main':cs('uc-main',['display','flexDirection','flexGrow','flexShrink','flexBasis','width','minWidth','maxWidth']),
      'uc-actions':cs('uc-actions',['display','flexGrow','width','marginLeft']),
    };
    out.zoneKids=[...document.getElementById('osada-bottomzone').children].map(c=>({id:c.id,cls:c.className,x:Math.round(c.getBoundingClientRect().x),w:Math.round(c.getBoundingClientRect().width),vis:getComputedStyle(c).display}));
    out.innerKids=[...document.getElementById('uc-inner').children].map(c=>({id:c.id,cls:c.className,x:Math.round(c.getBoundingClientRect().x),w:Math.round(c.getBoundingClientRect().width)}));
    return out;
  }, t.i);
  console.log(JSON.stringify(geo,null,1));
  await sleep(300);
  const el = await page.$('#osada-bottomzone');
  if (el) await el.screenshot({path: path.join(__dirname, `unitinfo-${(geo.name||'x').replace(/[^\w]+/g,'_')}.png`)});
}
if(errs.length) console.log('PAGE ERRORS', errs.slice(0,5));
await browser.close(); server.close();
