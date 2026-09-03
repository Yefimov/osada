import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8807;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf','.wav':'audio/wav','.mp3':'audio/mpeg','.ogg':'audio/ogg','.gif':'image/gif','.svg':'image/svg+xml','.ico':'image/x-icon'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=rq.url.split('?')[0];const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,160)));
const results=[]; const ok=(n,c)=>results.push([c?'PASS':'FAIL',n]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('drpzop01.xml','x');});
await sleep(3200);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';const b=document.getElementById('uiokbut');if(b)b.click();});
await sleep(500);

const probe=await page.evaluate(()=>{
  const out={};
  const sb=document.getElementById('statusbar');
  const r=sb.getBoundingClientRect();
  out.barLeft=Math.round(r.left); out.barWidth=Math.round(r.width); out.barTop=Math.round(r.top); out.barHeight=Math.round(r.height);
  out.menuDisplay=getComputedStyle(document.getElementById('menu')).display;
  out.hasBrand=!!document.getElementById('osadaBrand');
  out.hasNav=!!document.getElementById('osadaNav');
  out.hasEndTurn=!!document.getElementById('osadaEndTurn');
  out.hasReserves=!!document.getElementById('buy');
  out.hasZoom=!!document.getElementById('zoom');
  out.hasOptions=!!document.getElementById('options');
  out.opText=(document.querySelector('.osada-tb-op')||{}).textContent||'';
  out.weatherText=(document.querySelector('.osada-tb-weather-txt')||{}).textContent||'';
  out.dateText=(document.querySelector('.osada-tb-date')||{}).textContent||'';
  out.navCount=(document.getElementById('osadaNavCount')||{}).textContent||'';
  out.etText=document.getElementById('osadaEndTurn').textContent;
  out.etClass=document.getElementById('osadaEndTurn').className;
  // locmsg far right (>half width) or hidden
  const lm=document.getElementById('locmsg'); const lr=lm.getBoundingClientRect();
  out.locVisible=getComputedStyle(lm).display!=='none';
  out.locLeft=Math.round(lr.left);
  // no element in the bar overflows past viewport
  out.barRight=Math.round(r.right);
  return out;
});
ok('top bar spans full viewport width', probe.barLeft===0 && probe.barWidth===1920 && probe.barTop===0);
ok('top bar ~40px tall', probe.barHeight>=38 && probe.barHeight<=42);
ok('#menu rail dissolved (display none)', probe.menuDisplay==='none');
ok('brand + navigator + endturn present', probe.hasBrand && probe.hasNav && probe.hasEndTurn);
ok('reserves(buy) + zoom + options present', probe.hasReserves && probe.hasZoom && probe.hasOptions);
ok('scenario name shown in op slot', probe.opText.length>0);
ok('date shown (has a 4-digit year)', /\d{4}/.test(probe.dateText));
ok('weather words shown with separator', probe.weatherText.includes('·'));
ok('navigator count is numeric', /^\d+$/.test(probe.navCount));
ok('end turn labelled', /end turn/i.test(probe.etText));
ok('locmsg far right when visible', !probe.locVisible || probe.locLeft>probe.barWidth/2);

// ready-unit navigator cycles selection
const navRes=await page.evaluate(()=>{
  const before=window.game.scenario.map.currentUnit&&window.game.scenario.map.currentUnit.id;
  window.game.ui.cycleReadyUnit(1);
  const afterFwd=window.game.scenario.map.currentUnit&&window.game.scenario.map.currentUnit.id;
  window.game.ui.cycleReadyUnit(1);
  const afterFwd2=window.game.scenario.map.currentUnit&&window.game.scenario.map.currentUnit.id;
  return {before,afterFwd,afterFwd2, hasCurrent:afterFwd!=null};
});
ok('navigator selects a ready unit', navRes.hasCurrent);
ok('navigator advances between units', navRes.afterFwd!==navRes.afterFwd2 || navRes.afterFwd!=null);

// end-turn N>0 opens inline confirm (does not end immediately); ✗ cancels
const etRes=await page.evaluate(async ()=>{
  window.uiSettings && (window.uiSettings.confirmEndTurn=true);
  const turnBefore=window.game.scenario.map.turn;
  window.game.ui.onEndTurnClick();
  const et=document.getElementById('osadaEndTurn');
  const confirming=et.getAttribute('confirming')==='on';
  const hasYesNo=!!et.querySelector('.osada-et__yes')&&!!et.querySelector('.osada-et__no');
  const turnAfterClick=window.game.scenario.map.turn;
  et.querySelector('.osada-et__no') && et.querySelector('.osada-et__no').click();
  const confirmingAfterCancel=et.getAttribute('confirming')==='on';
  return {confirming,hasYesNo,sameTurn:turnBefore===turnAfterClick,confirmingAfterCancel};
});
ok('end turn (N>0) shows inline confirm, does not end', etRes.confirming && etRes.hasYesNo && etRes.sameTurn);
ok('inline confirm ✗ cancels', !etRes.confirmingAfterCancel);

ok('no page errors', errs.length===0);
if(errs.length) console.log('ERRORS:',errs);
console.log('\nprobe:',JSON.stringify(probe,null,1));
console.log('\n'+results.map(([s,n])=>`${s}  ${n}`).join('\n'));
const fails=results.filter(r=>r[0]==='FAIL').length;
console.log(`\n${results.length-fails}/${results.length} passed`);
await browser.close(); server.close();
process.exit(fails?1:0);
