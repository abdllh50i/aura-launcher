# 1.13: "عمري" hears its name better and wakes less by mistake

Notes for DEV.md, section "The voice assistant "عمري" (`voice/`)". The owner's request: hear the name better, and above
all stop waking on sounds nobody meant. Everything below was measured, before and after, on a test corpus built on the
PC; the app's own Kotlin code was run over it (`voice-test/kt/Harness2.kt`), so the numbers are the code's, not a copy's.

## The test corpus (`D:\k2501-work\voice-test`, not in the repo)
* Speech: Windows' Naayf and 32 Arabic and 8 English neural voices (edge-tts 7.3.1; 7.2.8 got 403 from the service).
  Nine of them are "owners": Naayf, ar-SA-Hamed, ar-KW-Fahed, ar-SA-Zariyah (tuning) and ar-AE-Hamdan, ar-OM-Abdullah,
  ar-QA-Moaz, ar-BH-Ali, ar-KW-Noura (held out). Each enrolled six times like the owner would (Settings' checks
  rebuilt: one word, a voice, the odd one out asked again), in an idling car, from "عمري" at rate -8..+8% and pitch
  -10..+10 Hz.
* Positives: each owner's other 154 ways of saying it (rate -25..+35%, pitch -20..+20 Hz, plain, exclaimed, asked,
  ±4 dB) in ten conditions: quiet, idle 20 dB, city 15 dB, motorway 10 / 5 / 0 dB(A), music 15 / 10 / 5 dB (with road
  noise under it), road and music together. A cabin's short reverberation and the head unit's 90 Hz - 7 kHz microphone.
* Negatives: per owner, 10 minutes per background (quiet, idle, city, motorway, music), an event every 0.6-3 s: the
  owner's own near words (عمر، عمرو، حمري، سمري، عمرين، أمري، قمري، عمرك، عمره، الشمري، العمري، بحري، صبري ... 45),
  his Gulf phrases and sentences, other voices' near words and speech, English, and car sounds (thumps, double bumps,
  ringing panels, rattles, bangs, doors, the indicator, horns, engine revs, wipers, chimes including AMRI's own, beeps,
  coughs). 7.5 hours in all, far denser than a real drive: false accepts per hour compare versions, they are not what
  the car will see. Apart: phrases with the word in them ("يا عمري") and other people saying "عمري".
* The background models are synthetic (road: brown and pink noise, tyre roar, wind, engine orders; music: two real
  songs, one Gulf, and synthetic songs with a sung line). Nothing was recorded on the unit.

## What changed
* **Two listeners.** `Segmenter(band = true)` also follows the voice's band (500 Hz-3 kHz, `VoiceBand`): a word starts
  when either level rises 10 dB over its floor and lasts while every level that rose for it is still up. The plain
  listener runs beside it (music keeps the band up, so words there came out too long); `WakeDetector` judges both
  listeners' words (one cut of the same word is judged once). Most of the misses in road noise were words never found:
  at 5 dB(A) over the motorway the first difference rose 10 dB over its floor in 44-61% of the tries, the band in nearly
  all. Enrolment and the offline requests keep the plain listener, so the owner's recordings were cut as before.
* **A verifier on candidates** (`WakeModel.judge`). Stage 1 is the old score (six alignments, every word). Words under
  the limit then need: a voice (`VoiceCheck`, unchanged); the score again with the recordings seen through the word's
  own background (`spreadThrough`: in loud noise everything looks like the wake word, because the noise hides what tells
  words apart, and the recordings come closer together through it as well); the worst fifth of the alignment
  (`Dtw.path`, `worstPart`); and not "mid-speech" (under 6 dB over its background while that background stood over 4 dB
  above the floor: the background was the previous word). Limits per sensitivity in `Limits`.
* **Learned sounds** (`WakeNegatives`, `amri-wake-neg.bin`). A wake the owner did not mean (the recogniser heard
  nothing, the 8 s / 15 s ran out, he tapped the pill within 6 s, or said "خلاص") is kept if it matched weakly; later
  words much closer to a kept sound than to the recordings are refused. A wake followed by a request forgets the kept
  sounds close to it; 24 at most; Settings → Voice assistant shows the count and forgets them; recording the wake word
  again starts empty.
* Tried and not kept: the loudness contour (c0) in the alignment (no change), fewer cepstra (no change), the end's
  spectral tilt and per-third alignment costs (no separation), the pitch track (TTS near words move like the name),
  a background estimate from before and after the word, a noise margin (2.5x), a logistic mix of the cues (no better
  than plain limits on the held-out voices), a stricter limit under heavy masking (costs road recall), and a "weak"
  test through the background for the learned sounds (real calls in noise were then refused: true accepts 67% → 28%).

