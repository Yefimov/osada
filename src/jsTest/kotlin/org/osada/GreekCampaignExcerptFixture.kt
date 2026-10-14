package org.osada

// An excerpt of resources/campaigns/data/camp6bn4.json (Greek campaign) as shipped on 2026-10-14:
// every record's id, scenario and outcome gotos; the briefings of bn4s00, bn4s03, bn4s10 and bn4s11
// with their line ids, links, conditions, choices and effects intact and their text shortened.
// Karma cannot serve campaign resources, so regenerate this from the JSON if those records change.
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
internal const val GREEK_CAMPAIGN_EXCERPT_JSON =
    "[{\"id\":0,\"scenario\":\"bn4s00.xml\",\"outcome\":{\"briliant\":{\"goto\":1,\"prestige\":450},\"victory\":{\"goto\":1" +
        ",\"prestige\":400},\"tactical\":{\"goto\":1,\"prestige\":350},\"lose\":{\"goto\":255,\"prestige\":0}},\"briefing\":{" +
        "\"dialogue\":[{\"id\":\"scene-0\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"next\":\"scene-1\",\"text\":\"Captai" +
        "n, that mule has t\"},{\"id\":\"scene-1\",\"speaker\":\"Konstantinos Davakis\",\"side\":\"left\",\"next\":\"scene-2\"" +
        ",\"text\":\"Then stop calling it a p\"},{\"id\":\"scene-2\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"ne" +
        "xt\":\"decision\",\"text\":\"Italian patrols have fou\"},{\"id\":\"decision\",\"speaker\":\"Konstantinos Davakis\"," +
        "\"side\":\"left\",\"text\":\"Commander, do I send tha\",\"choices\":[{\"id\":\"hold-the-ridge-line\",\"text\":\"hold-" +
        "the-ridge-line\",\"effects\":[{\"id\":\"bn4s00.choice.hold-the-ridge-line.flag\",\"type\":\"setFlag\",\"flag\":\"g" +
        "reece_pindus_hold_the_ridge_line\"},{\"type\":\"experience\",\"amount\":10,\"unitClass\":1,\"id\":\"bn4s00.choic" +
        "e.hold-the-ridge-line.ridge-infantry\"},{\"type\":\"experience\",\"amount\":10,\"unitClass\":8,\"id\":\"bn4s00.c" +
        "hoice.hold-the-ridge-line.ridge-artillery\"},{\"type\":\"resupply\",\"refuel\":false,\"rearm\":true,\"id\":\"bn4" +
        "s00.choice.hold-the-ridge-line.ridge-ammunition\"}],\"next\":\"choice-a-0\"},{\"id\":\"keep-a-mobile-reserve" +
        "\",\"text\":\"keep-a-mobile-reserve\",\"effects\":[{\"id\":\"bn4s00.choice.keep-a-mobile-reserve.flag\",\"type\":" +
        "\"setFlag\",\"flag\":\"greece_pindus_keep_a_mobile_reserve\"},{\"type\":\"experience\",\"amount\":15,\"unitClass\"" +
        ":3,\"id\":\"bn4s00.choice.keep-a-mobile-reserve.reserve-recon\"},{\"type\":\"resupply\",\"refuel\":true,\"rearm" +
        "\":false,\"id\":\"bn4s00.choice.keep-a-mobile-reserve.reserve-fuel\"}],\"next\":\"choice-b-0\"}]},{\"id\":\"choi" +
        "ce-a-0\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"allFlags\":[\"greece_pindus_hold_the_r" +
        "idge_line\"]},\"next\":\"choice-a-1\",\"text\":\"So there is no reserve. \"},{\"id\":\"choice-a-1\",\"speaker\":\"Ko" +
        "nstantinos Davakis\",\"side\":\"left\",\"conditions\":{\"allFlags\":[\"greece_pindus_hold_the_ridge_line\"]},\"t" +
        "ext\":\"They will know. Vovousa \"},{\"id\":\"choice-b-0\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"co" +
        "nditions\":{\"allFlags\":[\"greece_pindus_keep_a_mobile_reserve\"]},\"next\":\"choice-b-1\",\"text\":\"A light l" +
        "ine may bend be\"},{\"id\":\"choice-b-1\",\"speaker\":\"Konstantinos Davakis\",\"side\":\"left\",\"conditions\":{\"a" +
        "llFlags\":[\"greece_pindus_keep_a_mobile_reserve\"]},\"text\":\"Mark them. The battalion\"}]}},{\"id\":1,\"sce" +
        "nario\":\"bn4s01.xml\",\"outcome\":{\"briliant\":{\"goto\":2,\"prestige\":450},\"victory\":{\"goto\":2,\"prestige\":4" +
        "00},\"tactical\":{\"goto\":2,\"prestige\":350},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":2,\"scenario\":\"bn4s" +
        "02.xml\",\"outcome\":{\"briliant\":{\"goto\":3,\"prestige\":500},\"victory\":{\"goto\":3,\"prestige\":450},\"tactica" +
        "l\":{\"goto\":3,\"prestige\":400},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":3,\"scenario\":\"bn4s03.xml\",\"out" +
        "come\":{\"briliant\":{\"goto\":7,\"prestige\":500},\"victory\":{\"goto\":7,\"prestige\":450},\"tactical\":{\"goto\":7" +
        ",\"prestige\":400},\"lose\":{\"goto\":255,\"prestige\":0}},\"briefing\":{\"dialogue\":[{\"id\":\"reaction-2-0-brili" +
        "ant\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"conditions\":{\"previousOutcome\":[\"briliant\"]},\"nex" +
        "t\":\"callback-0\",\"text\":\"Klisura and its pass fel\"},{\"id\":\"reaction-2-1-victory-tactical\",\"speaker\":\"" +
        "Petros Manesis\",\"side\":\"left\",\"conditions\":{\"previousOutcome\":[\"victory\",\"tactical\"]},\"next\":\"callba" +
        "ck-0\",\"text\":\"The supply drivers can g\"},{\"id\":\"callback-0\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"ri" +
        "ght\",\"conditions\":{\"allFlags\":[\"greece_klisura_break_the_pass\"]},\"next\":\"callback-1\",\"text\":\"Every a" +
        "rtillery officer \"},{\"id\":\"callback-1\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"allFl" +
        "ags\":[\"greece_klisura_open_the_side_roads\"]},\"next\":\"pindus-ridge-memory\",\"text\":\"The Ballaban men r" +
        "emembe\"},{\"id\":\"pindus-ridge-memory\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"conditions\":{\"all" +
        "Flags\":[\"greece_pindus_hold_the_ridge_line\"]},\"next\":\"pindus-reserve-memory\",\"text\":\"I thought the P" +
        "indus nee\"},{\"id\":\"pindus-reserve-memory\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"al" +
        "lFlags\":[\"greece_pindus_keep_a_mobile_reserve\"]},\"next\":\"scene-0\",\"text\":\"The battalion you kept b\"}" +
        ",{\"id\":\"scene-0\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"next\":\"scene-1\",\"text\":\"The wire to Heigh" +
        "t 731 h\"},{\"id\":\"scene-1\",\"speaker\":\"Alexandros Papagos\",\"side\":\"left\",\"next\":\"scene-2\",\"text\":\"Keep" +
        " it. In a week we ma\"},{\"id\":\"scene-2\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"next\":\"decision" +
        "\",\"text\":\"German columns are enter\"},{\"id\":\"decision\",\"speaker\":\"Alexandros Papagos\",\"side\":\"left\",\"" +
        "text\":\"You have held this front\",\"choices\":[{\"id\":\"bn4c01-choice.0\",\"text\":\"bn4c01-choice.0\",\"effect" +
        "s\":[{\"type\":\"route\",\"scenarioIndex\":7,\"id\":\"bn4c01-choice.0.route\"},{\"id\":\"bn4c01-choice.0.flag\",\"ty" +
        "pe\":\"setFlag\",\"flag\":\"camp6bn4_bn4c01_choice_0\"}],\"next\":\"choice-a-0\"},{\"id\":\"bn4c01-choice.1\",\"text" +
        "\":\"bn4c01-choice.1\",\"effects\":[{\"type\":\"route\",\"scenarioIndex\":5,\"id\":\"bn4c01-choice.1.route\"},{\"id\"" +
        ":\"bn4c01-choice.1.flag\",\"type\":\"setFlag\",\"flag\":\"camp6bn4_bn4c01_choice_1\"}],\"next\":\"choice-b-0\"}]}," +
        "{\"id\":\"choice-a-0\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"camp6bn4_" +
        "bn4c01_choice_0\"]},\"next\":\"choice-a-1\",\"text\":\"Western Macedonia leaves\"},{\"id\":\"choice-a-1\",\"speake" +
        "r\":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"allFlags\":[\"camp6bn4_bn4c01_choice_0\"]},\"next\":\"cho" +
        "ice-a-2\",\"text\":\"And I recommend we stop \"},{\"id\":\"choice-a-2\",\"speaker\":\"Alexandros Papagos\",\"side\"" +
        ":\"left\",\"conditions\":{\"allFlags\":[\"camp6bn4_bn4c01_choice_0\"]},\"text\":\"Western Macedonia, then.\"},{\"" +
        "id\":\"choice-b-0\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"allFlags\":[\"camp6bn4_bn4c01" +
        "_choice_1\"]},\"next\":\"choice-b-1\",\"text\":\"I'll tell the western ba\"},{\"id\":\"choice-b-1\",\"speaker\":\"Di" +
        "mitris Laskaris\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"camp6bn4_bn4c01_choice_1\"]},\"text\":\"They " +
        "may. Thessaloniki k\"}]}},{\"id\":4,\"scenario\":\"bn4c01.xml\",\"outcome\":{\"briliant\":{\"goto\":5,\"prestige\":" +
        "0},\"victory\":{\"goto\":5,\"prestige\":0},\"tactical\":{\"goto\":5,\"prestige\":0},\"lose\":{\"goto\":255,\"prestige" +
        "\":0}}},{\"id\":5,\"scenario\":\"bn4s04.xml\",\"outcome\":{\"briliant\":{\"goto\":6,\"prestige\":250},\"victory\":{\"g" +
        "oto\":6,\"prestige\":225},\"tactical\":{\"goto\":6,\"prestige\":200},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\"" +
        ":6,\"scenario\":\"bn4s05.xml\",\"outcome\":{\"briliant\":{\"goto\":9,\"prestige\":325},\"victory\":{\"goto\":9,\"pres" +
        "tige\":300},\"tactical\":{\"goto\":9,\"prestige\":275},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":7,\"scenario" +
        "\":\"bn4s06.xml\",\"outcome\":{\"briliant\":{\"goto\":8,\"prestige\":250},\"victory\":{\"goto\":8,\"prestige\":225},\"" +
        "tactical\":{\"goto\":8,\"prestige\":200},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":8,\"scenario\":\"bn4s07.xm" +
        "l\",\"outcome\":{\"briliant\":{\"goto\":9,\"prestige\":325},\"victory\":{\"goto\":9,\"prestige\":300},\"tactical\":{\"" +
        "goto\":9,\"prestige\":275},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":9,\"scenario\":\"bn4s08.xml\",\"outcome\"" +
        ":{\"briliant\":{\"goto\":10,\"prestige\":350},\"victory\":{\"goto\":10,\"prestige\":300},\"tactical\":{\"goto\":10,\"" +
        "prestige\":250},\"lose\":{\"goto\":10,\"prestige\":225}}},{\"id\":10,\"scenario\":\"bn4s09.xml\",\"outcome\":{\"bril" +
        "iant\":{\"goto\":12,\"prestige\":400},\"victory\":{\"goto\":11,\"prestige\":350},\"tactical\":{\"goto\":11,\"prestig" +
        "e\":300},\"lose\":{\"goto\":11,\"prestige\":225}}},{\"id\":11,\"scenario\":\"bn4s10.xml\",\"outcome\":{\"briliant\":{" +
        "\"goto\":12,\"prestige\":550},\"victory\":{\"goto\":12,\"prestige\":500},\"tactical\":{\"goto\":12,\"prestige\":450}" +
        ",\"lose\":{\"goto\":255,\"prestige\":0}},\"briefing\":{\"dialogue\":[{\"id\":\"reaction-10-0-briliant\",\"speaker\":" +
        "\"Major Edward Hale\",\"side\":\"right\",\"conditions\":{\"previousOutcome\":[\"briliant\"]},\"next\":\"callback-0\"" +
        ",\"text\":\"The airborne lodgement f\"},{\"id\":\"reaction-10-1-victory-tactical\",\"speaker\":\"Eleni Markou\"," +
        "\"side\":\"right\",\"conditions\":{\"previousOutcome\":[\"victory\",\"tactical\"]},\"next\":\"callback-0\",\"text\":\"E" +
        "nough of western Crete \"},{\"id\":\"reaction-10-2-lose\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"condi" +
        "tions\":{\"previousOutcome\":[\"lose\"]},\"next\":\"callback-0\",\"text\":\"The Cretan positions col\"},{\"id\":\"ca" +
        "llback-0\",\"speaker\":\"Major Edward Hale\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"greece_crete_invas" +
        "ion_hold_hill_107\"]},\"next\":\"callback-1\",\"text\":\"You committed early to H\"},{\"id\":\"callback-1\",\"spea" +
        "ker\":\"Eleni Markou\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"greece_crete_invasion_guard_the_docks\"" +
        "]},\"next\":\"callback-2\",\"text\":\"You kept the Cretan rese\"},{\"id\":\"callback-2\",\"speaker\":\"Major Edward" +
        " Hale\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"greece_crete_invasion_keep_the_aamg\"]},\"next\":\"call" +
        "back-3\",\"text\":\"The Thermopylae anti-air\"},{\"id\":\"callback-3\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"" +
        "right\",\"conditions\":{\"allFlags\":[\"greece_crete_invasion_distribute_the_gunners\"]},\"next\":\"scene-0\",\"" +
        "text\":\"The Thermopylae section \"},{\"id\":\"scene-0\",\"speaker\":\"Major Edward Hale\",\"side\":\"right\",\"next" +
        "\":\"scene-1\",\"text\":\"The naval signal promise\"},{\"id\":\"scene-1\",\"speaker\":\"Eleni Markou\",\"side\":\"righ" +
        "t\",\"next\":\"scene-2\",\"text\":\"The field hospital reach\"},{\"id\":\"scene-2\",\"speaker\":\"Petros Manesis\",\"s" +
        "ide\":\"left\",\"next\":\"decision\",\"text\":\"The formed column can ma\"},{\"id\":\"decision\",\"speaker\":\"Petros " +
        "Manesis\",\"side\":\"left\",\"text\":\"Commander: meet the flee\",\"choices\":[{\"id\":\"follow-the-naval-clock\",\"" +
        "text\":\"follow-the-naval-clock\",\"effects\":[{\"id\":\"bn4s10.choice.follow-the-naval-clock.flag\",\"type\":\"" +
        "setFlag\",\"flag\":\"greece_crete_evacuation_follow_the_naval_clock\"},{\"type\":\"resupply\",\"refuel\":true,\"" +
        "rearm\":true,\"id\":\"bn4s10.choice.follow-the-naval-clock.naval-column-supply\"},{\"type\":\"experience\",\"a" +
        "mount\":15,\"unitClass\":7,\"id\":\"bn4s10.choice.follow-the-naval-clock.naval-transport\"},{\"type\":\"experi" +
        "ence\",\"amount\":15,\"unitClass\":3,\"id\":\"bn4s10.choice.follow-the-naval-clock.naval-recon\"}],\"next\":\"ch" +
        "oice-a-0\"},{\"id\":\"bring-out-the-hospital\",\"text\":\"bring-out-the-hospital\",\"effects\":[{\"id\":\"bn4s10.c" +
        "hoice.bring-out-the-hospital.flag\",\"type\":\"setFlag\",\"flag\":\"greece_crete_evacuation_bring_out_the_ho" +
        "spital\"},{\"type\":\"prestige\",\"amount\":-75,\"id\":\"bn4s10.choice.bring-out-the-hospital.sougia-hospital\"" +
        "},{\"type\":\"resupply\",\"refuel\":true,\"rearm\":false,\"strength\":9,\"id\":\"bn4s10.choice.bring-out-the-hosp" +
        "ital.hospital-column\"}],\"next\":\"choice-b-0\"}]},{\"id\":\"choice-a-0\",\"speaker\":\"Eleni Markou\",\"side\":\"r" +
        "ight\",\"conditions\":{\"allFlags\":[\"greece_crete_evacuation_follow_the_naval_clock\"]},\"next\":\"choice-a-" +
        "1\",\"text\":\"Let me have the rear-gua\"},{\"id\":\"choice-a-1\",\"speaker\":\"Major Edward Hale\",\"side\":\"right" +
        "\",\"conditions\":{\"allFlags\":[\"greece_crete_evacuation_follow_the_naval_clock\"]},\"text\":\"Each unit get" +
        "s a beach a\"},{\"id\":\"choice-b-0\",\"speaker\":\"Major Edward Hale\",\"side\":\"right\",\"conditions\":{\"allFlag" +
        "s\":[\"greece_crete_evacuation_bring_out_the_hospital\"]},\"next\":\"choice-b-1\",\"text\":\"One more daylight" +
        " may co\"},{\"id\":\"choice-b-1\",\"speaker\":\"Eleni Markou\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"gree" +
        "ce_crete_evacuation_bring_out_the_hospital\"]},\"text\":\"The hospital starts now.\"}]}},{\"id\":12,\"scenar" +
        "io\":\"bn4s11.xml\",\"outcome\":{\"briliant\":{\"goto\":14,\"prestige\":550},\"victory\":{\"goto\":14,\"prestige\":45" +
        "0},\"tactical\":{\"goto\":14,\"prestige\":400},\"lose\":{\"goto\":255,\"prestige\":0}},\"briefing\":{\"dialogue\":[{" +
        "\"id\":\"reaction-9-0-briliant\",\"speaker\":\"Major Edward Hale\",\"side\":\"right\",\"conditions\":{\"previousOut" +
        "come\":[\"briliant\"],\"previousScenario\":[\"bn4s08.xml\"]},\"next\":\"callback-0\",\"text\":\"The line held thro" +
        "ugh ev\"},{\"id\":\"reaction-9-1-victory-tactical\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"conditi" +
        "ons\":{\"previousOutcome\":[\"victory\",\"tactical\"],\"previousScenario\":[\"bn4s08.xml\"]},\"next\":\"callback-0" +
        "\",\"text\":\"The required part of the\"},{\"id\":\"reaction-9-2-lose\",\"speaker\":\"Petros Manesis\",\"side\":\"le" +
        "ft\",\"conditions\":{\"previousOutcome\":[\"lose\"],\"previousScenario\":[\"bn4s08.xml\"]},\"next\":\"callback-0\"," +
        "\"text\":\"Thermopylae broke before\"},{\"id\":\"reaction-11-0-briliant\",\"speaker\":\"Major Edward Hale\",\"sid" +
        "e\":\"right\",\"conditions\":{\"previousOutcome\":[\"briliant\"],\"previousScenario\":[\"bn4s10.xml\"]},\"next\":\"c" +
        "allback-0\",\"text\":\"The units reached the ex\"},{\"id\":\"reaction-11-1-victory-tactical\",\"speaker\":\"Elen" +
        "i Markou\",\"side\":\"right\",\"conditions\":{\"previousOutcome\":[\"victory\",\"tactical\"],\"previousScenario\":[" +
        "\"bn4s10.xml\"]},\"next\":\"callback-0\",\"text\":\"Twelve formations came o\"},{\"id\":\"reaction-11-2-lose\",\"sp" +
        "eaker\":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"previousOutcome\":[\"lose\"],\"previousScenario\":[\"" +
        "bn4s10.xml\"]},\"next\":\"callback-0\",\"text\":\"We didn't get enough for\"},{\"id\":\"callback-0\",\"speaker\":\"M" +
        "ajor Edward Hale\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"greece_thermopylae_defend_the_broad_line" +
        "\"]},\"next\":\"callback-1\",\"text\":\"The broad line kept forc\"},{\"id\":\"callback-1\",\"speaker\":\"Petros Mane" +
        "sis\",\"side\":\"left\",\"conditions\":{\"allFlags\":[\"greece_thermopylae_hold_three_hinges\"]},\"next\":\"callba" +
        "ck-2\",\"text\":\"Brallos, Amfiklia, Levad\"},{\"id\":\"callback-2\",\"speaker\":\"Major Edward Hale\",\"side\":\"ri" +
        "ght\",\"conditions\":{\"allFlags\":[\"greece_crete_evacuation_follow_the_naval_clock\"]},\"next\":\"callback-3" +
        "\",\"text\":\"The units we gave loadin\"},{\"id\":\"callback-3\",\"speaker\":\"Eleni Markou\",\"side\":\"right\",\"con" +
        "ditions\":{\"allFlags\":[\"greece_crete_evacuation_bring_out_the_hospital\"]},\"next\":\"earned-decision\",\"t" +
        "ext\":\"You held Sougia for the \"},{\"id\":\"earned-decision\",\"speaker\":\"Major Edward Hale\",\"side\":\"right" +
        "\",\"conditions\":{\"completedActions\":[\"bn4s10.xml.finished_by_6\",\"bn4s10.xml.sougia_held\",\"bn4s10.xml." +
        "core_losses_light\"]},\"text\":\"Six carriers came out th\",\"choices\":[{\"id\":\"keep-the-crete-carriers\",\"t" +
        "ext\":\"keep-the-crete-carriers\",\"effects\":[{\"id\":\"bn4s11.choice.keep-the-crete-carriers.flag\",\"type\":" +
        "\"setFlag\",\"flag\":\"greece_el_alamein_keep_the_crete_carriers\"},{\"type\":\"grantUnit\",\"eqid\":30514,\"expe" +
        "rience\":60,\"strength\":7,\"id\":\"bn4s11.choice.keep-the-crete-carriers.crete-carriers\"}],\"next\":\"earned" +
        "-a-response\"},{\"id\":\"share-the-evacuation-pool\",\"text\":\"share-the-evacuation-pool\",\"effects\":[{\"id\":" +
        "\"bn4s11.choice.share-the-evacuation-pool.flag\",\"type\":\"setFlag\",\"flag\":\"greece_el_alamein_share_the_" +
        "evacuation_pool\"},{\"type\":\"experience\",\"amount\":5,\"id\":\"bn4s11.choice.share-the-evacuation-pool.evac" +
        "uation-veterans\"},{\"type\":\"resupply\",\"refuel\":true,\"rearm\":false,\"id\":\"bn4s11.choice.share-the-evacu" +
        "ation-pool.evacuation-fuel\"}],\"next\":\"earned-b-response\"}]},{\"id\":\"earned-a-response\",\"speaker\":\"Maj" +
        "or Edward Hale\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"greece_el_alamein_keep_the_crete_carriers\"" +
        "]},\"next\":\"earned-b-response\",\"text\":\"It does. Keep the driver\"},{\"id\":\"earned-b-response\",\"speaker\"" +
        ":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"allFlags\":[\"greece_el_alamein_share_the_evacuation_po" +
        "ol\"]},\"next\":\"scene-0\",\"text\":\"No separate section. Eac\"},{\"id\":\"scene-0\",\"speaker\":\"Major Edward Ha" +
        "le\",\"side\":\"right\",\"next\":\"scene-1\",\"text\":\"The barrage begins at 21\"},{\"id\":\"scene-1\",\"speaker\":\"Di" +
        "mitris Laskaris\",\"side\":\"right\",\"next\":\"scene-2\",\"text\":\"And British lorries, fue\"},{\"id\":\"scene-2\"," +
        "\"speaker\":\"Eleni Markou\",\"side\":\"right\",\"next\":\"scene-3\",\"text\":\"This came through three \"},{\"id\":\"s" +
        "cene-3\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"next\":\"decision\",\"text\":\"The men ask a shorter qu\"" +
        "},{\"id\":\"decision\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"text\":\"Before the barrage, I ne\",\"c" +
        "hoices\":[{\"id\":\"bn4c02-choice.0\",\"text\":\"bn4c02-choice.0\",\"effects\":[{\"type\":\"route\",\"scenarioIndex\"" +
        ":14,\"id\":\"bn4c02-choice.0.route\"},{\"id\":\"bn4c02-choice.0.flag\",\"type\":\"setFlag\",\"flag\":\"camp6bn4_bn4" +
        "c02_choice_0\"}],\"next\":\"choice-a-0\"},{\"id\":\"bn4c02-choice.1\",\"text\":\"bn4c02-choice.1\",\"effects\":[{\"t" +
        "ype\":\"route\",\"scenarioIndex\":21,\"id\":\"bn4c02-choice.1.route\"},{\"id\":\"bn4c02-choice.1.flag\",\"type\":\"s" +
        "etFlag\",\"flag\":\"camp6bn4_bn4c02_choice_1\"}],\"next\":\"choice-b-0\"}]},{\"id\":\"choice-a-0\",\"speaker\":\"Ele" +
        "ni Markou\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"camp6bn4_bn4c02_choice_0\"]},\"next\":\"choice-a-1\"" +
        ",\"text\":\"I will not call you a co\"},{\"id\":\"choice-a-1\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\"," +
        "\"conditions\":{\"allFlags\":[\"camp6bn4_bn4c02_choice_0\"]},\"next\":\"choice-a-2\",\"text\":\"I won't. We keep " +
        "the for\"},{\"id\":\"choice-a-2\",\"speaker\":\"Petros Manesis\",\"side\":\"left\",\"conditions\":{\"allFlags\":[\"cam" +
        "p6bn4_bn4c02_choice_0\"]},\"text\":\"Then tell the brigade wh\"},{\"id\":\"choice-b-0\",\"speaker\":\"Major Edwa" +
        "rd Hale\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"camp6bn4_bn4c02_choice_1\"]},\"next\":\"choice-b-1\",\"" +
        "text\":\"London will call it dese\"},{\"id\":\"choice-b-1\",\"speaker\":\"Dimitris Laskaris\",\"side\":\"right\",\"c" +
        "onditions\":{\"allFlags\":[\"camp6bn4_bn4c02_choice_1\"]},\"next\":\"choice-b-2\",\"text\":\"I signed the moveme" +
        "nt or\"},{\"id\":\"choice-b-2\",\"speaker\":\"Eleni Markou\",\"side\":\"right\",\"conditions\":{\"allFlags\":[\"camp6b" +
        "n4_bn4c02_choice_1\"]},\"text\":\"I'll contact the courier\"}]}},{\"id\":13,\"scenario\":\"bn4c02.xml\",\"outcom" +
        "e\":{\"briliant\":{\"goto\":14,\"prestige\":0},\"victory\":{\"goto\":14,\"prestige\":0},\"tactical\":{\"goto\":14,\"pr" +
        "estige\":0},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":14,\"scenario\":\"bn4s12.xml\",\"outcome\":{\"briliant\"" +
        ":{\"goto\":15,\"prestige\":550},\"victory\":{\"goto\":15,\"prestige\":500},\"tactical\":{\"goto\":15,\"prestige\":45" +
        "0},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":15,\"scenario\":\"bn4s13.xml\",\"outcome\":{\"briliant\":{\"goto\"" +
        ":16,\"prestige\":400},\"victory\":{\"goto\":16,\"prestige\":350},\"tactical\":{\"goto\":16,\"prestige\":300},\"lose" +
        "\":{\"goto\":255,\"prestige\":0}}},{\"id\":16,\"scenario\":\"bn4s14.xml\",\"outcome\":{\"briliant\":{\"goto\":17,\"pre" +
        "stige\":550},\"victory\":{\"goto\":17,\"prestige\":450},\"tactical\":{\"goto\":17,\"prestige\":400},\"lose\":{\"goto" +
        "\":255,\"prestige\":0}}},{\"id\":17,\"scenario\":\"bn4s15.xml\",\"outcome\":{\"briliant\":{\"goto\":18,\"prestige\":7" +
        "50},\"victory\":{\"goto\":18,\"prestige\":650},\"tactical\":{\"goto\":18,\"prestige\":550},\"lose\":{\"goto\":255,\"p" +
        "restige\":0}}},{\"id\":18,\"scenario\":\"bn4s16.xml\",\"outcome\":{\"briliant\":{\"goto\":19,\"prestige\":550},\"vic" +
        "tory\":{\"goto\":19,\"prestige\":500},\"tactical\":{\"goto\":19,\"prestige\":400},\"lose\":{\"goto\":255,\"prestige\"" +
        ":0}}},{\"id\":19,\"scenario\":\"bn4s17.xml\",\"outcome\":{\"briliant\":{\"goto\":20,\"prestige\":900},\"victory\":{\"" +
        "goto\":20,\"prestige\":800},\"tactical\":{\"goto\":20,\"prestige\":700},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"" +
        "id\":20,\"scenario\":\"bn4s18.xml\",\"outcome\":{\"briliant\":{\"goto\":254,\"prestige\":0},\"victory\":{\"goto\":254" +
        ",\"prestige\":0},\"tactical\":{\"goto\":254,\"prestige\":0},\"lose\":{\"goto\":254,\"prestige\":0}}},{\"id\":21,\"sce" +
        "nario\":\"bn4s19.xml\",\"outcome\":{\"briliant\":{\"goto\":22,\"prestige\":0},\"victory\":{\"goto\":22,\"prestige\":0" +
        "},\"tactical\":{\"goto\":22,\"prestige\":0},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":22,\"scenario\":\"bn4s20" +
        ".xml\",\"outcome\":{\"briliant\":{\"goto\":23,\"prestige\":350},\"victory\":{\"goto\":23,\"prestige\":300},\"tactica" +
        "l\":{\"goto\":23,\"prestige\":250},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":23,\"scenario\":\"bn4s21.xml\",\"o" +
        "utcome\":{\"briliant\":{\"goto\":24,\"prestige\":650},\"victory\":{\"goto\":24,\"prestige\":600},\"tactical\":{\"got" +
        "o\":24,\"prestige\":550},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":24,\"scenario\":\"bn4s22.xml\",\"outcome\":" +
        "{\"briliant\":{\"goto\":25,\"prestige\":800},\"victory\":{\"goto\":25,\"prestige\":750},\"tactical\":{\"goto\":25,\"p" +
        "restige\":700},\"lose\":{\"goto\":25,\"prestige\":650}}},{\"id\":25,\"scenario\":\"bn4s23.xml\",\"outcome\":{\"brili" +
        "ant\":{\"goto\":26,\"prestige\":650},\"victory\":{\"goto\":26,\"prestige\":600},\"tactical\":{\"goto\":26,\"prestige" +
        "\":550},\"lose\":{\"goto\":255,\"prestige\":0}}},{\"id\":26,\"scenario\":\"bn4s24.xml\",\"outcome\":{\"briliant\":{\"g" +
        "oto\":27,\"prestige\":550},\"victory\":{\"goto\":27,\"prestige\":500},\"tactical\":{\"goto\":27,\"prestige\":450},\"" +
        "lose\":{\"goto\":27,\"prestige\":400}}},{\"id\":27,\"scenario\":\"bn4s25.xml\",\"outcome\":{\"briliant\":{\"goto\":28" +
        ",\"prestige\":450},\"victory\":{\"goto\":28,\"prestige\":400},\"tactical\":{\"goto\":28,\"prestige\":350},\"lose\":{" +
        "\"goto\":255,\"prestige\":0}}},{\"id\":28,\"scenario\":\"bn4s26.xml\",\"outcome\":{\"briliant\":{\"goto\":254,\"prest" +
        "ige\":0},\"victory\":{\"goto\":254,\"prestige\":0},\"tactical\":{\"goto\":254,\"prestige\":0},\"lose\":{\"goto\":254," +
        "\"prestige\":0}}}]"
