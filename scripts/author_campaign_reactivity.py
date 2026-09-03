"""
Authors the reactive layer onto the two story campaigns.

Adds, per scenario:
  * outcome reaction lines  - conditional openers keyed on the REAL previous result
  * choice effects          - paired trade-offs, each branch giving a different advantage
  * scenario actions        - optional objectives resolved from real end-of-scenario state

Everything written here is additive. Routing, prestige awards, outcome text and the existing
dialogue bodies are untouched. Re-running the script replaces, rather than duplicates, previously
authored reaction lines / effects / actions.

DO NOT RE-RUN THIS SCRIPT ON ITS OWN (2026-08-17). It is idempotent only with respect to itself.
`scripts/author_campaign_objectives.py` runs AFTER it and rewires each `reaction-*` line's `next`
through its own `callback-*` node; re-running this script recomputes those pointers back to the
plain opener and drops the callbacks out of the conversation. If you must re-run it, run
`author_campaign_objectives.py` immediately afterwards and diff the result. For a one-off data
correction (an eqid, a text fix), patch the campaign JSON directly instead.

Equipment IDs below were read out of resources/equipment/eqp-united/equipment-country-{188,189}
(both campaigns use the eqp-kaiser set); country 189 = November Revolution, 188 = Hungarian
Soviet Republic. Every eqid referenced here exists in the campaign's own country file.

WARNING - eqids are NOT stable across an efile merge. `tools/eqp-merge` hands ids out over a list
sorted by country/unit-class/name, so adding any efile renumbers everything (see
GameStateSerializer.SAVE_FORMAT_VERSION). Deployed scenario XML was carried across by
tools/eqp-merge/remap_united_ids.py; these constants were NOT, and every November Revolution id
here silently pointed at another country's equipment until 2026-08-15. A grantUnit naming an eqid
outside the loaded country files grants nothing at all - `CampaignEffectApplier.applyGrantUnit`
warns, adds no unit, and still marks the effect applied - so the branch quietly became strictly
worse than its counterpart. `scripts/check_campaign_dialogue.py` catches this; run it after any
merge and re-read the ids out of the country file rather than trusting the numbers below.
"""

import collections
import io
import json

DATA = "src/jsMain/resources/resources/campaigns/data/%s.json"

# --------------------------------------------------------------------------- equipment

# November Revolution (country 189) - re-read from equipment-country-189.json on 2026-08-15
N_VOLKSMARINE = 46706   # Volksmarine - revolutionary sailors
N_SOLDATEN = 46704      # Soldaten - defecting soldiers
N_SPARTAKISTEN = 46705  # Spartakisten - radical worker militia, cheap and inexperienced
N_ENTRENCHED = 46711    # Eingegrabene Infanterie - fortification

# Hungarian Soviet Republic (country 188) - re-read from equipment-country-188.json on 2026-08-17.
# The previous 39xxx ids were the same defect the November Revolution block hit above: they named
# country 85's warlord-China equipment ("Muslim Infantry", "Light Horse", "Bre.14/400"), so every
# Hungarian grantUnit branch silently granted nothing.
H_WORKER = 46655        # Gyari Munkas - cheapest worker militia
H_INTERNATIONAL = 46659  # Nemzetkozi Dandar - International Brigade
H_ENTRENCHED = 46673    # Beasott Gyalogsag - fortification
H_MOUNTAIN_GUN = 46689  # 7.5cm Skoda GebG M15
H_ARMOURED_TRAIN = 46680  # Pancelvonat
H_SUPPLY_COLUMN = 46682  # Szallitooszlop

RAPID = "favours_rapid_offensive"
METHODICAL = "favours_methodical_consolidation"


def flag(eid, name):
    return {"id": eid, "type": "setFlag", "flag": name}


def unflag(eid, name):
    return {"id": eid, "type": "clearFlag", "flag": name}


def prestige(eid, amount):
    return {"id": eid, "type": "prestige", "amount": amount}


def experience(eid, amount, uclass=None):
    e = {"id": eid, "type": "experience", "amount": amount}
    if uclass is not None:
        e["unitClass"] = uclass
    return e


def resupply(eid, strength):
    return {"id": eid, "type": "resupply", "strength": strength, "refuel": True, "rearm": True}


def grant(eid, eqid, exp=0, strength=10):
    return {"id": eid, "type": "grantUnit", "eqid": eqid, "experience": exp, "strength": strength}