## Numbers (Kotlin code over the corpus; all nine owners; normal sensitivity unless said)
| | 1.12 | 1.13 |
|---|---|---|
| true accepts: quiet / idle / city | 97.3 / 97.8 / 81.3% | 96.8 / 97.9 / 81.2% |
| motorway 10 / 5 / 0 dB(A) | 80.1 / 26.2 / 0.5% | 90.8 / 52.7 / 10.2% |
| music 15 / 10 / 5 dB, road + music | 84.1 / 72.1 / 63.8 / 51.9% | 72.4 / 62.3 / 58.7 / 53.4% |
| all conditions | 65.5% | 67.6% |
| false accepts / h (dense streams) | 128.4 | 44.9 |
| by background: quiet / idle / city / motorway / music | 54.7 / 38.7 / 114.0 / 217.3 / 217.3 | 8.7 / 5.3 / 51.3 / 83.3 / 76.0 |
| car sounds / music itself / speech pieces / English | 104 / 55 / 332 / 38 | 23 / 1 / 37 / 5 |
| own near words / others' near words | 315 / 119 | 222 / 49 |
| strict: true accepts, false / h | 61.0%, 106.5 | 61.5%, 30.1 |
| relaxed: true accepts, false / h | 65.9%, 170.1 | 70.8%, 86.7 |
| phrases with the word (road 10) | 42% | 17% |

Held-out owners alone tell the same story. The cost is music: with music 10-15 dB under the voice 10-12% fewer calls
are taken (the noise-aware score is what keeps the songs out). What still gets through is mostly the owner's own near
words said on their own ("عمرين", "عمرك", "عمر", "حمري", "أمري").

Learned sounds, simulated (each stream's first half teaches every false wake, the second half is heard with them):
false accepts 35.5 → 31.5 / h, true accepts 66.9 → 66.2%. The same false wakes heard again later over other sounds:
50% of them woke again without the learned sounds, 30% with them. Ten real "عمري" answered with silence (only the
weak ones, about four, get learned) cost 4 points of true accepts until confirmed wakes forgot them (back to 66.8%).

A pretrained speech embedding as a second stage (openWakeWord's melspectrogram and embedding models, ONNX, few-shot
against the enrolment words, measured on what the 1.13 verifier still lets through): keeping 98% of the true wakes it
removes 74% of the remaining car sounds and 83-86% of the remaining speech pieces, but only 29% of the near words, which
are most of what remains (39% of all). Not shipped: it needs the TFLite runtime plus 2.4 MB of models (over the ~3 MB
budget with the runtime), 42 M multiply-adds per 80 ms window (about 8 windows per candidate) on the weak SoC, and the
owner's recordings as sound (amri-wake.bin keeps spectra), so a new enrolment or recordings gathered from confirmed
wakes. A candidate for a later version if sounds still wake it.

## Requests (`Commands.kt`)
New: what's playing (from what played when it was called, before the pause), how long to go (AMRI Maps' route; another
navigation app does not say), the date (with the Umm al-Qura date in Arabic), brightness (needs "modify system
settings"; asks for it), screen off, the nearest petrol station / pharmacy / restaurant / café / hospital / mosque /
ATM / supermarket / hotel / car wash / garage (AMRI Maps: searched near the car and the nearest said, the map's search
opened; another navigation app: its own search), "ودني <place>", an app by name (Arabic finds Latin labels:
"يوتيوب" → YouTube), a song by name (YouTube Music's search, first song to the YouTube Music app), thanks, who are you,
"الصوت عالي / واطي". Answers that take a moment show their first words, then the answer (12 s at most). 102
phrasings, old and new, parse as meant (`voice-test/kt/cmd/CmdTest.kt`). A word one letter off a key was tried and
dropped ("الماجد" read as "المسجد"); the recogniser's spellings are keys instead.

MapsActivity takes a search query ("q") only in debug builds: in a release build the map's search opens empty. Taking
"q" in release builds (one line in `nav/MapsActivity.kt`, another area) would open it on the place asked for.

## Checked on the emulator (workshop, 1.13.0 debug)
Enrolment from files (spread as on the PC), nine positives woken (quiet, road 5 dB, music 10 dB), 40 s of quiet and
music negatives without a wake, one false wake in 40 s of motorway negatives; every new request through the debug
"say" hook; Settings' new rows. Not checked: the live microphone (the workshop emulator runs without host audio), the
recogniser's real outcomes that teach the learned sounds, the unit's CPU with two listeners, anything on the unit.
Debug hooks: `--es detect a.wav` (both listeners, each word's verdict), `--es learn a.wav`, `--ez forget true`.
