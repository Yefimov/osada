"""
Authors map-based scenario objectives and the dialogue callbacks that react to them.

Runs AFTER author_campaign_reactivity.py. Adds two things that script deliberately left out:

  1. `hexesHeld` objectives built from REAL victory-hex coordinates, read out of each scenario's
     own XML (resources/scenarios/data/*.xml). Nothing here is invented: every row/col below was
     extracted from the `<hex ... victory="1">` elements of the scenario it belongs to.

  2. Callback lines in scenario N that react to what actually happened in scenario N-1 - an
     objective held or lost - and to the doctrine flags the player's earlier choices set.

Callbacks are what make a choice *echo*. Without them a choice grants a bonus and is forgotten;
with them the campaign remembers it out loud.

Idempotent: re-running preserves existing authored callbacks, adds any missing generated ones,
and rebuilds their flow in the order already present in the campaign JSON. This matters because
story editors may add character-specific `callback-*` grudges and vindications by hand.
"""

import collections
import io
import json

DATA = "src/jsMain/resources/resources/campaigns/data/%s.json"
CALLBACK_PREFIX = "callback-"

RAPID = "favours_rapid_offensive"
METHODICAL = "favours_methodical_consolidation"

# --------------------------------------------------------------------------- objectives
# Coordinates verified against resources/scenarios/data/<scenario>.xml victory hexes.
# `atLeast` below the hex count expresses partial success as a distinct, recordable fact.

