// Portrait composition core — v2 (head-centric dossier). Pure, dependency-free ES module.
//
// Adds over v1: face archetypes (features never mixed freely), split uniform/hair layers,
// headgear hair-mode occlusion, and WEIGHTED constraints (rank↔age, gender↔facial hair,
// branch↔headgear, season↔uniform, uncommon injuries). Reuses SeededRandom's FNV-1a via
// portrait-core.mjs so seeding stays consistent across the system.

// FNV-1a twin of SeededRandom.seedFrom. Kept here because the deleted v1 generator used to export
// this helper; v2 must remain a self-contained module now that v1 no longer ships.
function seedFrom(...parts) {
    let hash = 0x811c9dc5 | 0;
    const text = parts.join('\0');
    for (let i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619);
    return hash;
}

// One mulberry32 draw from a fully-formed 32-bit seed — same stream as SeededRandom.
function draw(seed) {
    let t = (seed + 0x6d2b79f5) | 0;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t = (t + Math.imul(t ^ (t >>> 7), t | 61)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}

const rand = (seed, salt) => draw(seedFrom(String(seed), salt));
const chance = (seed, salt, p) => rand(seed, salt) < p;

function pickList(seed, salt, list) {
    if (!list || list.length === 0) return null;
    return list[Math.min(Math.floor(rand(seed, salt) * list.length), list.length - 1)];
}

/** Weighted key by { key: weight }, keys walked in sorted order so the choice is stable. */
function weightedPick(seed, salt, weights) {
    const entries = Object.entries(weights).sort((a, b) => (a[0] < b[0] ? -1 : 1));
    const total = entries.reduce((s, [, w]) => s + w, 0);
    let r = rand(seed, salt) * total;
    for (const [key, w] of entries) {
        r -= w;
        if (r < 0) return key;
    }
    return entries[entries.length - 1][0];
}

const idsIn = (manifest, category) => manifest.layers[category].map((l) => l.id);

// Female profiles avoid the heavy-jaw archetypes so the lower face never reads masculine.
и const FEMALE_ARCHETYPES = ['female_oval', 'female_heart', 'female_broad', 'female_square', 'female_long'];
const FEMALE_HAIR_STYLES = ['bob', 'braid', 'bun', 'waves'];

const femaleHairBack = (style) => ({
    bob: 'hair_back_female_bob',
    braid: 'hair_back_female_braid',
    bun: 'hair_back_female_bun',
    waves: 'hair_back_female'
})[style];

const femaleHairFront = (style) => ({
    bob: 'hair_front_female_a',
    braid: 'hair_front_female_braided',
    bun: 'hair_front_female_bun',
    waves: 'hair_front_female_b'
})[style];

const femaleUnderHair = (style) => ({
    bob: 'under_hair_female_bob',
    braid: 'under_hair_female_braid',
    bun: 'under_hair_female_bun',
    waves: 'under_hair_female'
})[style];

function archetypeWeights(W, age, gender) {
    const base = W.ageArchetype[age];
    if (gender !== 'female') return Object.fromEntries(Object.entries(base).filter(([k]) => !FEMALE_ARCHETYPES.includes(k)));
    const soft = {};
    for (const k of FEMALE_ARCHETYPES) if (base[k]) soft[k] = base[k];
    return Object.keys(soft).length ? soft : {round_young: 1};
}

/** Resolve a wound override that may be a tag ("brow"), a full id ("wound_brow_bandage"), or null. */
function woundIdFor(manifest, val) {
    if (!val) return null;
    const byTag = manifest.layers.wound.find((l) => l.tags && l.tags.wound === val);
    if (byTag) return byTag.id;
    return manifest.layers.wound.find((l) => l.id === val)?.id || null;
}

const hairModeOf = (manifest, headgearId) => {
    const layer = manifest.layers.headgear.find((l) => l.id === headgearId);
    return (layer && layer.tags && layer.tags.hairMode) || 'FULL_HAIR';
};

const headgearLayer = (manifest, headgearId) => manifest.layers.headgear.find((l) => l.id === headgearId);

const underHairFor = (manifest, headgearId, seed) => {
    const layer = headgearLayer(manifest, headgearId);
    const compatible = layer && layer.tags && layer.tags.underHair;
    return pickList(seed, 'underhair', compatible || ['under_hair_temples']);
};

const underHairFemaleFor = (manifest, headgearId, style, seed) => {
    const layer = headgearLayer(manifest, headgearId);
    return layer?.tags?.underHairFemale
        ? pickList(seed, 'underhairfemale', layer.tags.underHairFemale)
        : femaleUnderHair(style);
};

const allowsHairBack = (manifest, headgearId) => headgearLayer(manifest, headgearId)?.tags?.hairBack !== false;

const jawFitFor = (manifest, archetype) => {
    const layer = manifest.layers.face.find((l) => l.id === `face_${archetype}`);
    return (layer && layer.tags && layer.tags.jawFit) || 'medium';
};

const shortHairBackFor = (manifest, archetype) => `hair_back_short_${jawFitFor(manifest, archetype)}`;

function fittedFacialHair(manifest, archetype, selected) {
    if (selected !== 'facial_beard' && selected !== 'facial_stubble') return selected;
    return `${selected}_${jawFitFor(manifest, archetype)}`;
}

/** id -> "v2/layers/<dir>/<id>.svg" (relative to the resources root). */
export function layerPath(manifest, id) {
    for (const category of Object.keys(manifest.layers)) {
        if (manifest.layers[category].some((l) => l.id === id)) {
            return `v2/layers/${manifest.categoryDir[category]}/${id}.svg`;
        }
    }
    return null;
}

/**
 * Compose a v2 portrait. input: { branch, rank, gender, season? }.
 * Everything else (age, archetype, facial hair, headgear, hair, marks, background) is derived by
 * the manifest's weighted constraints. Returns { layerIds, byCategory, meta }.
 */
export function compose(manifest, input, seed) {
    const W = manifest.weights;
    const poolId = input.poolId || 'ussr_1942';
    const pool = manifest.pools[poolId];
    if (!pool) return {layerIds: [], byCategory: {}, meta: {poolId: null}};
    const branch = input.branch;
    const rank = input.rank;
    const gender = (input.gender || 'male').toLowerCase() === 'female' ? 'female' : 'male';
    const season = input.season || (chance(seed, 'season', W.seasonWinter) ? 'winter' : 'summer');

    const age = input.age || weightedPick(seed, 'age', W.rankAge[rank] || {middle: 1});
    const archetype = weightedPick(seed, 'arch', archetypeWeights(W, age, gender));

    // Headgear (branch × season), then hair by the headgear's occlusion mode. An explicit override
    // (id or 'none') lets a review page pin a specific cap; otherwise it is weighted by branch+season.
    const headgearWeights = pool.headgear || W.headgear;
    const headgearKey = input.headgear !== undefined ? input.headgear : weightedPick(seed, 'headgear', headgearWeights[branch][season]);
    const headgear = headgearKey === 'none' ? null : headgearKey;
    const hairMode = headgear ? hairModeOf(manifest, headgear) : 'FULL_HAIR';
    const woundId = input.wound !== undefined
        ? woundIdFor(manifest, input.wound)
        : (chance(seed, 'woundOn', W.woundChance) ? pickList(seed, 'wound', idsIn(manifest, 'wound')) : null);

    const chosen = {};
    const put = (cat, id) => {
        if (id) chosen[cat] = id;
    };

    put('background', pickList(seed, 'bg', idsIn(manifest, 'background')));
    const uniformBack = pool.uniformBack || `back_${branch}`;
    put('uniform_back', gender === 'female' ? `${uniformBack}_female` : uniformBack);

    // Hair split, gender + headgear aware. Females always keep female hair (never bald): headgear
    // only hides the crown, so long back/side hair still frames the face and shows below a hat.
    if (gender === 'female') {
        const style = pickList(seed, 'hairstyle', FEMALE_HAIR_STYLES);
        if (!headgear || allowsHairBack(manifest, headgear)) put('hair_back', femaleHairBack(style));
        if (hairMode === 'FULL_HAIR') put('hair_front', femaleHairFront(style));
        else if (hairMode === 'UNDER_CAP') put('under_headgear_hair', underHairFemaleFor(manifest, headgear, style, seed));
        // UNDER_FUR_HAT / UNDER_FLIGHT_HELMET: hair_back_female still shows at the sides and nape.
    } else if (hairMode === 'FULL_HAIR') {
        put('hair_back', chance(seed, 'hairvol', 0.35) ? 'hair_back_full' : shortHairBackFor(manifest, archetype));
        put('hair_front', pickList(seed, 'hairfront', manifest.hairFront.FULL_HAIR));
    } else if (hairMode === 'UNDER_CAP') {
        if (allowsHairBack(manifest, headgear)) put('hair_back', shortHairBackFor(manifest, archetype));
        put('under_headgear_hair', underHairFor(manifest, headgear, seed));
    } // male UNDER_FUR_HAT / UNDER_FLIGHT_HELMET / NONE -> no hair

    put('face', `face_${archetype}`);
    if (input.expression) put('expression', `expression_${input.expression}`);
    if (age === 'middle') put('age_face', 'age_face_light');
    else if (age === 'old') put('age_face', 'age_face_heavy');

    const wantScar = input.scar !== undefined ? input.scar : chance(seed, 'scarOn', W.scarChance);
    if (wantScar) put('scar', pickList(seed, 'scar', idsIn(manifest, 'scar')));

    // HARD INVARIANT (not a weight): female profiles may only ever use facial_clean. Any facial-hair
    // roll is confined to the male branch, so no seed, override or fallback can put a beard on a woman.
    const facialHair = gender === 'female' ? 'facial_clean' : weightedPick(seed, 'facial', W.facialByAge[age]);
    put('facial_hair', fittedFacialHair(manifest, archetype, facialHair));

    put('uniform_front_collar', pool.collarBySeason?.[season] || pool.collar || (branch === 'aviation' ? 'collar_aviation' : `collar_${branch}_${season}`));
    put('rank', `${pool.rankPrefix || 'rank_'}${rank}`);
    put('branch', pool.branchLayer || `branch_${branch}`);
    const accessories = poolId === 'ancient_rebel'
        ? []
        : (['soviet_interwar', 'revolution_1919', 'spanish_republic_1936', 'white_army_1919'].includes(poolId)
            ? ['accessory_round_spectacles', 'accessory_pince_nez']
            : ['accessory_round_spectacles', 'accessory_wire_spectacles']);
    const accessory = poolId === 'ancient_rebel'
        ? null
        : (input.accessory !== undefined
            ? input.accessory
            : (chance(seed, 'accessory', W.accessoryChance) ? pickList(seed, 'accessory-type', accessories) : null));
    if (woundId !== 'wound_eye_patch' && accessory !== 'none') {
        put('accessory', accessory);
    }
    if (headgear) put('headgear', headgear);
    if (woundId) put('wound', woundId);

    const layerIds = manifest.order.map((c) => chosen[c]).filter(Boolean);
    const hairGray = age === W.grayHairFromAge;
    return {layerIds, byCategory: chosen, meta: {age, archetype, season, hairMode, gender, hairGray, poolId}};
}