# --------------------------------------------------------------------------- effects
# Each pair trades one advantage for a DIFFERENT advantage - never strictly-better vs worse.

EFFECTS = {
    # ---------------------------------------------------------------- November Revolution
    # The Kiel prisoner decision. Both branches are also PLAYED OUT on the map by the scenario
    # events in n_kiel.xml, so the numbers here are only half of each branch:
    #
    #   free-prisoners-first     the part of the group that got out IS this Volksmarine unit, so
    #                            it is deliberately strength 5, not a fresh strength-10 formation.
    #                            The `sailors_liberated` flag makes `kiel-partial-breakout` fire at
    #                            mission start, leaving a strength-4 remnant in the yard with the
    #                            alarm already raised.
    #   secure-communications-first  keeps the larger prestige/experience award, but REDUCED from
    #                            200/25: this branch now also has a live chance at the whole group
    #                            (`kiel-prison-alarm` -> `kiel-prisoners-rescued`), and it must not
    #                            end up holding both the big bonuses and a permanent core unit.
    "free-prisoners-first": [
        flag("n_kiel.choice.prisoners.flag", "sailors_liberated"),
        unflag("n_kiel.choice.prisoners.clear", "communications_secured"),
        grant("n_kiel.choice.prisoners.unit", N_VOLKSMARINE, exp=60, strength=5),
        prestige("n_kiel.choice.prisoners.prestige", 50),
    ],
    "secure-communications-first": [
        flag("n_kiel.choice.comms.flag", "communications_secured"),
        unflag("n_kiel.choice.comms.clear", "sailors_liberated"),
        prestige("n_kiel.choice.comms.prestige", 140),
        experience("n_kiel.choice.comms.experience", 15),
    ],
    # Expansion buys ground and numbers; consolidation buys a restored, fortified core.
    "regional-columns": [
        flag("n_willhelmsh.choice.expand.flag", RAPID),
        unflag("n_willhelmsh.choice.expand.clear", METHODICAL),
        grant("n_willhelmsh.choice.expand.unit", N_SOLDATEN),
        prestige("n_willhelmsh.choice.expand.prestige", 130),
    ],
    "secure-base": [
        flag("n_willhelmsh.choice.consolidate.flag", METHODICAL),
        unflag("n_willhelmsh.choice.consolidate.clear", RAPID),
        resupply("n_willhelmsh.choice.consolidate.supply", 10),
        grant("n_willhelmsh.choice.consolidate.unit", N_ENTRENCHED, exp=20),
    ],
    # Infrastructure pays in prestige and competence; mobilisation pays in bodies and costs
    # production - the classic "arm the workers" exchange.
    "take-infrastructure": [
        flag("n_frankfurt.choice.infrastructure.flag", "railways_controlled"),
        unflag("n_frankfurt.choice.infrastructure.clear", "workers_armed"),
        prestige("n_frankfurt.choice.infrastructure.prestige", 170),
        experience("n_frankfurt.choice.infrastructure.experience", 20),
    ],
    "declare-council": [
        flag("n_frankfurt.choice.council.flag", "workers_armed"),
        unflag("n_frankfurt.choice.council.clear", "railways_controlled"),
        grant("n_frankfurt.choice.council.unit1", N_SPARTAKISTEN),
        grant("n_frankfurt.choice.council.unit2", N_SPARTAKISTEN),
        prestige("n_frankfurt.choice.council.production", -60),
    ],
    "government-quarter": [
        flag("n_berlin.choice.government.flag", "struck_for_government_quarter"),
        prestige("n_berlin.choice.government.prestige", 150),
        experience("n_berlin.choice.government.experience", 15),
    ],
    "barracks-stations": [
        flag("n_berlin.choice.barracks.flag", "secured_military_infrastructure"),
        resupply("n_berlin.choice.barracks.supply", 10),
        grant("n_berlin.choice.barracks.unit", N_VOLKSMARINE, exp=40),
    ],
    # ------------------------------------------------------------ Hungarian Soviet Republic
    "railway-first": [
        flag("rhu190416.choice.railway.flag", METHODICAL),
        unflag("rhu190416.choice.railway.clear", RAPID),
        grant("rhu190416.choice.railway.unit", H_ENTRENCHED, exp=20),
        resupply("rhu190416.choice.railway.supply", 9),
    ],
    "mobile-guard": [
        flag("rhu190416.choice.mobile.flag", RAPID),
        unflag("rhu190416.choice.mobile.clear", METHODICAL),
        prestige("rhu190416.choice.mobile.prestige", 150),
        experience("rhu190416.choice.mobile.experience", 20, uclass=3),
    ],
    "counterstroke": [
        flag("rhu190424.choice.counterstroke.flag", RAPID),
        unflag("rhu190424.choice.counterstroke.clear", METHODICAL),
        prestige("rhu190424.choice.counterstroke.prestige", 140),
        experience("rhu190424.choice.counterstroke.experience", 25),
    ],
    "phased-retreat": [
        flag("rhu190424.choice.retreat.flag", METHODICAL),
        unflag("rhu190424.choice.retreat.clear", RAPID),
        resupply("rhu190424.choice.retreat.supply", 10),
        grant("rhu190424.choice.retreat.unit", H_SUPPLY_COLUMN),
    ],
    "reserve-west": [
        flag("rhu190501.choice.west.flag", "reserve_held_west"),
        unflag("rhu190501.choice.west.clear", "reserve_pushed_east"),
        prestige("rhu190501.choice.west.prestige", 90),
        grant("rhu190501.choice.west.unit", H_ENTRENCHED, exp=20),
    ],
    "reserve-east": [
        flag("rhu190501.choice.east.flag", "reserve_pushed_east"),
        unflag("rhu190501.choice.east.clear", "reserve_held_west"),
        prestige("rhu190501.choice.east.prestige", 160),
        experience("rhu190501.choice.east.experience", 20),
    ],
    "strike-kisterenye": [
        flag("rhu190509.choice.kisterenye.flag", "kisterenye_pincer_struck"),
        unflag("rhu190509.choice.kisterenye.clear", "workers_armed"),
        experience("rhu190509.choice.kisterenye.experience", 30),
        prestige("rhu190509.choice.kisterenye.prestige", 80),
    ],
    "reinforce-workers": [
        flag("rhu190509.choice.workers.flag", "workers_armed"),
        unflag("rhu190509.choice.workers.clear", "kisterenye_pincer_struck"),
        grant("rhu190509.choice.workers.unit1", H_WORKER),
        grant("rhu190509.choice.workers.unit2", H_WORKER),
        prestige("rhu190509.choice.workers.production", -50),
    ],
    "turning-attack": [
        flag("rhu190523.choice.turning.flag", "miskolc_turning_attack"),
        unflag("rhu190523.choice.turning.clear", "miskolc_concentric_attack"),
        experience("rhu190523.choice.turning.experience", 25, uclass=3),
        prestige("rhu190523.choice.turning.prestige", 120),
    ],
    "concentric-attack": [
        flag("rhu190523.choice.concentric.flag", "miskolc_concentric_attack"),
        unflag("rhu190523.choice.concentric.clear", "miskolc_turning_attack"),
        grant("rhu190523.choice.concentric.unit", H_MOUNTAIN_GUN, exp=20),
        resupply("rhu190523.choice.concentric.supply", 9),
    ],
    "rapid-pursuit": [
        flag("rhu190530.choice.pursuit.flag", RAPID),
        unflag("rhu190530.choice.pursuit.clear", METHODICAL),
        prestige("rhu190530.choice.pursuit.prestige", 170),
        experience("rhu190530.choice.pursuit.experience", 20),
    ],
    "prepared-attack": [
        flag("rhu190530.choice.prepared.flag", METHODICAL),
        unflag("rhu190530.choice.prepared.clear", RAPID),
        grant("rhu190530.choice.prepared.unit", H_ARMOURED_TRAIN, exp=20),
        resupply("rhu190530.choice.prepared.supply", 10),
    ],
    "railway-axis": [
        flag("rhu190609.choice.railway.flag", "railways_controlled"),
        unflag("rhu190609.choice.railway.clear", "parallel_valley_columns"),
        grant("rhu190609.choice.railway.unit", H_SUPPLY_COLUMN),
        prestige("rhu190609.choice.railway.prestige", 130),
        resupply("rhu190609.choice.railway.supply", 9),
    ],
    "parallel-columns": [
        flag("rhu190609.choice.columns.flag", "parallel_valley_columns"),
        unflag("rhu190609.choice.columns.clear", "railways_controlled"),
        experience("rhu190609.choice.columns.experience", 25),
        prestige("rhu190609.choice.columns.prestige", 90),
    ],
    "elastic-defence": [
        flag("rhu190613.choice.elastic.flag", METHODICAL),
        unflag("rhu190613.choice.elastic.clear", RAPID),
        resupply("rhu190613.choice.elastic.supply", 10),
        grant("rhu190613.choice.elastic.unit", H_ENTRENCHED, exp=20),
    ],
    "local-counterattack": [
        flag("rhu190613.choice.counter.flag", RAPID),
        unflag("rhu190613.choice.counter.clear", METHODICAL),
        prestige("rhu190613.choice.counter.prestige", 160),
        experience("rhu190613.choice.counter.experience", 25),
    ],
    "szolnok-main": [
        flag("rhu190719.choice.szolnok.flag", "tisza_committed_deep"),
        unflag("rhu190719.choice.szolnok.clear", "tisza_held_central"),
        prestige("rhu190719.choice.szolnok.prestige", 180),
        experience("rhu190719.choice.szolnok.experience", 20),
    ],
    "support-flanks": [
        flag("rhu190719.choice.central.flag", "tisza_held_central"),
        unflag("rhu190719.choice.central.clear", "tisza_committed_deep"),
        resupply("rhu190719.choice.central.supply", 10),
        grant("rhu190719.choice.central.unit", H_INTERNATIONAL, exp=40),
    ],
    "hold-east-bank": [
        flag("rhu190724.choice.hold.flag", "final_stand_east"),
        prestige("rhu190724.choice.hold.prestige", 120),
        experience("rhu190724.choice.hold.experience", 20),
    ],
    "phased-withdrawal": [
        flag("rhu190724.choice.withdraw.flag", "final_withdrawal_west"),
        resupply("rhu190724.choice.withdraw.supply", 10),
        prestige("rhu190724.choice.withdraw.prestige", 60),
    ],
}

