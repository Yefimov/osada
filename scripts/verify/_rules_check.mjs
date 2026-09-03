import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8906;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1400,height:900});
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2000);
await page.evaluate(()=>window.game.ui.startMenuButton('newcampaign'));
await sleep(500);
const info = await page.evaluate(()=>{
  const rules = document.getElementById('osadaRulesButton');
  const cs = getComputedStyle(rules);
  const r = rules.getBoundingClientRect();
  const buttons = document.getElementById('smCampButtons');
  const buttonsHtml = buttons ? buttons.outerHTML.slice(0, 2000) : null;
  return {
    rect: [r.left, r.top, r.width, r.height],
    display: cs.display, visibility: cs.visibility, color: cs.color, background: cs.backgroundImage.slice(0,60),
    fontSize: cs.fontSize, opacity: cs.opacity, zIndex: cs.zIndex,
    text: rules.textContent,
    className: rules.className,
    buttonsHtml,
  };
});
console.log(JSON.stringify(info, null, 2));
await browser.close(); server.close();
