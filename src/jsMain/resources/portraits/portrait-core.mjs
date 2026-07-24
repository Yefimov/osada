// Portrait composition core — pure, dependency-free ES module.
//
// Shared by the browser demo (portrait-demo.html) and the Node build scripts
// (scripts/portraits/*.mjs). Contains NO DOM and NO fetch: callers pass in the parsed
// manifest object. The RNG mirrors org.osada.hero.SeededRandom (mulberry32 + FNV-1a) bit for
// bit, so this module and the Kotlin PortraitComposer select the same layers for the same seed.

const GOLDEN_GAMMA = 0x6d2b79f5 | 0;

/** FNV-1a of parts joined by a single space — identical to SeededRandom.seedFrom. */
export function seedFrom(...parts) {
  let h = 0x811c9dc5 | 0; // FNV offset basis (-2128831035)
  const s = parts.join(' ');
  for (let i = 0; i < s.length; i++) {
    h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  }
  return h | 0;
}

/** One mulberry32 step. `box` is `{ s }`; mutates and returns a double in [0, 1). */
function nextDouble(box) {
  box.s = (box.s + GOLDEN_GAMMA) | 0;
  let t = box.s;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t = (t + Math.imul(t ^ (t >>> 7), t | 61)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}

/** Deterministic index in [0, bound), matching SeededRandom(seed).nextInt(bound). */
function nextInt(seed, bound) {
  if (bound <= 0) return 0;
  const box = { s: seed | 0 };
  return Math.min(Math.floor(nextDouble(box) * bound), bound - 1);
}

/** Seeded pick from `list`, keyed on the portrait seed and the category name. */
function pick(list, seed, category) {
  if (list.length === 0) return null;
  return list[nextInt(seedFrom(String(seed), category), list.length)];
}

function branchMatches(layer, branch) {
  const t = layer.tags && layer.tags.branch;
  return !t || t.indexOf(branch) !== -1;
}

function genderMatches(layer, gender) {
  const t = layer.tags && layer.tags.gender;
  return !t || t === gender;
}

/** id -> "layers/<dir>/<id>.svg", resolved from the manifest's category directories. */
export function layerPath(manifest, id) {
  for (const category of Object.keys(manifest.layers)) {
    if (manifest.layers[category].some((l) => l.id === id)) {
      return `layers/${manifest.categoryDir[category]}/${id}.svg`;
    }
  }
  return null;
}

/**
 * Deterministically choose one layer per category for `facts` at `seed`.
 * facts: { branch, gender, rank, ageBand, scar:boolean, wound:string|null }
 * Returns { layerIds: [...ordered by manifest.order], byCategory: { category: id } }.
 */
export function compose(manifest, facts, seed) {
  const L = manifest.layers;
  const byCategory = {};

  const put = (cat, layer) => {
    if (layer) byCategory[cat] = layer.id;
  };

  for (const category of manifest.order) {
    const all = L[category] || [];
    switch (category) {
      case 'uniform':
      case 'headgear':
        put(category, pick(all.filter((l) => branchMatches(l, facts.branch)), seed, category));
        break;
      case 'head':
        put(category, pick(all.filter((l) => genderMatches(l, facts.gender)), seed, category));
        break;
      case 'facialHair':
        if (facts.gender === 'female') put(category, all.find((l) => l.id === 'facial_clean'));
        else put(category, pick(all, seed, category));
        break;
      case 'rankInsignia':
        put(category, all.find((l) => l.tags && l.tags.rank === facts.rank) || all[0]);
        break;
      case 'branchBadge':
        put(category, all.find((l) => branchMatches(l, facts.branch)));
        break;
      case 'ageOverlay':
        put(category, all.find((l) => l.tags && l.tags.age === facts.ageBand) || all[0]);
        break;
      case 'scar':
        if (facts.scar) put(category, pick(all, seed, category));
        break;
      case 'woundOverlay':
        if (facts.wound) put(category, all.find((l) => l.tags && l.tags.wound === facts.wound));
        break;
      default:
        put(category, pick(all, seed, category));
    }
  }

  const layerIds = manifest.order
    .map((c) => byCategory[c])
    .filter((id) => id != null);
  return { layerIds, byCategory };
}

/** Order an arbitrary set of layer ids by the manifest stacking order (for re-rendering a save). */
export function orderLayerIds(manifest, ids) {
  const rank = {};
  manifest.order.forEach((c, i) => (rank[c] = i));
  const catOf = (id) => Object.keys(manifest.layers).find((c) => manifest.layers[c].some((l) => l.id === id));
  return [...ids].sort((a, b) => (rank[catOf(a)] ?? 99) - (rank[catOf(b)] ?? 99));
}
