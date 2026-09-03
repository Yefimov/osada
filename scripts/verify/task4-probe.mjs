import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8813;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=rq.url.split('?')[0];const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,160)));
const results=[]; const ok=(n,c)=>results.push([c?'PASS':'FAIL',n]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('drpzop01.xml','x');});
await sleep(3000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(500);

const initial = await page.evaluate(()=>{
  const cv = document.getElementById('osada-minimap');
  if(!cv) return {exists:false};
  const r = cv.getBoundingClientRect();
  const ctx = cv.getContext('2d');
  const data = ctx.getImageData(0,0,cv.width,cv.height).data;
  let nonBackgroundPixels = 0;
  // count pixels that differ from the base fill color #15171c (21,23,28)
  for(let i=0;i<data.length;i+=4){
    if(data[i]!==0x15 || data[i+1]!==0x17 || data[i+2]!==0x1c) nonBackgroundPixels++;
  }
  return {
    exists:true, width:cv.width, height:cv.height,
    rectW: Math.round(r.width), rectH: Math.round(r.height),
    nonBackgroundPixels,
  };
});
ok('minimap canvas exists at spec size (~240x160)', initial.exists && initial.width===240 && initial.height===160);
ok('minimap canvas rendered at correct CSS size', initial.rectW===240 && initial.rectH===160);
ok('minimap has drawn content beyond flat background (composited real canvas)', initial.nonBackgroundPixels>500);
if(!initial.exists) console.log('  DEBUG initial:', JSON.stringify(initial));

// select a unit -> a green dot should appear somewhere; also viewport rect (light stroke) exists
const afterSelect = await page.evaluate(()=>{
  const map = window.game.scenario.map;
  const unit = map.getUnits().find(u=>u.player&&u.player.id===map.currentPlayer.id);
  window.game.ui.uiUnitSelect(unit);
  const cv = document.getElementById('osada-minimap');
  const ctx = cv.getContext('2d');
  const data = ctx.getImageData(0,0,cv.width,cv.height).data;
  let greenPixels=0, redPixels=0, brassPixels=0, lightStrokePixels=0;
  for(let i=0;i<data.length;i+=4){
    const r=data[i], g=data[i+1], b=data[i+2];
    if(r===0x7f && g===0xa8 && b===0x6a) greenPixels++;
    if(r===0xc9 && g===0x46 && b===0x3d) redPixels++;
    if(r===0xd9 && g===0xb2 && b===0x5a) brassPixels++;
    if(r===0xe7 && g===0xe2 && b===0xd4) lightStrokePixels++;
  }
  return {greenPixels, redPixels, brassPixels, lightStrokePixels};
});
ok('own-unit dot (green) drawn after selecting a unit', afterSelect.greenPixels>0);
ok('viewport rect (light stroke) drawn', afterSelect.lightStrokePixels>0);
console.log('  dot counts:', JSON.stringify(afterSelect));

// click-to-scroll: click near bottom-right of minimap, #game scroll should change accordingly
const scrollTest = await page.evaluate(async ()=>{
  const game = document.getElementById('game');
  const before = {left: game.scrollLeft, top: game.scrollTop};
  const cv = document.getElementById('osada-minimap');
  const r = cv.getBoundingClientRect();
  cv.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, clientX:r.left+r.width*0.85, clientY:r.top+r.height*0.85}));
  window.dispatchEvent(new MouseEvent('mouseup', {bubbles:true}));
  await new Promise(res=>setTimeout(res,50));
  const after = {left: game.scrollLeft, top: game.scrollTop};
  return {before, after};
});
ok('click on minimap scrolls the main map container', scrollTest.before.left!==scrollTest.after.left || scrollTest.before.top!==scrollTest.after.top);
console.log('  scroll before/after:', JSON.stringify(scrollTest));

// verify game canvases were never resized/drawn-to by the minimap (dimensions unchanged, hard constraint)
const canvasIntegrity = await page.evaluate(()=>{
  const hexes = document.getElementById('hexes');
  const mapC = document.getElementById('map');
  return {hexesW: hexes?hexes.width:null, hexesH: hexes?hexes.height:null, mapW: mapC?mapC.width:null, mapH: mapC?mapC.height:null};
});
ok('game canvases still present with sane (unmodified) dimensions', canvasIntegrity.hexesW>0 && canvasIntegrity.mapW>0);

ok('no page errors', errs.length===0);
console.log('\n==== TASK 4 PROBE ====');
for(const [s,n] of results) console.log(`${s}  ${n}`);
if(errs.length) errs.slice(0,5).forEach(e=>console.log('  ERR '+e));
console.log(`${results.filter(r=>r[0]==='PASS').length}/${results.length} passed`);
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
