import http from 'http'; import fs from 'fs'; import path from 'path';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const DIST = 'C:/dev/osada/build/dist/js/developmentExecutable';
const PORT = 8853;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1440,height:900});
const errors=[]; page.on('pageerror',e=>errors.push(e.message.slice(0,160)));
await page.evaluateOnNewDocument(()=>{ try { localStorage.setItem('osada-language','en'); } catch (e) {} });
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2500);

await page.evaluate(()=>{
  window.game.ui.startMenuButton('campaign');
  const sel = document.querySelector('#smCampSel select') || document.getElementById('smCampSel');
  const t = [...sel.options].find(o=>o.text.includes('Forward, Comrade'));
  sel.selectedIndex = t.index; if (sel.onchange) sel.onchange();
});
await sleep(500);
await page.evaluate(()=>document.getElementById('osadaRulesButton').click());
await sleep(700);
console.log(await page.evaluate(()=>{
  const box = document.getElementById('osadaRulesWindow') || document.querySelector('.osadaRulesWindow');
  if (!box) return { open:false, ids:[...document.querySelectorAll('[id]')].map(e=>e.id).filter(i=>/rule/i.test(i)) };
  const rows = [...box.querySelectorAll('*')].filter(e=>/replacement|experience/i.test(e.textContent||'') && e.children.length===0);
  return {
    open: true,
    matches: rows.map(r=>r.textContent.trim()).slice(0,6),
    fullText: (box.textContent||'').replace(/\s+/g,' ').slice(0,900),
  };
}));
console.log('errors:', errors.join(' | ') || '(none)');
await browser.close(); server.close();
