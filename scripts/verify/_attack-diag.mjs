import http from 'http'; import fs from 'fs'; import path from 'path';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const DIST = 'C:/Users/Илья/IdeaProjects/openGeneral/build/dist/js/developmentExecutable';
const PORT = 8837;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('drpzop01.xml','x');});
await sleep(3000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(400);

async function findScreenPosForCell(targetRow, targetCol) {
  return await page.evaluate(async (targetRow, targetCol) => {
    const canvases=[...document.querySelectorAll('#game canvas')];
    const rect=canvases[0].getBoundingClientRect();
    for(let fy=0.03; fy<=0.97; fy+=0.004){
      for(let fx=0.03; fx<=0.97; fx+=0.004){
        const cx=rect.left+rect.width*fx, cy=rect.top+rect.height*fy;
        for(const cv of canvases) cv.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:cx,clientY:cy}));
        const m=(document.getElementById('locmsg').textContent.match(/\((\d+),(\d+)\)\s*$/)||[]);
        if(+m[1]===targetCol && +m[2]===targetRow) return {found:true, cx, cy};
      }
    }
    return {found:false};
  }, targetRow, targetCol);
}
async function clickAt(cx, cy) {
  await page.evaluate(async ({cx, cy}) => {
    const canvases=[...document.querySelectorAll('#game canvas')];
    for(const cv of canvases) cv.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,clientX:cx,clientY:cy,button:0,which:1}));
  }, {cx, cy});
}

// Find MY unit that has a valid MOVE destination cell adjacent to an enemy (so after moving there,
// the enemy should become attackable) — i.e. simulate "drive up then click enemy."
const setup = await page.evaluate(() => {
  const map = window.game.scenario.map;
  const cur = map.currentPlayer;
  const candidates = map.getUnits().filter(u => u.player && u.player.id === cur.id && !u.hasMoved && !u.hasFired);
  const dirs = [[-1,0],[1,0],[0,-1],[0,1],[-1,1],[1,-1]];
  let fallback = null;
  for (const cand of candidates) {
    window.game.ui.uiUnitSelect(cand);
    const startPos = cand.getPos();
    let best = null;
    for (let r=0;r<map.rows;r++) for (let c=0;c<map.cols;c++) {
      if (!map.map[r][c].isMoveSel) continue;
      const dist = Math.abs(r-startPos.row) + Math.abs(c-startPos.col);
      for (const [dr,dc] of dirs) {
        const nr=r+dr, nc=c+dc;
        if (nr<0||nc<0||nr>=map.rows||nc>=map.cols) continue;
        const nhex = map.map[nr][nc];
        const nu = nhex.getUnit(false) || nhex.getUnit(true);
        if (nu && nu.player && nu.player.side !== cur.side) {
          const candidate = { unitId: cand.id, moveRow: r, moveCol: c, enemyRow: nr, enemyCol: nc, enemyName: nu.unitData(true).name, dist, enemyIsMobile: nu.unitData(true).movpoints > 0 };
          if (!fallback) fallback = candidate;
          if (candidate.enemyIsMobile && (!best || dist > best.dist)) best = candidate;
        }
      }
    }
    if (best && best.dist >= 2) return best;
  }
  return fallback;
});
console.log('SETUP:', JSON.stringify(setup));
if (!setup) { console.log('No move-adjacent-to-enemy setup found in this scenario.'); await browser.close(); server.close(); process.exit(1); }

// Re-select (the search above already selected/deselected many units)
await page.evaluate((id) => { window.game.ui.uiUnitSelect(window.game.scenario.map.getUnitById(id)); }, setup.unitId);
await sleep(50);

const movePos = await findScreenPosForCell(setup.moveRow, setup.moveCol);
console.log('movePos found:', movePos.found);
await clickAt(movePos.cx, movePos.cy);
console.log('move click dispatched, waiting for animation...');
await sleep(2000); // let move animation + finishMoveAnimation fully complete

const stateAfterMove = await page.evaluate((er, ec) => {
  const map = window.game.scenario.map;
  return {
    currentUnitId: map.currentUnit ? map.currentUnit.id : null,
    waitUIAnimation: window.game.waitUIAnimation,
    enemyHexIsAttackSel: map.map[er][ec].isAttackSel,
    hasFired: map.currentUnit ? map.currentUnit.hasFired : null,
  };
}, setup.enemyRow, setup.enemyCol);
console.log('STATE AFTER MOVE:', JSON.stringify(stateAfterMove));

const enemyPos = await findScreenPosForCell(setup.enemyRow, setup.enemyCol);
console.log('enemyPos found:', enemyPos.found);
if (enemyPos.found) {
  const beforeStrength = await page.evaluate((er, ec) => {
    const map = window.game.scenario.map;
    const u = map.map[er][ec].getUnit(false) || map.map[er][ec].getUnit(true);
    return u ? u.strength : null;
  }, setup.enemyRow, setup.enemyCol);
  await clickAt(enemyPos.cx, enemyPos.cy);
  await sleep(500);
  const result = await page.evaluate((er, ec) => {
    const map = window.game.scenario.map;
    const u = map.map[er][ec].getUnit(false) || map.map[er][ec].getUnit(true);
    return {
      afterStrength: u ? u.strength : null,
      bzClass: document.getElementById('osada-bottomzone').className,
      ecName: document.getElementById('ecName')?.textContent,
    };
  }, setup.enemyRow, setup.enemyCol);
  console.log('CLICK ENEMY RESULT: beforeStrength=', beforeStrength, JSON.stringify(result));
  console.log(beforeStrength !== result.afterStrength ? 'ATTACK REGISTERED (strength changed)' : 'NO ATTACK (strength unchanged) -- BUG REPRODUCED IF enemy card shown');
}

await browser.close(); server.close(); process.exit(0);
