# Open General scenario music

These MP3 files are scenario music referenced by Open General scenarios imported into OSADA. They
were copied byte-for-byte from the installed Open General efile `SOUND/` directories; filenames were
normalized to lowercase for a case-sensitive web server, but the audio content was not modified.

`campsimpob.mp3` is deliberately not shipped or listed in `manifest.json`: its political character
is uncertain and the repository owner disabled it on 2026-09-17. The `music="campsimpob.mp3"`
attributes remain in the scenario XML as inert examples of the mechanism.

## Adding replacement music

Use a new, unambiguous filename rather than reusing an excluded source track. Put a browser-playable
`.mp3`, `.ogg`, `.m4a` or `.wav` file in this directory, set the scenario `<map music="...">`
attribute to that filename, and add the lower-case filename to `manifest.json`. The manifest is the
scenario allow-list: an authored scenario never requests a file not listed there. The personal player
has its separate recording catalog below. For an imported OG track,
`python tools/og-import/deploy_sounds.py --write --include-music` performs the copying and manifest
generation; `EXCLUDED_BUNDLED_MUSIC` records tracks that command must leave disabled.

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


## Personal player catalog, updated 2026-10-05

`catalog.json` schema 1 / content version 5 contains 107 prepared personal-player recordings
from the owner's proposed catalog, plus OG African Drums: 108 musical recordings in
17 collections. Albums share recording IDs/assets where appropriate; the OG collection remains separate.
The opening recording is Popov's **Night**, supplied by the owner. **Seryozha's and Valya's
Farewell** remains deferred to the owner and has no playable placeholder.

The original downloads and owner-provided master are preserved outside runtime resources in
`C:/dev/osada-music-staging/masters/`. `tools/music/source-plan.json` pins the chosen versions
and excerpt boundaries; `tools/music/prepare_catalog.py` generates both variants directly
from masters. `provenance.json` records source URLs (where known), source uploader, master
and output SHA-256, duration, measured LUFS/true peak and applied static gain. Unknown
performers and dates are omitted; a composer is never substituted for a performer.
The owner-supplied Night master has no supplied source URL or performer credit.

Delivery uses Opus VBR at 64 kbit/s for archive mono, 80 kbit/s for ordinary stereo, and
96 kbit/s for orchestral stereo, with MP3 96/128 kbit/s mono/stereo fallback. Original stereo
is preserved for Night. Static level matching targets -20 LUFS with at most +3 dB gain and
measured source peak headroom of -3 dBFS; useful dynamics are retained. No denoising,
dynamic compression or synthetic stereo was introduced. Full decoding of every output is
verified during preparation. Listening comparisons under game effects and actual Apple
hardware validation remain separate from these automated checks.

The long Civil War compilation was downloaded once. Excerpts follow the uploader's chapters
and timestamped track list. Its Red Army chapter contains two performances: the selected
first performance ends at the intervening silence, 1519.6 seconds, rather than including both
as one recording. Its individual performer is omitted because the combined chapter credits
do not identify the versions separately.

Two source corrections are explicit: the unavailable Yugoslav partisan link was replaced by
City Choir Bijelo Polje's performance; the supplied Moorsoldaten link was an excerpt, so the
full Ernst Busch and chorus recording was selected. Some selected versions are modern
performances of historical songs, not wartime recordings. The Adriano recording metadata
identifies **Moscow Philharmonic Orchestra**, correcting the proposed Moscow Symphony credit.
Complete version identities are recorded in `provenance.json`.

All collection sleeves are original generic SVG artwork created for OSADA. No third-party
album art or HOI4 artwork is used. Existing OG redistribution permission above concerns the
OG subset; source/provenance entries are not additional license grants for new recordings.
The personal catalog does not alter the legacy scenario allow-list or its deliberate exclusion.

The 2026-10-05 additions contain 35 recordings: Korean War / DPRK (8), Cuban Revolution (9),
Chinese Revolution (9), and Vietnam / Revolutionary Songs (9). Each has Opus VBR 64–80 kbit/s
and an MP3 fallback, prepared directly from its staged master using the same static gain method.
The previous 143 music variants are unchanged byte-for-byte. Names have EN/RU translations
and original-language titles. Unknown recording years/performers remain omitted; remaster or
digital release dates are not presented as dates of the original recording.

On 2026-10-05 the owner answered **yes** to holding redistribution permission for these new
recordings. Each new provenance entry records that owner confirmation separately from the source
license metadata. No worldwide public-domain conclusion is inferred from the age or subject of
a song. Copyright remains with the respective owners. Uploads that state a copyright/no-reupload
notice retain that source status; OSADA's redistribution basis is the owner's confirmed permission.

The Pathé 1935 March of the Volunteers master is the original Ogg **Vorbis** file from Wikimedia
Commons, performed by the Diantong Company singing team (電通公司歌唱隊). The source page is
[Commons revision 1209359289](https://commons.wikimedia.org/w/index.php?title=File:March_of_the_Volunteers_(Pathe_Records_-_1935).ogg&oldid=1209359289).
Its PD-China label and the US pre-1972-recording protection caveat are retained in provenance;
this recording is not described as worldwide public domain. The delivered Opus and MP3 are
conversions of that master, not of the Commons MP3 transcode.

Two Korean uploads explicitly supply Creative Commons Attribution (reuse allowed) metadata:
[Song of General Kim Il Sung](https://www.youtube.com/watch?v=pwE10Uo_kgs), uploader DPRKVideoArchive,
and [Song of Coast Artillerymen](https://www.youtube.com/watch?v=Bmzsmvc2Gtc), uploader Music of
the Korean People's Army. Source identities and uploader credits are in `provenance.json`.
OSADA applies only the documented static gain and format conversion; no composer is substituted
for an unidentified performer. The Chinese Internationale uses a different Mandarin-language
recording from the Russian one, with the shared work identity `internationale`.

| Existing OG file | Embedded ID3 title / credit | SHA-256 |
|---|---|---|
| `africa2.mp3` | African Drums; performer not supplied | `db01e01efe74a863543ea583e829fb0ea7162cebf102a20ae13bfbb3b2b7d0f6` |
| `amb_field.mp3` | Ambience / Park | `43fe3b6ee5df51dfb9f3a8e06bd6508e7e42f9fb698cf221bcaa9db4e300d322` |

2026-10-04: `amb_field.mp3` (Park) is authored environment audio, controlled by environment volume, superseded by active rain/snow. It stays at its licensed legacy path and in the importer manifest, but is absent from the musical catalog and all presets. The 21 scenario references are preserved. `africa2.mp3` remains music.

Bella Ciao was added on 2026-10-05 to International Antifascist Resistance from the
owner's exact requested upload: https://www.youtube.com/watch?v=zyh_OVeQZH0 . Its source
is titled "Bella Ciao" - Italian Partisan Song, uploaded by Kamerad Kosmos. The uploader
is not a credited performer; performer and recording year remain unknown. No reuse license
is stated by the upload. Redistribution relies on the owner's existing confirmed permission
for the requested historical songs, recorded separately in source-plan/provenance metadata.
Both variants were encoded directly from the staged WebM master; all previous 213 codec
variants are unchanged byte-for-byte.
