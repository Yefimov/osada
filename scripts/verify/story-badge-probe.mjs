/**
 * Probe for the 2026-08-16 "Story only" report: "Forward, Comrade!" and "Greece: Resistance and
 * Civil War" carried the story badge despite having no story at all.
 *
 * The importer (`tools/og-import/deploy_campaigns.py`) emits one dialogue line per OG branch node so
 * the player can choose a path, tagged `speaker="General Staff"` / `role="Path selection"` because
 * no character is speaking. `StoryCampaignDetector` counted any dialogue at all, so every imported
 * BRANCHING campaign looked like a story campaign. It now ignores exactly those generated prompts.
 *
 * Checked against the real shipped campaign files through the real register UI, not fixtures.
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8828;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2500);

await page.evaluate(()=>window.game.ui.startMenuButton('newcampaign'));
await sleep(2500);   // story detection fetches every campaign file asynchronously

const badged = await page.evaluate(()=>{
  const list=document.getElementById('osadaCampList');
  return [...list.children]
    .filter(r=>!r.classList.contains('osadaListRow--group'))
    .filter(r=>r.querySelector('.osadaStoryBadge'))
    .map(r=>r.querySelector('.osadaListRowName')?.textContent?.trim());
});
ok('campaign register rendered with some story badges', badged.length>0, `${badged.length} badged`);

// These three are branch-only: their sole "dialogue" is the generated path-selection prompt.
for (const name of ['Forward, Comrade!','Greece: Resistance and Civil War','Sim Pobedishi!']) {
  ok(`"${name}" no longer carries the story badge`,
     !badged.some(b=>b && b.includes(name)), badged.join(' | ') || '(none)');
}
// These have real authored dialogue (hundreds of lines) and must keep it.
for (const name of ['November','Hungarian']) {
  ok(`a genuinely authored campaign matching "${name}" still carries the badge`,
     badged.some(b=>b && b.includes(name)), badged.join(' | ') || '(none)');
}

ok('no runtime JS errors', errs.length===0, errs.join(' | '));
console.log('\n=== Story badge probe ===');
console.log('badged campaigns: ' + (badged.join(' | ') || '(none)') + '\n');
for (const [s,n,e] of results) console.log(`${s}  ${n}${e?'\n        '+e:''}`);
const failed = results.filter(r=>r[0]==='FAIL').length;
console.log(`\n${results.length-failed}/${results.length} passed`);
await browser.close(); server.close();
process.exit(failed?1:0);