# ------------------------------------------------------------------- reaction lines
# Keyed by scenario. Each entry is {grade: text}; the speaker is inherited from the
# scenario's own opening line so portrait and role stay consistent.

REACTIONS = {
    "n_willhelmsh.xml": {
        "briliant": "They heard what your column did in Kiel before your train cleared the "
                    "junction. Men in these yards repeat the fleet's refusal as if they stood on "
                    "the decks themselves; the garrison has been arguing with itself ever since.",
        "victory": "Kiel still holds behind you. That is enough to make this room listen when its "
                   "delegate speaks—though the officers here have not lost their nerve, and they "
                   "still command the harbour batteries.",
        "tactical": "The casualty lists reached these yards before you did. Men still came to hear "
                    "the delegate from Kiel—but the officers had time to read the same telegrams, "
                    "bar the armouries and prepare for your column.",
    },
    "n_frankfurt.xml": {
        "briliant": "News of your coastal columns arrived before your train. The stationmaster has "
                    "already pushed two government troop trains onto sidings; Frankfurt's "
                    "garrison has spent two days deciding whether it still takes orders.",
        "victory": "The northern councils still hold, and their seals got your train through. But "
                   "Frankfurt is not a naval town: here the old authorities still have police, "
                   "officials and a printing press.",
        "tactical": "The casualty lists reached Frankfurt before you did. The officials have "
                    "pinned them beside the government's proclamations as proof that your councils "
                    "can be resisted—and the police guarding this platform believe them.",
    },
    "n_berlin.xml": {
        "briliant": "The delegates at Lehrter station were arguing over whether your column could "
                    "possibly arrive intact. Then it marched off the train before they finished. "
                    "Berlin is the last question, comrade, and for once we ask it from strength.",
        "victory": "They know your column in the working districts now. What they do not know is "
                   "whether Berlin will turn the councils' movement into power—or into a "
                   "negotiation conducted over their heads.",
        "tactical": "Your column reached Berlin behind its own casualty lists. The Majority "
                    "Socialists are reading those names when they speak of order and continuity, "
                    "and frightened people will listen to them.",
    },
    "rhu190424.xml": {
        "briliant": "The rear guard held longer than the enemy's timetable allowed for. That is the "
                    "first hour this army has won back since the intervention began.",
        "victory": "The withdrawal was carried out in order. The formations are intact, which is "
                   "more than the staff expected to be able to report.",
        "tactical": "The line broke before the transport was clear. We saved the army, but not its "
                    "equipment, and not its confidence.",
    },
    "rhu190501.xml": {
        "briliant": "The Romanians expected a rout and found a front. Budapest has stopped drafting "
                    "evacuation orders.",
        "victory": "We hold the Tisza. The government will call it a victory; the staff will call "
                   "it a reprieve.",
        "tactical": "We are on the Tisza because there was nowhere further to fall back to. The "
                    "Council is asking questions this army cannot yet answer.",
    },
    "rhu190509.xml": {
        "briliant": "The eastern front held, and the factories heard about it. Salgotarjan's workers "
                    "are arming themselves without waiting for authorisation.",
        "victory": "The front is stable. Whether the industrial districts can be held is a separate "
                   "question, and it is being decided this week.",
        "tactical": "The front held at a price the industry cannot pay twice. Salgotarjan's output "
                    "is already falling.",
    },
    "rhu190523.xml": {
        "briliant": "Salgotarjan is intact and the Czechoslovak command has lost a week it cannot "
                    "recover. The northern operation can begin from a position no one planned for.",
        "victory": "The industrial district is held. We may go north - carefully, and with the "
                   "supply columns kept close behind the mobile formations.",
        "tactical": "We hold the mines, not the initiative. Any northern operation begins with "
                    "formations that have not been rested or replaced.",
    },
    "rhu190530.xml": {
        "briliant": "Miskolc fell faster than the Czechoslovak staff believed possible. Their "
                    "northern grouping is separating from its supply.",
        "victory": "Miskolc is ours. The advance can continue, though the mobile formations have "
                   "already outrun their columns.",
        "tactical": "Miskolc cost us more than the map suggests. The corps needs replacements before "
                    "it is asked to exploit anything.",
    },
    "rhu190609.xml": {
        "briliant": "Kassa is taken. In its workers' districts, appeals for councils are being copied "
                    "in Slovak and passed north by rail. Janoušek's organisers speak openly of a "
                    "Slovak republic—but ink and expectation are not yet a government.",
        "victory": "Kassa is in our hands. Bundles of Slovak appeals are already moving north, and "
                   "revolutionary committees are forming wherever the old officials have lost their "
                   "nerve. No one yet agrees which committee has the right to speak for a new republic.",
        "tactical": "We took Kassa and very little else. Leaflets promise councils and a Slovak "
                    "republic as though ink could hold the road to Eperjes. Unless the army opens "
                    "that road, the proclamation remains a rumour.",
    },
    "rhu190613.xml": {
        "briliant": "The northern operation has succeeded beyond anything the Entente allowed for. "
                    "That success is precisely why Clemenceau's note arrived this morning.",
        "victory": "The north is held. And now Paris sends us a map with our borders already drawn "
                   "upon it.",
        "tactical": "The advance has stalled, and the note from Paris is timed to exploit exactly "
                    "that.",
    },
    "rhu190719.xml": {
        "briliant": "Comrade, this army withdrew from Slovakia undefeated. Whatever the government "
                    "has promised Paris, the formations that crossed back are still capable of an "
                    "offensive.",
        "victory": "We evacuated the north as ordered. The army is intact; its confidence in the "
                   "government is not.",
        "tactical": "We gave up Slovakia and gained nothing for it. Stromfeld resigned rather than "
                    "sign the order, and the staff has not recovered from it.",
    },
    "rhu190724.xml": {
        "briliant": "The Tisza crossing succeeded. I will not pretend the staff expected it - the "
                    "Romanian command certainly did not.",
        "victory": "We hold a bridgehead. Whether it can be supplied across a river under observed "
                   "artillery is the only question that now matters.",
        "tactical": "The offensive has broken on the far bank. What remains is to decide where this "
                    "army makes its last stand.",
    },
}

