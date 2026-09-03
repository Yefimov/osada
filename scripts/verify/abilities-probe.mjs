import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8832;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf','.wav':'audio/wav','.mp3':'audio/mpeg','.ogg':'audio/ogg','.gif':'image/gif','.svg':'image/svg+xml','.ico':'image/x-icon'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1800);
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('bn4s19.xml','x');});
await sleep(3500);
await page.evaluate(()=>{const sm=document.getElementById('startmenu'); if(sm) sm.style.display='none'; const ok=document.getElementById('uiokbut'); if(ok) ok.click();});
await sleep(600);
await page.evaluate(()=>{
  const eq=document.getElementById('equipment'); if(eq) eq.style.display='block';
  const ui=window.game.ui;
  for(const k of Object.getOwnPropertyNames(Object.getPrototypeOf(ui))) if(/updateEquipmentWindow/.test(k)) { try{ui[k](1);}catch(e){} }
  if(ui.updateEquipmentWindow) try{ui.updateEquipmentWindow(1);}catch(e){}
});
await sleep(1200);

// Find an entry whose name matches, click it, read the detail panel.
const WANT = (process.argv[2]||'Headquarters');
const out = await page.evaluate((want)=>{
  const boxes=[...document.querySelectorAll('.eqUnitBox')];
  const hit=boxes.find(b=>(b.textContent||'').includes(want)) || boxes[0];
  if(hit) hit.click();
  const det=document.getElementById('eqDetailBody');
  const marks=[...document.querySelectorAll('#eqDetailBody .osada-capability-mark')].map(m=>({t:m.textContent,cls:m.className.replace('osada-capability-mark ',''),tip:(m.title||'').slice(0,70)}));
  const mech=det&&det.querySelector('.osada-eqd-mechanics');
  return {name:det&&det.querySelector('.osada-eqd-name')?det.querySelector('.osada-eqd-name').textContent:null,
          marks, mechanics: mech?mech.textContent:null, boxCount:boxes.length};
}, WANT);
console.log(JSON.stringify(out,null,1));
const el = await page.$('#eqDetailBody'); if(el) await el.screenshot({path:path.join(__dirname,'abilities-detail.png')});
if(errs.length) console.log('PAGE ERRORS', errs.slice(0,5));
await browser.close(); server.close();
