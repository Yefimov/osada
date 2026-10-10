#!/usr/bin/env node
// Corpus gate for authored scenario placement (docs/README.md C1).
//
// For every deployed scenario XML it reports:
//   * occupancy collisions -- two units authored into the same layer of one hex. The runtime keeps
//     only the last (`Hex.setUnit`), so the earlier records are silently lost. Layer follows the
//     runtime's own rule: AIR movement method of the carrier override when `carrier` > 0, else of
//     the unit's own equipment (`GameUnit.unitData` / `UnitPredicates.isAir`);
//   * gameplay hexes outside the loaded grid -- units or victory/deploy/escape/trigger/mine/supply
//     markers beyond the rows/cols the map image can carry (`ScenarioLoader.loadTerrainImage`:
//     cols = min(authored, (width + 15) / 45 + 1), rows = min(authored, height / 50 + 1)), or at a
//     negative coordinate.
//
// Legacy findings are listed in scenario-grid-baseline.json while they are repaired. The gate
// fails on any finding NOT in the baseline, and also on a baseline entry that no longer occurs, so
// the baseline shrinks as content is fixed. `--write-baseline` regenerates it from the corpus.
//
// Usage: node scripts/verify/check-scenario-grid.mjs [--write-baseline] [--verbose]

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const served = path.join(root, "src/jsMain/resources");
const dataDir = path.join(served, "resources/scenarios/data");
const equipmentDir = path.join(served, "resources/equipment/eqp-united");
const baselinePath = path.join(here, "scenario-grid-baseline.json");

const AIR_MOVEMENT = 5; // MovMethod.AIR
const HEX_COLUMN_STEP = 45;
const HEX_SLANT_WIDTH = 15;
const HEX_ROW_HEIGHT = 50;
const GAMEPLAY_ATTRIBUTES = [
  "victory",
  "deploy",
  "escapeground",
  "escapeair",
  "trigger",
  "mines",
  "supply",
  "victiers0",
  "victiers1",
];

const args = new Set(process.argv.slice(2));

/** Movement method and hangar capacity of every equipment record. */
function loadEquipment() {
  const movement = new Map();
  const hangarCaps = new Map();
  for (const file of fs.readdirSync(equipmentDir)) {
    if (!file.startsWith("equipment-country-")) continue;
    const json = JSON.parse(fs.readFileSync(path.join(equipmentDir, file), "utf8"));
    const movIndex = json.parsehints.indexOf("movmethod");
    const hangarIndex = json.parsehints.indexOf("hangarcap");
    for (const [eqid, record] of Object.entries(json.units)) {
      movement.set(Number(eqid), record[movIndex]);
      hangarCaps.set(Number(eqid), Number(record[hangarIndex]) || 0);
    }
  }
  return { movement, hangarCaps };
}

/** Width and height of a PNG or JPEG, read from the header (a `.png` can be a JPEG). */
function imageSize(file) {
  const bytes = fs.readFileSync(file);
  if (bytes.readUInt32BE(0) === 0x89504e47) return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
  if (bytes[0] === 0xff && bytes[1] === 0xd8) {
    let offset = 2;
    while (offset < bytes.length) {
      if (bytes[offset] !== 0xff) {
        offset++;
        continue;
      }
      const marker = bytes[offset + 1];
      const length = bytes.readUInt16BE(offset + 2);
      const startOfFrame = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
      if (startOfFrame) return { width: bytes.readUInt16BE(offset + 7), height: bytes.readUInt16BE(offset + 5) };
      offset += 2 + length;
    }
  }
  return null;
}

function attributes(tag) {
  const result = {};
  for (const match of tag.matchAll(/([A-Za-z0-9_]+)="([^"]*)"/g)) result[match[1]] = match[2];
  return result;
}