# Cumulative / doctrine lines, shown after the outcome reaction where they apply.
EXTRAS = {
    "n_berlin.xml": [
        {
            "id": "extra-workers-armed",
            "text": "Word that you armed the workers in Frankfurt reached Berlin yesterday. Our "
                    "factory battalions are in the streets now; they did not wait to be asked. "
                    "That is our strength—and exactly what the Majority Socialists point to when "
                    "they call for order.",
            "conditions": {"allFlags": ["workers_armed"]},
        },
        {
            "id": "extra-alternate-history",
            "text": "Every city you crossed was meant to be isolated before the next one moved. "
                    "Instead your trains carried delegates, rifles and confidence toward Berlin. "
                    "The ministries assumed obedience would travel faster than revolt; this week "
                    "proved the opposite.",
            "conditions": {"minSuccesses": 3},
        },
    ],
    "rhu190724.xml": [
        {
            "id": "extra-alternate-history",
            "text": "Paris expected the northern army to dissolve after the evacuation. Bucharest "
                    "expected the Tisza to be the end of us. Neither expectation has yet become a "
                    "fact; what remains is an army, a government, and very little time.",
            "conditions": {"minSuccesses": 8},
        },
    ],
}

# ------------------------------------------------------------------------ actions
# Only rule types that need no map coordinates, so nothing references data we have not
# verified. Two tiers give the complete/partial distinction the dialogue layer can read.
ACTIONS = [
    {"id": "core_intact", "type": "coreLossesAtMost", "maxLosses": 0},
    {"id": "core_losses_light", "type": "coreLossesAtMost", "maxLosses": 2},
]