OBJECTIVES = {
    "n_kiel.xml": [
        {"id": "naval_base_held", "type": "hexesHeld",
         "hexes": [{"row": 9, "col": 35}, {"row": 11, "col": 34}]},          # Marinebasis x2
        {"id": "town_hall_held", "type": "hexesHeld",
         "hexes": [{"row": 21, "col": 28}]},                                  # Rathaus
        {"id": "barracks_held", "type": "hexesHeld",
         "hexes": [{"row": 16, "col": 31}, {"row": 17, "col": 34}]},          # Kasernenkomplex
        # The detention compound at (10,34) leaves nothing behind in the end state to count -
        # a rescue CONVERTS the detainees - so this is the one fact that has to be read from the
        # scenario event that produced it. `kiel-prisoners-rescued` is declared in n_kiel.xml and
        # fires only when a revolutionary combat unit reaches the compound while detainees survive.
        {"id": "prisoners_rescued", "type": "eventFired",
         "events": ["kiel-prisoners-rescued"]},
    ],
    "n_willhelmsh.xml": [
        {"id": "region_fully_secured", "type": "hexesHeld",
         "hexes": [{"row": 10, "col": 6}, {"row": 15, "col": 15},
                   {"row": 22, "col": 8}, {"row": 30, "col": 37}]},           # Norden/Aurich/Emden/Oldenburg
        {"id": "region_partly_secured", "type": "hexesHeld", "atLeast": 2,
         "hexes": [{"row": 10, "col": 6}, {"row": 15, "col": 15},
                   {"row": 22, "col": 8}, {"row": 30, "col": 37}]},
    ],
    "n_frankfurt.xml": [
        {"id": "frankfurt_city_held", "type": "hexesHeld",
         "hexes": [{"row": 31, "col": 12}, {"row": 33, "col": 8},
                   {"row": 35, "col": 7}, {"row": 35, "col": 14}]},           # Frankfurt am Main x4
        {"id": "outer_towns_held", "type": "hexesHeld", "atLeast": 3,
         "hexes": [{"row": 10, "col": 23}, {"row": 15, "col": 2},
                   {"row": 16, "col": 36}, {"row": 22, "col": 6}]},
    ],
    "n_berlin.xml": [
        {"id": "airfield_held_at_end", "type": "hexesHeld",
         "hexes": [{"row": 9, "col": 16}]},                                   # Berlin-Tempelhof
        {"id": "government_quarter_held", "type": "hexesHeld",
         "hexes": [{"row": 12, "col": 18}, {"row": 13, "col": 16}, {"row": 15, "col": 13}]},
        {"id": "outskirts_held", "type": "hexesHeld", "atLeast": 1,
         "hexes": [{"row": 11, "col": 11}, {"row": 14, "col": 5}]},           # Falkensee / Potsdam
    ],
    # rhu190416 has no victory hexes at all - only the core-loss objectives apply.
    "rhu190424.xml": [
        {"id": "hajduboszormeny_held", "type": "hexesHeld",
         "hexes": [{"row": 24, "col": 8}]},
    ],
    "rhu190501.xml": [
        {"id": "szolnok_bridgehead_held", "type": "hexesHeld", "atLeast": 2,
         "hexes": [{"row": 9, "col": 12}, {"row": 10, "col": 18}, {"row": 11, "col": 31}]},
        {"id": "military_base_captured", "type": "hexesHeld",
         "hexes": [{"row": 22, "col": 21}]},                                  # Szolnok Military Base
    ],
    "rhu190509.xml": [
        {"id": "industrial_north_held", "type": "hexesHeld",
         "hexes": [{"row": 8, "col": 21}, {"row": 8, "col": 32}, {"row": 12, "col": 16}]},
        {"id": "industrial_north_partly_held", "type": "hexesHeld", "atLeast": 1,
         "hexes": [{"row": 8, "col": 21}, {"row": 8, "col": 32}, {"row": 12, "col": 16}]},
    ],
    "rhu190523.xml": [
        {"id": "miskolc_held", "type": "hexesHeld", "hexes": [{"row": 15, "col": 28}]},
        {"id": "industrial_complex_saved", "type": "hexesHeld",
         "hexes": [{"row": 7, "col": 23}, {"row": 8, "col": 11}]},            # Kazincbarcika + Ozd
        {"id": "industrial_complex_lost", "type": "hexesNotHeld",
         "hexes": [{"row": 7, "col": 23}, {"row": 8, "col": 11}]},
    ],
    "rhu190530.xml": [
        {"id": "kassa_held", "type": "hexesHeld", "hexes": [{"row": 10, "col": 35}]},
        {"id": "rozsnyo_held", "type": "hexesHeld", "hexes": [{"row": 14, "col": 6}]},
    ],
    "rhu190609.xml": [
        {"id": "eperjes_held", "type": "hexesHeld", "hexes": [{"row": 18, "col": 33}]},  # Presov
        {"id": "full_northern_advance", "type": "hexesHeld",
         "hexes": [{"row": 2, "col": 34}, {"row": 18, "col": 33}, {"row": 27, "col": 20}]},
    ],
    "rhu190613.xml": [
        {"id": "nyitra_held", "type": "hexesHeld", "hexes": [{"row": 17, "col": 3}]},
        {"id": "mining_towns_held", "type": "hexesHeld",
         "hexes": [{"row": 7, "col": 36}, {"row": 12, "col": 28}]},           # Zolyom + Selmecbanya
    ],
    "rhu190719.xml": [
        {"id": "tisza_advance_deep", "type": "hexesHeld", "atLeast": 4,
         "hexes": [{"row": 1, "col": 26}, {"row": 10, "col": 32}, {"row": 11, "col": 17},
                   {"row": 21, "col": 25}, {"row": 24, "col": 9}]},
        {"id": "tisza_bridgehead_held", "type": "hexesHeld", "atLeast": 2,
         "hexes": [{"row": 1, "col": 26}, {"row": 10, "col": 32}, {"row": 11, "col": 17},
                   {"row": 21, "col": 25}, {"row": 24, "col": 9}]},
    ],
    "rhu190724.xml": [
        {"id": "karcag_held", "type": "hexesHeld", "hexes": [{"row": 5, "col": 38}]},
        {"id": "gyoma_held", "type": "hexesHeld", "hexes": [{"row": 26, "col": 35}]},
    ],
}

# Core-loss objectives apply everywhere; they need no map knowledge.
CORE_ACTIONS = [
    {"id": "core_intact", "type": "coreLossesAtMost", "maxLosses": 0},
    {"id": "core_losses_light", "type": "coreLossesAtMost", "maxLosses": 2},
]

# --------------------------------------------------------------------------- callbacks
# Scenario -> list of conditional lines shown after the outcome reaction. Conditions reference
# the PREVIOUS scenario's recorded facts (qualified `scenario.action`) or persistent flags.
#
# Each entry is (key, conditions, text) or (key, conditions, text, voice_line_id). Without a
# `voice` the callback is spoken by whoever opens the scenario, which is right for a staff report
# and wrong for anything personal: "your decision cost those men their lives" cannot come from a
# Wilhelmshaven council chairman who was never in Kiel. `voice` names another line in the SAME
# scenario whose speaker/role/side/portrait the callback borrows, so a courier who actually
# carried the despatches can deliver it - the documented alternative to teleporting a character
# across the country (see docs/campaign-dialogue-and-consequences.md, "Historical honesty").

