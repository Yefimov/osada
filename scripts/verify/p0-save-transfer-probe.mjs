/**
 * End-to-end probe for the two P0 save items the roadmap still had open, both driven through the
 * real UI rather than fixtures:
 *
 * - **pre-deployment refresh round trip.** A classic deploy-phase campaign (`forward.json`, one of
 *   the only two imported campaigns with `deployphase: true`) must survive a page refresh without
 *   starting the mission early and without losing its undeployed reserve/core roster. Phase
 *   validation for this was unit-tested; the refresh itself had never been exercised.
 * - **per-campaign export/import.** `Export campaign` writes one run; `Import campaign` identifies
 *   its campaign, previews it, applies only on confirmation, and replaces nothing else. Cancelling
 *   the confirmation must leave the local run exactly as it was.
 *
 * See docs/design/save-recovery.md sections 2, 7 and 8.
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8843;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1440,height:900});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);
const report=()=>{ console.log('\n=== P0 save transfer probe ===\n');
  for (const [s,n,e] of results) console.log(`${s}  ${n}${e?'\n        '+e:''}`);
  const f=results.filter(r=>r[0]==='FAIL').length;
  console.log(`\n${results.length-f}/${results.length} passed`); return f; };
process.on('uncaughtException', async e => { console.error('\nPROBE ERROR: '+e.message); report();
  await browser.close(); server.close(); process.exit(1); });

const CONSTANTS = path.resolve(__dirname,'..','..','src','jsMain','kotlin','org','osada','Constants.kt');
const major = (fs.readFileSync(CONSTANTS,'utf8').match(/const val VERSION = "(\d+)\.(\d+)/)||[]).slice(1,3).join('.');

// English strings are asserted below, so pin the language instead of inheriting the host locale.
await page.evaluateOnNewDocument(()=>{ try { localStorage.setItem('osada-language','en'); } catch (e) {} });
await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2500);

// ---------------------------------------------- start a real deploy-phase campaign through the UI

const started = await page.evaluate(()=>{
  window.game.ui.startMenuButton('campaign');
  const sel = document.querySelector('#smCampSel select') || document.getElementById('smCampSel');
  const options = [...sel.options];
  const target = options.find(o=>/Forward, Comrade/i.test(o.text));
  if (!target) return { found: false, sample: options.slice(0,3).map(o=>o.text) };
  sel.selectedIndex = target.index;
  if (sel.onchange) sel.onchange();
  document.getElementById('smCPlayBut').click();
  return { found: true, campaign: target.text };
});
ok('the deploy-phase campaign is in the register', started.found, JSON.stringify(started));
await sleep(8000);

// Kotlin/JS collections are not always plain arrays across the @JsExport boundary, so normalize
// before treating one as a list.
const deployState = () => page.evaluate(()=>{
  const asArray = (c) => {
    if (!c) return [];
    if (Array.isArray(c)) return c;
    if (typeof c.toArray === 'function') {
      const a = c.toArray();
      return Array.isArray(a) ? a : Array.prototype.slice.call(a);
    }
    const n = (c.size !== undefined ? c.size : c.length);
    if (typeof n === 'number' && typeof c.get === 'function') {
      const out = []; for (let i = 0; i < n; i++) out.push(c.get(i)); return out;
    }
    try { return Array.from(c); } catch (e) { return []; }
  };
  const g = window.game;
  const player = g.campaignPlayer || g.scenario?.map?.currentPlayer;
  const core = asArray(player && player.getCoreUnitList ? player.getCoreUnitList() : null);
  const hexOf = (u) => (u && typeof u.getHex === 'function') ? u.getHex() : undefined;
  return {
    deployPhase: g.campaign?.deployPhase === true,
    campaignFile: g.campaign?.file ?? null,
    scenarioIndex: g.campaign?.currentScenarioIndex ?? null,
    turn: g.scenario?.map?.turn ?? null,
    coreCount: core.length,
    undeployed: core.filter(u=>!hexOf(u)).length,
    hasUndeployed: !!(player && player.hasUndeployedUnits && player.hasUndeployedUnits()),
    started: !!g.gameStarted,
  };
});

const before = await deployState();
ok('the campaign loaded in its deploy phase', before.deployPhase && before.campaignFile !== null,
   JSON.stringify(before));
ok('it has a core roster to place', before.coreCount > 0, JSON.stringify(before));
ok('some of that roster is still undeployed before the mission runs',
   before.hasUndeployed || before.undeployed > 0, JSON.stringify(before));

// The index is `{rows:[...]}`, not a map keyed by run id.
const storedIndex = () => page.evaluate((major)=>{
  const idx = JSON.parse(localStorage.getItem('osada-save-index-'+major) || '{"rows":[]}');
  const row = (idx.rows||[])[0];
  return { count: (idx.rows||[]).length, phase: row?.phase ?? null, id: row?.campaignRunId ?? null,
           turn: row?.turn ?? null, operation: row?.campaignScenario ?? null };
}, major);
const storedPhase = await storedIndex();
ok('a snapshot was written for the deploy-phase state', storedPhase.count === 1, JSON.stringify(storedPhase));
ok('the snapshot is filed under the campaign it belongs to',
   storedPhase.id === before.campaignFile, JSON.stringify(storedPhase));
// NOT asserted as `phase === 'deployment'`: `derivePhase` gates that label on `!gameStarted`, and
// `setupGameState` sets `gameStarted` before the first autosave, so a deploy-phase save is stored as
// `playerTurn`. The round trip below is what the roadmap item actually requires; the unreachable
// PHASE_DEPLOYMENT label is recorded as a separate finding rather than asserted either way here.
ok('the snapshot records a phase the validator accepts on read-back',
   typeof storedPhase.phase === 'string' && storedPhase.phase.length > 0, String(storedPhase.phase));

// ------------------------------------------------------------------ refresh: the actual round trip

await page.reload({waitUntil:'networkidle2'}); await sleep(8000);
const after = await deployState();
ok('the run restored itself after the refresh', after.campaignFile === before.campaignFile,
   JSON.stringify(after));
ok('the mission did not start early: the turn counter did not advance',
   after.turn === before.turn, `${before.turn} -> ${after.turn}`);
ok('the campaign is still on the same operation',
   after.scenarioIndex === before.scenarioIndex, `${before.scenarioIndex} -> ${after.scenarioIndex}`);
ok('the core roster survived the refresh intact',
   after.coreCount === before.coreCount, `${before.coreCount} -> ${after.coreCount}`);
ok('the reserve is still undeployed rather than auto-placed',
   after.hasUndeployed === before.hasUndeployed && after.undeployed === before.undeployed,
   `undeployed ${before.undeployed} -> ${after.undeployed}, hasUndeployed ${before.hasUndeployed} -> ${after.hasUndeployed}`);

// -------------------------------------------------------- per-campaign export from the register

// Export acts on the register's SELECTED campaign, so select one the way a player would.
const selectCampaign = (name) => page.evaluate((name)=>{
  window.game.ui.startMenuButton('campaign');
  const sel = document.querySelector('#smCampSel select') || document.getElementById('smCampSel');
  const t = [...sel.options].find(o=>o.text.includes(name));
  if (!t) return false;
  sel.selectedIndex = t.index;
  if (sel.onchange) sel.onchange();
  return true;
}, name);
const exportButtonState = () => page.evaluate(()=>{
  const b = document.getElementById('campaignRunExport');
  return { present: !!b, label: b?.textContent ?? null, title: b?.title ?? '',
           disabled: b ? b.classList.contains('osadaCampBackupButton--disabled') : null,
           ariaDisabled: b?.getAttribute('aria-disabled') };
});

await selectCampaign('Red Army Campaign'); await sleep(300);
const unplayed = await exportButtonState();
ok('Export campaign is on the register footer', unplayed.present, unplayed.label);
ok('for a campaign with no run it is disabled and says why',
   unplayed.disabled === true && unplayed.ariaDisabled === 'true' && unplayed.title.length > 0,
   JSON.stringify(unplayed));

await selectCampaign('Forward, Comrade'); await sleep(300);
const exported = await page.evaluate(()=>{
  // Capture the download instead of writing a file: the export uses a hidden anchor + data URL.
  const original = HTMLAnchorElement.prototype.click;
  let href = null, name = null;
  HTMLAnchorElement.prototype.click = function () { href = this.href; name = this.download; };
  const button = document.getElementById('campaignRunExport');
  const disabled = button ? button.classList.contains('osadaCampBackupButton--disabled') : null;
  if (button) button.click();
  HTMLAnchorElement.prototype.click = original;
  return {
    disabled, name,
    status: document.getElementById('campaignRunBackupStatus')?.textContent || '',
    json: href ? decodeURIComponent(href.replace(/^data:application\/force-download,/,'')) : null,
  };
});
ok('it is enabled for the campaign that has a run', exported.disabled === false, `disabled=${exported.disabled}`);
ok('the file name names the campaign it belongs to',
   /^osada-campaign-forward-\d+\.json$/.test(exported.name||''), exported.name || 'no download');
ok('the export reports itself', exported.status.length>0, exported.status);

const parsed = exported.json ? JSON.parse(exported.json) : null;
ok('the file declares its kind so the importer can tell it apart from a profile backup',
   parsed?.kind === 'osada-campaign-run', String(parsed?.kind));
ok('it carries the run metadata and a restorable current generation',
   !!parsed?.run?.metadata?.campaignRunId && !!parsed?.run?.current?.payload,
   JSON.stringify(Object.keys(parsed?.run||{})));
ok('a per-campaign export carries no profile-level state',
   !!parsed && !('rulesetProfiles' in parsed) && !('heroArchive' in parsed),
   Object.keys(parsed||{}).join(','));

// ---------------------------------- import: identification, preview, cancel-safety, then apply

// Seed a second, unrelated campaign run so isolation is provable rather than assumed.
await page.evaluate((major)=>{
  const key = 'osada-save-index-'+major;
  const idx = JSON.parse(localStorage.getItem(key));
  const first = idx.rows[0];
  const snapshot = JSON.parse(localStorage.getItem('osada-save-run-'+major+'-'+first.campaignRunId+'-current'));
  idx.rows.push({ ...first, campaignRunId: 'probe-other.json', campaignFile: 'probe-other.json',
                  campaignName: 'Unrelated Campaign', campaignScenario: 5, turn: 9 });
  localStorage.setItem(key, JSON.stringify(idx));
  localStorage.setItem('osada-save-run-'+major+'-probe-other.json-current',
    JSON.stringify({ ...snapshot, campaignRunId: 'probe-other.json', campaignScenario: 5, turn: 9 }));
}, major);

// The file being imported carries a DIFFERENT operation, so a successful import is visible in the
// index rather than being a no-op that looks like success.
const importedOperation = (parsed?.run?.metadata?.campaignScenario ?? 0) + 4;
const fileText = JSON.stringify({ ...parsed, run: { ...parsed.run,
  metadata: { ...parsed.run.metadata, campaignScenario: importedOperation, turn: 3 },
  current: { ...parsed.run.current, campaignScenario: importedOperation, turn: 3 } } });

const pick = async (text, fileName) => {
  await page.evaluate((text, fileName)=>{
    const input = document.getElementById('campaignRunImportFile');
    const dt = new DataTransfer();
    dt.items.add(new File([text], fileName, { type: 'application/json' }));
    input.files = dt.files;
    input.dispatchEvent(new Event('change'));
  }, text, fileName);
  await sleep(600);
};
const status = () => page.evaluate(()=>document.getElementById('campaignRunBackupStatus')?.textContent||'');

await selectCampaign('Forward, Comrade');
await sleep(400);
await pick(JSON.stringify({ runs: [], exportedAt: 1, gameVersion: 'x' }), 'profile.json');
const profileMsg = await status();
ok('a whole-profile backup is refused with a message about which file it is',
   /full profile backup/i.test(profileMsg), profileMsg);

await pick('{"kind":"osada-campaign-run"}', 'empty.json');
const emptyMsg = await status();
ok('a file with no readable run is refused', /not a readable campaign export/i.test(emptyMsg), emptyMsg);

await pick(fileText, 'import.json');
const preview = await page.evaluate(()=>{
  const box = document.getElementById('osadaConfirmCard');
  return {
    open: !!box,
    title: box?.querySelector('.uiMessageBoxTitle')?.textContent || '',
    body: box?.querySelector('.uiMessageBoxBody')?.textContent || '',
  };
});
ok('the import previews before it writes', preview.open, JSON.stringify(preview).slice(0,200));
ok('the preview names the affected campaign', /Forward, Comrade/i.test(preview.title), preview.title);
ok('it names the incoming operation and the one it would replace',
   /Operation \d+/.test(preview.body) && /replace/i.test(preview.body), preview.body.slice(0,220));
ok('it states that only this campaign changes', /Only this campaign changes/i.test(preview.body),
   preview.body.slice(-160));

// Cancel must be inert -- the ProfileBackup bug class this importer must not repeat.
const indexRow = (id) => page.evaluate((major,id)=>{
  const idx = JSON.parse(localStorage.getItem('osada-save-index-'+major) || '{"rows":[]}');
  const row = (idx.rows||[]).find(r=>r.campaignRunId === id);
  return row ? { operation: row.campaignScenario, turn: row.turn } : null;
}, major, id);

const runId = parsed.run.metadata.campaignRunId;
const beforeCancel = await indexRow(runId);
await page.evaluate(()=>document.querySelector('#osadaConfirmCard .osadaConfirmBoxCancel').click());
await sleep(400);
const afterCancel = await indexRow(runId);
ok('cancelling the confirmation writes nothing',
   JSON.stringify(beforeCancel) === JSON.stringify(afterCancel),
   `${JSON.stringify(beforeCancel)} -> ${JSON.stringify(afterCancel)}`);

await pick(fileText, 'import.json');
await page.evaluate(()=>document.querySelector('#osadaConfirmCard .osadaConfirmBoxConfirm').click());
await sleep(800);
const afterImport = await indexRow(runId);
const otherAfter = await indexRow('probe-other.json');
ok('confirming replaces that campaign run',
   afterImport?.operation === importedOperation,
   `${JSON.stringify(afterImport)} expected operation ${importedOperation}`);
ok('and leaves every other campaign untouched',
   otherAfter?.operation === 5 && otherAfter?.turn === 9, JSON.stringify(otherAfter));
const successMsg = await status();
ok('the import reports the campaign it imported', /Forward, Comrade/i.test(successMsg), successMsg);

ok('no runtime JS errors', errs.length===0, errs.join(' | '));

const failed = report();
await browser.close(); server.close();
process.exit(failed?1:0);