AUTHORED_PREFIXES = ("reaction-", "extra-")


def author(campaign_file):
    path = DATA % campaign_file
    data = json.load(io.open(path, encoding="utf-8"),
                     object_pairs_hook=collections.OrderedDict)

    stats = collections.Counter()
    for entry in data:
        scenario = entry["scenario"]
        briefing = entry["briefing"]
        dialogue = briefing.get("dialogue", [])

        # Idempotency: drop anything this script previously inserted.
        dialogue = [l for l in dialogue if not str(l.get("id", "")).startswith(AUTHORED_PREFIXES)]

        # ---- effects on choices
        for line in dialogue:
            for choice in line.get("choices", []):
                eff = EFFECTS.get(choice["id"])
                if eff:
                    choice["effects"] = eff
                    stats["choices"] += 1
                else:
                    choice.pop("effects", None)
                    stats["choices_without_effects"] += 1

        # ---- reaction lines
        # Objective callbacks from a prior authoring pass remain in the file. The dramatic opener
        # is the first ordinary story node, not whichever callback happens to be first today.
        opener = next(
            line for line in dialogue
            if not str(line.get("id", "")).startswith("callback-")
        )
        extras = EXTRAS.get(scenario, [])
        first_extra = extras[0]["id"] if extras else opener["id"]

        new_lines = []
        for grade, text in sorted(REACTIONS.get(scenario, {}).items()):
            new_lines.append(collections.OrderedDict([
                ("id", "reaction-%s" % grade),
                ("speaker", opener["speaker"]),
                ("role", opener.get("role", "")),
                ("side", opener.get("side", "left")),
                ("portrait", opener.get("portrait")),
                ("text", text),
                ("conditions", {"previousOutcome": [grade]}),
                ("next", first_extra),
            ]))
            stats["reactions"] += 1

        for i, extra in enumerate(extras):
            nxt = extras[i + 1]["id"] if i + 1 < len(extras) else opener["id"]
            new_lines.append(collections.OrderedDict([
                ("id", extra["id"]),
                ("speaker", opener["speaker"]),
                ("role", opener.get("role", "")),
                ("side", opener.get("side", "left")),
                ("portrait", opener.get("portrait")),
                ("text", extra["text"]),
                ("conditions", extra["conditions"]),
                ("next", nxt),
            ]))
            stats["extras"] += 1

        briefing["dialogue"] = new_lines + dialogue
        # Merge, never replace. A LATER pass (scripts/author_campaign_objectives.py) writes richer
        # map-referencing objectives into the same list; overwriting it here silently deleted them
        # on every re-run, which is exactly the kind of loss the idempotency claim above promises
        # not to cause. Only this script's own two ids are refreshed.
        own = {action["id"] for action in ACTIONS}
        authored = [a for a in (entry.get("actions") or []) if a.get("id") not in own]
        entry["actions"] = authored + list(ACTIONS)
        stats["scenarios"] += 1

    io.open(path, "w", encoding="utf-8", newline="\n").write(
        json.dumps(data, ensure_ascii=False, indent=1) + "\n")
    return stats


for name in ("novemberrevolution", "rhu"):
    s = author(name)
    print("%-20s scenarios=%d reactions=%d extras=%d choices_with_effects=%d no_effects=%d" % (
        name, s["scenarios"], s["reactions"], s["extras"],
        s["choices"], s["choices_without_effects"]))