CB = collections.OrderedDict([
    ("n_willhelmsh.xml", [
        ("naval-base", {"completedActions": ["n_kiel.xml.naval_base_held"]},
         "Because your column held the Kiel naval base, its crews have been sending us everything "
         "they can spare. Half the rifles against this wall came north on their wagons."),
        ("naval-base-lost", {"failedActions": ["n_kiel.xml.naval_base_held"]},
         "You left Kiel with its naval base slipping back into the officers' hands. Whatever "
         "remained in those magazines will be issued to the men sent after us."),
        # The three prisoner callbacks are mutually exclusive by construction: rescued / broke
        # some out but never reached the yard / took the telegraph and never came at all.
        ("prisoners-freed", {"completedActions": ["n_kiel.xml.prisoners_rescued"]},
         "The men from the Arrestlokal came on the train behind yours. They have not let anyone "
         "forget whose column opened the gate; half the sailors in this room joined after hearing "
         "them tell it.",
         "parkhaus-scene"),
        ("prisoners-breakout-partial",
         {"allFlags": ["sailors_liberated"], "failedActions": ["n_kiel.xml.prisoners_rescued"]},
         "The sailors you pulled through the service gate came north with us, and they have not "
         "sat down since. The rest were still behind Souchon's wall when your train left. Artelt "
         "sends word that the governor may yet release them—and that he is done building plans on "
         "the governor's mercy.",
         "parkhaus-scene"),
        # The historically documented event, and the one thing this line must not overstate: the
        # shooting was into the DEMONSTRATION marching on the prison - Steinhäuser's detachment,
        # eight dead and twenty-nine badly wounded in the Soviet account - not an execution of the
        # detainees in their cells, for which there is no Soviet or GDR source. The reproach is
        # that the column went without support and was broken up, not that the prisoners were shot.
        ("prisoners-abandoned",
         {"allFlags": ["communications_secured"], "failedActions": ["n_kiel.xml.prisoners_rescued"]},
         "I carried your despatches all that night, comrade, and I will carry this one too. The "
         "crowd went down to the Arrestlokal without us. Steinhäuser's men were across the street "
         "with the order already given - eight dead, twenty-nine carried away, and the cells still "
         "locked when the firing stopped. The telegraph worked perfectly. That is what I had to "
         "tell the families, standing in a room where the wire was the only thing that had held.",
         "parkhaus-scene"),
        ("comms", {"allFlags": ["communications_secured"]},
         "Your telegraph gamble paid for the journey north: our operators knew Wilhelmshaven had "
         "risen before the Admiralty did, and your train passed every signal they tried to close."),
    ]),
    ("n_frankfurt.xml", [
        ("region-secured", {"completedActions": ["n_willhelmsh.xml.region_fully_secured"]},
         "Four telegrams, four council seals: Norden, Aurich, Emden, Oldenburg. The railwaymen "
         "carry your despatches free of charge now and leave the government's sacks on the platform."),
        ("region-partial", {"completedActions": ["n_willhelmsh.xml.region_partly_secured"],
                            "failedActions": ["n_willhelmsh.xml.region_fully_secured"]},
         "This handbill says your coastal rising has already been stopped. It was printed in one "
         "of the towns your columns never reached, and the police are passing copies through the "
         "barracks."),
        ("doctrine-rapid", {"allFlags": [RAPID]},
         "Your speed opened every signal north of here. It also brought me formations that have "
         "not slept under a roof since Kiel. Ask them for another forced march and some will make "
         "it; do not pretend all will."),
        ("doctrine-methodical", {"allFlags": [METHODICAL]},
         "Your column arrived fed, armed and in step. The police used those same hours to wire the "
         "telegraph room and promise hot meals in the barracks. We are about to learn which "
         "preparation mattered more."),
    ]),
    ("n_berlin.xml", [
        ("frankfurt-held", {"completedActions": ["n_frankfurt.xml.frankfurt_city_held"]},
         "You left Frankfurt with its city, bridges and station under council guard. The "
         "Rhine-Main delegates arriving behind you carry mandates now, not petitions."),
        ("frankfurt-lost", {"failedActions": ["n_frankfurt.xml.frankfurt_city_held"]},
         "You had to leave Frankfurt before its council closed its hand on the whole city. The "
         "government still has a functioning administration in the west, and it is using it "
         "against us."),
    ]),
    ("rhu190501.xml", [
        ("rearguard-held", {"completedActions": ["rhu190424.xml.hajduboszormeny_held"]},
         "The rear guard held Hajduboszormeny to the last hour. The transport columns crossed the "
         "Tisza intact because of it."),
        ("core-intact", {"completedActions": ["rhu190424.xml.core_intact"]},
         "Not one formation was lost in the withdrawal. The staff has been recalculating what this "
         "army can still be asked to do."),
    ]),
    ("rhu190509.xml", [
        ("bridgehead", {"completedActions": ["rhu190501.xml.szolnok_bridgehead_held"]},
         "Szolnok is held. The Romanians must now force a river crossing under our guns instead of "
         "walking across it."),
        ("base-taken", {"completedActions": ["rhu190501.xml.military_base_captured"]},
         "The military base at Szolnok fell to us with its stores largely intact. The People's "
         "Commissariat has stopped rationing small-arms ammunition."),
        ("doctrine-methodical", {"allFlags": [METHODICAL]},
         "This army has been fighting deliberately, and it still has its equipment. Salgotarjan's "
         "workers are asking whether that caution will extend to them."),
    ]),
    ("rhu190523.xml", [
        ("industry-held", {"completedActions": ["rhu190509.xml.industrial_north_held"]},
         "The northern industrial belt held completely. Salgotarjan is still producing, and what it "
         "produces is reaching the front."),
        ("industry-partial", {"completedActions": ["rhu190509.xml.industrial_north_partly_held"],
                              "failedActions": ["rhu190509.xml.industrial_north_held"]},
         "We saved part of the industrial district. The output is a fraction of what it was, and "
         "the miners know exactly which pits we could not defend."),
        ("workers", {"allFlags": ["workers_armed"]},
         "The armed workers' battalions have held their ground. They are inexperienced and they "
         "know it - but they did not break, and they will not be disarmed quietly."),
    ]),
    ("rhu190530.xml", [
        ("complex-saved", {"completedActions": ["rhu190523.xml.industrial_complex_saved"]},
         "Ozd and Kazincbarcika came through undamaged. The furnaces are lit, the rolling mills are "
         "turning, and the railway repair shops are ours."),
        ("complex-lost", {"completedActions": ["rhu190523.xml.industrial_complex_lost"]},
         "We took Miskolc and lost the works at Ozd and Kazincbarcika doing it. What we need now "
         "cannot be manufactured behind this front."),
        ("miskolc", {"completedActions": ["rhu190523.xml.miskolc_held"]},
         "Miskolc is secure and the northern road is open."),
    ]),
    ("rhu190609.xml", [
        ("kassa", {"completedActions": ["rhu190530.xml.kassa_held"]},
         "Kassa is ours, and the junction with it. Every train the Czechoslovak command wanted to "
         "move south now goes the long way or not at all."),
        ("doctrine-rapid", {"allFlags": [RAPID]},
         "The mobile formations have carried this campaign further than the plan allowed for. They "
         "are also further from their supply than the plan allowed for."),
    ]),
    ("rhu190613.xml", [
        ("eperjes", {"completedActions": ["rhu190609.xml.eperjes_held"]},
         "Eperjes is held. In the town hall, workers' and soldiers' delegates argue over a "
         "proclamation they intend to make within days, while printers wait for the name to place "
         "above it. Paris is demanding our withdrawal before that argument can harden into a government."),
        ("full-advance", {"completedActions": ["rhu190609.xml.full_northern_advance"]},
         "Bartfa, Eperjes, Golnicbanya - the whole northern axis. No staff in Europe expected this "
         "army to be standing here in June."),
    ]),
    ("rhu190719.xml", [
        ("mining-towns", {"completedActions": ["rhu190613.xml.mining_towns_held"]},
         "We held the mining towns to the end and then evacuated them under orders, not under fire. "
         "The men have not forgotten the difference."),
        ("doctrine-methodical", {"allFlags": [METHODICAL]},
         "This army has been handled carefully for three months. It is still a coherent instrument "
         "- which is the only reason an offensive across the Tisza can even be discussed."),
    ]),
    ("rhu190724.xml", [
        ("advance-deep", {"completedActions": ["rhu190719.xml.tisza_advance_deep"]},
         "The offensive reached further than Szolnok, further than the staff plan, further than the "
         "Romanian command believed possible. Whatever happens on this bank, that happened."),
        ("bridgehead-only", {"completedActions": ["rhu190719.xml.tisza_bridgehead_held"],
                             "failedActions": ["rhu190719.xml.tisza_advance_deep"]},
         "We hold a bridgehead and no more. The crossing succeeded; the exploitation did not."),
        ("committed-deep", {"allFlags": ["tisza_committed_deep"]},
         "We committed the reserve deep, as you ordered. There is nothing left behind the line to "
         "commit again."),
    ]),
])