function checkScenario(file, { movement, hangarCaps }) {
  const xml = fs.readFileSync(path.join(dataDir, file), "utf8");
  const findings = [];
  const mapTag = xml.match(/<map\s[^>]*>/);
  if (!mapTag) return findings;
  const map = attributes(mapTag[0]);
  let rows = Number(map.rows) || 0;
  let cols = Number(map.cols) || 0;
  const imagePath = map.image ? path.join(served, map.image) : null;
  const size = imagePath && fs.existsSync(imagePath) ? imageSize(imagePath) : null;
  if (size) {
    const coveredCols = Math.floor((size.width + HEX_SLANT_WIDTH) / HEX_COLUMN_STEP) + 1;
    const coveredRows = Math.floor(size.height / HEX_ROW_HEIGHT) + 1;
    cols = cols > 0 ? Math.min(cols, coveredCols) : coveredCols;
    rows = rows > 0 ? Math.min(rows, coveredRows) : coveredRows;
  }

  for (const hexMatch of xml.matchAll(/<hex\s([^>]*?)(\/>|>([\s\S]*?)<\/hex>)/g)) {
    const hex = attributes(hexMatch[1]);
    const row = Number(hex.row);
    const col = Number(hex.col);
    const units = [...(hexMatch[3] ?? "").matchAll(/<unit\s[^>]*>/g)].map((m) => attributes(m[0]));
    const markers = GAMEPLAY_ATTRIBUTES.filter((name) => hex[name] !== undefined && hex[name] !== "0");
    const outside = row < 0 || col < 0 || (size && (row >= rows || col >= cols));
    if (outside && (units.length > 0 || markers.length > 0)) {
      const what = [...markers, ...(units.length ? [`${units.length} unit(s)`] : [])].join(", ");
      findings.push(`${file} r${row}c${col}: outside the ${rows}x${cols} grid (${what})`);
    }
    const layers = { surface: [], air: [] };
    let container = null;
    let aboard = 0;
    for (const unit of units) {
      // `aboard="1"` starts the unit inside the surface unit listed before it on this hex
      // (`ScenarioHexParser.stowAboard`). It takes no slot while that container has hangar room;
      // past the room the runtime falls back to the slot, so it counts as a collision again.
      if (unit.aboard === "1" && container && container.owner === unit.owner && aboard < (hangarCaps.get(Number(container.id)) ?? 0)) {
        aboard++;
        continue;
      }
      const carrier = Number(unit.carrier) || 0;
      const eqid = carrier > 0 ? carrier : Number(unit.id);
      const layer = movement.get(eqid) === AIR_MOVEMENT ? "air" : "surface";
      if (layer === "surface") {
        container = unit;
        aboard = 0;
      }
      layers[layer].push(unit.id);
    }
    for (const [layer, ids] of Object.entries(layers)) {
      if (ids.length > 1) {
        findings.push(`${file} r${row}c${col}: ${ids.length} ${layer} units (${ids.join(", ")}); ${ids.length - 1} overwritten`);
      }
    }
  }
  return findings;
}

const equipment = loadEquipment();
const findings = fs
  .readdirSync(dataDir)
  .filter((file) => file.endsWith(".xml"))
  .sort()
  .flatMap((file) => checkScenario(file, equipment));

if (args.has("--write-baseline")) {
  fs.writeFileSync(baselinePath, `${JSON.stringify(findings, null, 2)}\n`);
  console.log(`scenario grid check: wrote ${findings.length} baseline findings`);
  process.exit(0);
}

const baseline = new Set(fs.existsSync(baselinePath) ? JSON.parse(fs.readFileSync(baselinePath, "utf8")) : []);
const current = new Set(findings);
const added = findings.filter((finding) => !baseline.has(finding));
const resolved = [...baseline].filter((finding) => !current.has(finding));
if (args.has("--verbose")) findings.forEach((finding) => console.log(`  ${finding}`));
console.log(
  `scenario grid check: ${findings.length} known findings in ${new Set(findings.map((f) => f.split(" ")[0])).size} scenarios`,
);
if (added.length) {
  console.error("New placement defects (not in scripts/verify/scenario-grid-baseline.json):");
  added.forEach((finding) => console.error(`  ${finding}`));
}
if (resolved.length) {
  console.error("Baseline entries that no longer occur -- remove them from the baseline:");
  resolved.forEach((finding) => console.error(`  ${finding}`));
}
process.exit(added.length || resolved.length ? 1 : 0);
