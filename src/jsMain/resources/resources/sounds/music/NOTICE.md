# Open General scenario music

These MP3 files are scenario music referenced by Open General scenarios imported into OSADA. They
were copied byte-for-byte from the installed Open General efile `SOUND/` directories; filenames were
normalized to lowercase for a case-sensitive web server, but the audio content was not modified.

Open General's `README/read_me_first.html` states that media files belong to their respective
designers and require the particular owners' written permission for redistribution. On 2026-09-01,
the OSADA repository owner confirmed holding or having obtained the required permission to
redistribute this scenario-music set with OSADA. Copyright in each audio file remains with its
respective owner.

`manifest.json` lists only browser-playable referenced tracks present in that distribution. Missing
source tracks and unsupported `.MUS` files are omitted and remain silent without a network request.

## One deliberate substitution

`africa2.mp3` is named by `battle_cuito` and `vkampala_sfb`, both built on `eqp-olgcw`. `EFILE_OLGCW`
ships no `SOUND` directory at all and the install's root `SOUND` has no file of that name, so Open
General itself plays nothing for either scenario. Two other efiles do ship a file by that name and
they are **not the same recording** (`EFILE_CC74`, 2.7 MB; `EFILE_KAISER`, 1.4 MB). The CC74 copy is
shipped here as an explicit editorial choice, recorded in `MUSIC_SUBSTITUTIONS` in
`tools/og-import/deploy_sounds.py`, which prints the substitution on every run. Before that table
existed the deployer resolved track names across all efiles at once and picked alphabetically, so
this was the same file arriving by accident.