AUTHORED = (CALLBACK_PREFIX,)


def author(campaign_file):
    path = DATA % campaign_file
    data = json.load(io.open(path, encoding="utf-8"),
                     object_pairs_hook=collections.OrderedDict)
    stats = collections.Counter()

    for entry in data:
        scenario = entry["scenario"]
        briefing = entry["briefing"]
        dialogue = list(briefing.get("dialogue", []))

        # ---- objectives: map-based (verified coords) + universal core-loss tiers
        entry["actions"] = OBJECTIVES.get(scenario, []) + CORE_ACTIONS
        stats["objectives"] += len(OBJECTIVES.get(scenario, []))

        # ---- callbacks, inserted after the reaction/extra block, before the opener
        specs = CB.get(scenario, [])
        if not specs:
            briefing["dialogue"] = dialogue
            continue

        # The opener is the first line that this script and its predecessor did not author.
        opener = next(l for l in dialogue
                      if not str(l.get("id", "")).startswith(("reaction-", "extra-", CALLBACK_PREFIX)))
        template = dialogue[0]

        by_id = {str(line.get("id", "")): line for line in dialogue}
        existing_ids = set(by_id)
        missing_callbacks = []
        for spec in specs:
            key, cond, text = spec[0], spec[1], spec[2]
            voice = by_id.get(spec[3], template) if len(spec) > 3 else template
            callback_id = CALLBACK_PREFIX + key
            if callback_id in existing_ids:
                continue
            missing_callbacks.append(collections.OrderedDict([
                ("id", callback_id),
                ("speaker", voice["speaker"]),
                ("role", voice.get("role", "")),
                ("side", voice.get("side", "left")),
                ("portrait", voice.get("portrait")),
                ("text", text),
                ("conditions", cond),
                ("next", opener["id"]),
            ]))
            stats["callbacks"] += 1

        head, tail = [], []
        for line in dialogue:
            (head if str(line.get("id", "")).startswith(("reaction-", "extra-")) else tail).append(line)

        callbacks = [line for line in tail if str(line.get("id", "")).startswith(CALLBACK_PREFIX)]
        callbacks += missing_callbacks
        tail = [line for line in tail if not str(line.get("id", "")).startswith(CALLBACK_PREFIX)]

        # A callback with a false condition is skipped by the dialogue controller via `next`, so
        # one ordered chain handles every combination without duplicating the common scene.
        for i, callback in enumerate(callbacks):
            callback["next"] = callbacks[i + 1]["id"] if i + 1 < len(callbacks) else opener["id"]

        first_cb = callbacks[0]["id"]

        # Reaction/extra lines now flow into the callback chain instead of straight to the opener.
        for line in head:
            if line.get("next") == opener["id"]:
                line["next"] = first_cb

        briefing["dialogue"] = head + callbacks + tail
        stats["scenarios"] += 1

    io.open(path, "w", encoding="utf-8", newline="\n").write(
        json.dumps(data, ensure_ascii=False, indent=1) + "\n")
    return stats


for name in ("novemberrevolution", "rhu"):
    s = author(name)
    print("%-20s map_objectives=%d callbacks=%d scenarios_with_callbacks=%d" % (
        name, s["objectives"], s["callbacks"], s["scenarios"]))
