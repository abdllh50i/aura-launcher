# Offline place search: the spec

`search_ref.py` is the reference: where this text and that code differ, the code is right and this text has a bug. The Kotlin side must give the
same answers on `tests/norm_vectors.json` (exact) and `tests/search_tests.json` (same pass or fail per case; `search-golden.json` from the same
build gives ids and scores to compare with). Everything a query needs is in `places.db` or in this file, so the app never drifts from the data:
if `meta.norm_version` is not the one the app knows, the app must not use the file.

## 1. The file

`places.db` is one SQLite file readable by SQLite 3.22 (the firmware's). FTS4 only: no FTS5, no R*Tree. Open it read-only.

```sql
area(id, ar, en, city_ar, city_en)            -- a district and its town; one row per distinct pair
cat(id, key, ar, en, icon)                    -- key is stable, id is not (icon: the colour group, in the order of nav/Poi.kt)
p(id, kind, name, alt, cat, lat, lon, area, phone, rank, src)
    -- kind: 1 region 2 city 3 town 4 village 5 district 6 POI 7 road
    -- name is shown: the Arabic name when there is one. alt: the name in the other script, if any. Nothing guessed is ever in these two.
    -- lat, lon: degrees x 1e6, ids run south to north. rank: 1-100, higher is more important. src: bit 1 Overture, bit 2 OpenStreetMap, bit 4 coarse position
    -- coarse: five or more POIs sit on this very point, which is a geocoder's default and no witness from OpenStreetMap. Found by search, shown with "موقع تقريبي" on its card, never on the map's tiles, never the answer to a dropped pin.
f  fts4(content='', k, a, matchinfo=fts3)     -- docid = p.id. k: everything that finds the row. a: the words of its district and town
spot(kind, lat, lon, dir, kmh, src, j)        -- kind: 1 camera 2 signal 3 bump. dir: degrees the traffic it concerns is heading, null when unknown. src: 1 OSM, 2 the owner's additions
    -- a camera's dir comes from its road, not from OSM's direction tag (where the lens faces, when it is anything): the nearest main road within 25 m if that road is one-way, else null, which means either way.
    -- kmh on a signal or a bump is the limit of the road it is on, not the spot's own. j, signals only: the junction's number. Signal nodes within 60 m are one junction: "الإشارة الثانية" counts j, not rows.
gate(lat, lon, sub, road)                     -- nodes on a drivable road that can stop a car. sub: 1 gate 2 gate marked private or no entry 3 checkpoint 4 border post 5 toll booth 6 concrete block or bollard.
    -- road: the best road through it, 0 motorway 1 trunk 2 primary 3 secondary 4 tertiary 5 unclassified 6 residential 7 living street 8 service. A route through sub 2 or 3 off the main roads may end at a guard: warn before it starts.
zone(id, kind, name, en, s, n, w, e, ring)    -- areas to warn about. kind: 1 the Haram of Makkah 2 the Haram of Madinah (entry is for Muslims only; no road tag says so) 3 military 4 private.
    -- ring: JSON [[lon,lat],...], simplified and grown so that it holds all of OSM's area. s, n, w, e: its box. Kinds 1 and 2 have at most 100 points, which exclude_polygons accepts on the unit (device-config.json). OSM's lines, not a legal text.
    -- Kinds 3 and 4: warn when a route ends inside or leaves the motorway and trunk roads inside; a public highway may cross such a polygon (Jubail-Dammam crosses the naval base's).
syn(w, cats, role, canon)                     -- what query words mean, section 3
meta(k, v)                                    -- schema, norm_version, built, overture, osm_data, additions, attribution
```

`f` is contentless: `SELECT` only `docid` and use `MATCH`. Tokens in `k` that start with `§` are consonant skeletons, with `¶` the same for towns and districts only (kinds up to 5, 3+ letters), with `¤` a category id. They are never shown.
`p.lat` has an index, `spot.lat` too: a box is a `lat BETWEEN` with a `lon` filter.

There are no prefix tables in `f` on purpose. Measured on the same rows: `prefix='2,4'` 65.0 MB, `prefix='4'` 53.8 MB, none 39.4 MB. All the reads of one search on the emulator's SQLite 3.22, 26 queries with a warm file cache: with `2,4` median 5 ms, worst 22 ms; with none median 6 ms, worst 25 ms. With a cold cache: median 9 and 14 ms, worst 120 and 136 ms. A small gain for two thirds more file, because the time goes into reading rows from `p`, not into the index. (FTS4's prefix lengths are bytes: an Arabic letter counts 2.) Not measured on the unit's eMMC.

## 2. Text

**`norm(s)`** gives comparable text. Apply to every query and every shown name before comparing.
1. Lower case (`Locale.ROOT`), then NFKD.
2. Drop: `' ’ ‘ ʼ ʻ` `` ` `` `´`, tatweel, and every character of category Mn, Me or Cf.
3. Fold: `ٱ`→`ا`; `ى ی ې`→`ي`; `ة ۃ ھ ہ ە`→`ه`; `ک ڪ`→`ك`; `ڤ`→`ف`; `گ ڨ`→`ق`; `پ`→`ب`; `چ`→`ج`; `ژ`→`ز`; `ں`→`ن`; `ٹ`→`ت`; `ڈ`→`د`; `ڑ`→`ر`; drop `ء`; Arabic-Indic and Persian digits→ASCII.
4. Letters and digits stay. Anything else is a space. A space also goes in wherever the script changes between Arabic (U+0600-06FF) and anything else: `شارع٢٠` is `شارع 20`.
5. Single spaces, no spaces at the ends.

NFKD already folds `أ إ آ ؤ ئ` to `ا ا ا و ي` and the presentation forms to letters.

**`base(w)`**: first `WORD_CANON`: `بو`→`ابو`, `abou abo`→`abu`, `abdul abdel abdal abdol abdu`→`abd`, `um`→`umm`. Then for an Arabic word: `الله` stays; otherwise take off a leading `ال` if the word has 4+ letters, or `لل` if it has 5+.

**`latin_base(w)`**: `al`/`el` off a Latin word of 5+ letters; `ashsh…`, `adhdh…`, `athth…` lose their first 3 letters and `arr… ass… add… att… ann… azz…` their first 2 if the word has 6+ letters.

**`query_words(text)`**: `norm`, split, `base`, then joined names apart: an Arabic word that starts with `عبد` and has 5+ letters is `عبد` + `base(rest)`; one that starts with `ابو` and has 6+ letters is `ابو` + `base(rest)`; a Latin word matching `^(abdul|abdel|abdal|abdol|abdur|abdus|abdu)(.{3,})$` is `abd` + `latin_base(rest)`. Single Latin letters in a row become one word: `H&M` is `hm`. Used for queries and for the names a result is compared with.

**`skeleton(w)`**, of a word after `base` or `latin_base`, gives the consonants in one alphabet. A word with a digit has none.
- Arabic: `ب`b; `ت ث ط`t; `ج ق ك خ غ`k; `ح ه`h; `د ذ ض ظ ز`d; `ر`r; `س ش ص`s; `ف`f; `ل`l; `م`m; `ن`n; the rest (ا و ي ع) nothing. Doubles are kept in Arabic.
- Latin, left to right: `kh gh ck`k; `sh ch`s; `th`t; `dh`d; `ph`f; `b`b; `d z`d; `f v`f; `g j k q`k; `h`h; `l`l; `m`m; `n`n; `p`b; `r`r; `s`s; `t`t; `c` is s before e, i, y, else k; `x` is k then s; vowels and w, y nothing. The same consonant twice in a row counts once, but a vowel between them keeps both.
- Finally a last `h` goes, in both scripts.

**`skeletons(w)`**: `skeleton` of `base(w)` (Arabic) or `latin_base(w)`, and for a Latin word ending in `at`, also the same without its last `t` (Rawdat, Madinat). Used for a word's own forms; only skeletons of 2+ letters ever count.

**`lev1(a, b)`**: true when a and b are at most one edit apart.

## 3. Words with a meaning: table `syn`

`w` is a normalised word or phrase (`base` on each word). `cats`: category ids, space-separated: the word also means these kinds of place. `role`: 0 none; 1 road word (شارع, طريق, street, rd...); 2 optional word (titles and company words: الملك, king, شركة, فرع, محل, مركز...); 3 stop word (بن, al, the, اقرب, nearest...); 4 district word (حي, district); 5 one thing within a kind (شاورما, كرك, سمسا): required, but a place with the word in its name comes before one that is only of the kind; 6 a word of speech (وين, ابغى, خذني, ابي); 7 a letter's name (كي, اف, سي), `canon` holds the Latin letter; 8 another name of a known place (جسر البحرين), `canon` holds what to search for instead, `|` between several spellings. `canon` otherwise: one spelling for words that mean the same, so شارع answers to طريق, and for roles 0 and 5, شاورمه to شاورما.

Road, optional and district words are never required, a stop word disappears. So "طريق الملك فهد", "شارع الملك فهد" and "King Fahd Rd" all ask for فهد / fahd. But when nothing else is left ("طريق" alone), what is optional is what is asked. Words of speech are optional at either end of a query and plain words in its middle. Roles 7 and 8 count as no entry when the query is read as written.

## 4. The query plan

`plan_query(q)`:
- `typing` is true unless `q` ends in a space.
- `ws = query_words(q)`, then `without_li`: a word of 4+ letters that starts with `ل` and has no `syn` entry, whose rest has one (not role 7 or 8; for role 3 it needs 5+ letters): the rest. "لاقرب", "لصيدلية", "لمول".
- Walk from the left; at each position try the longest phrase of `syn` first (`syn.max_len` words).
  - No entry: a plain group of that one word; it is a prefix if it is the last word and typing.
  - Entry with role 3: dropped.
  - Otherwise a group with the entry's cats and role; it is optional for roles 1, 2, 4; a prefix if one word, last, and typing. Role 1 sets `road_hint`, role 4 `district_hint`. With roles 0 and 5 and a `canon`, `alts`: the other one-word entries of those roles with the same `canon`, in `w` order.
- Role 6 groups before the first or after the last other group are optional, and are `fillers`. Any others become plain groups (role 0).
- Drop a last group that is a single letter being typed (a digit stays).
- `required` = the groups that are not optional; if there are none, all groups.
- Stop if `required` is empty, or its words have fewer than 2 letters in all unless a road word asks for one digit ("شارع 9"). No results.

`group.sks` is `skeletons(word)` for a one-word group, else empty.

**MATCH strings** (`fts_string`). FTS4's standard syntax: `OR` binds tighter than the implicit AND between groups. A term is written `w*` if the group is a prefix or `w` has 3+ characters and is not all digits, else `w`.
For each required group, the alternatives are:
- one word: the term; and if `w` is Latin and `latin_base(w)` differs, the term of that too; and `x*`-style terms of the group's `alts` (not a prefix unless 3+ characters, as for any term). Several words: the phrase in double quotes.
- each category id `c` of the group: `¤c`.
- only in a loose string of n letters: each skeleton `s` of n+ letters: `§s`, with `*` if `s` has 3+ letters and it is the first skeleton.
- when there is one required group, or for the names-only string: `k:` in front of each alternative except a phrase (FTS4 cannot put a column on a phrase).
The alternatives are joined by ` OR `, the groups by a space.

**Candidates** (`candidates(match, near)`): ids run south to north, so a band of latitude is a range of ids. With a position, find the band of 1 degree either side of the car once (`SELECT id FROM p WHERE lat>=? ORDER BY lat LIMIT 1`, and `lat<=? ORDER BY lat DESC LIMIT 1`) and run
```sql
SELECT p.id,p.kind,p.name,p.alt,p.cat,p.lat,p.lon,p.area,p.rank,p.src FROM f CROSS JOIN p ON p.id=f.docid WHERE f MATCH ? AND f.docid BETWEEN A AND B
 ORDER BY (p.lat-LA)*(p.lat-LA)+(p.lon-LO)*(p.lon-LO)*K LIMIT 150      -- LA, LO in 1e-6 degrees; K = cos(lat)^2 to 6 decimals
```
If that returned fewer than 150, run the same without the band, and use that answer instead. If the answer is a full 150, also `ORDER BY p.rank DESC, p.id LIMIT 50` (without the band), unless every required group has cats and none of them is the airport category: a far row has no chance there, and this saves most of the time of the commonest searches. Without a position only `ORDER BY p.rank DESC, p.id LIMIT 200`.

**Stages**, the rows of all of them together:
0. With fillers: the names-only string plus `k:word` of each one-word filler. "روح الشرق" still finds that name.
1. With a role 5 group: the names-only string without any category alternatives.
2. With 2+ required groups: the names-only string, if it differs from the next one.
3. The plain string. (`first_stages` is 0 to 3.)
4. **Other readings** (`other_plans`), each a plan of its own made with `plan_query`, at most 4; `first_stages` of each, and a reading that found rows takes part in the score. They are: another name, when all the words of the query but stop words and fillers are a role 8 entry (each spelling of its `canon`; such a plan also runs `k:w1 k:w2...` of all its words first); letters, when 2+ words in a row have role 7: joined, "كي اف سي" is `kfc`; written apart, for each two neighbouring plain words of the same script (no cats, no digits): joined, "ستار بكس" is `ستاربكس`, and its first stages also take skeletons of 4+ letters.
5. Count: `few` is fewer than 6 rows so far; `none_near` is fewer than 3 rows with latitude and longitude both within 0.1 degrees of the car.
6. With one required group, a single word with no cats: `k:¶s OR ...` for its skeletons of 3+ letters, best-ranked not read. Towns and districts spelled otherwise (Al Malqa, Al Malga). A row counts only if the word `w` is equal to or `lev1` from a word of its name or alt in the query's script (Latin words also after `latin_base`); a row with no name in that script counts as it is.
7. If `few` or `none_near`: the single-edit string (`fts_edit_string`): for each word of 3+ letters of the required groups: the word (4+ letters: its first 3 letters with `*`), each spelling with a letter less (4+ letters; a 3-letter word only without a middle ا و ي, "هات" is `هت`), each spelling with a letter swapped within ت ث ط / س ص ث / د ذ ض ظ ز / ق ك ج غ / ه ح / ا ع, and for an Arabic word of up to 7 letters each spelling with ا و ي put in after the first letter or later. All with `k:`; a group's cats too. A row found here counts only if every required word `w` of a group whose cats do not hold the row's category has a word `n` in the row's name or alt with `lev1(w, n)` or `n` starting with `w`.
8. If `few`: the loose string of 3 letters, if it differs from the plain one.
9. If fewer than 3 rows so far: the loose string of 2 letters, if it differs. Two consonants say little, so only when nothing else is there.

Stages 6 to 9 read best-ranked rows never; the others as above. A row is scored by every reading that found rows, and keeps its best score.

## 5. The score

For a row: `n1 = query_words(name)`, `n2 = query_words(alt)`, `names` = both, plus `latin_base` of the Latin ones. `name_sks` = their skeletons. `area_words`, `area_sks`: the same for the row's `area.ar, en, city_ar, city_en`.

`cat_query`: `required` is not empty and every required group has cats.

**`quality(group)`**, 0..1:
- `cat_query`: with `in_cat` (the row's category is among the group's) and `named` (all the group's words are in `names`, or one of its `alts` is): both 1.0; `in_cat` only 0.9, or 0.4 for role 5; `named` only 0.8, or 0.95 for role 5; neither: the value below, at most 0.7.
- A group of several words: 1.0 if all are in `names`; 0.8 if the category is the group's; 0.6 if all are in `area_words`; else 0.5.
- One word `w`: 1.0 if `w` or (Latin) `latin_base(w)` is in `names`, or a name word has the same non-empty `canon` in `syn`.
  Otherwise the best of: for each name word starting with `w` (if `w` has 2+ letters) `0.8 + 0.09*len(w)/len(n)`; 0.8 if the category is the group's; if `w` has 4+ letters, for each name word `n` with `lev1(w, n)`: 0.9 for the same length, else 0.86; for each skeleton `s` of `w` with 2+ letters: if `s` is in `name_sks`, 0.92 for 3+ letters, else 0.85 if `w` and the names are in different scripts, else 0.75; or if the group is a prefix, `s` has 3+ letters and a name skeleton starts with it, 0.65.
  If that best is at least 0.75 it is the answer. Otherwise the best of it and: 0.6 if `w` is in `area_words`; 0.55 if an area word starts with `w` (2+ letters) or a skeleton of `w` is in `area_sks`; and 0.5.

A group **says where** if its quality is under 0.75 and one of its words is in `area_words` or starts an area word (2+ letters).

`t` = the mean of `quality` over the required groups that do not say where; if all do, over all of them.

`covered(words)`: the share of the words that are asked for: `w` is in the asked words (all groups' words, and `latin_base` of the Latin ones), or `a` (2+ letters) starts with `w` or `w` with `a`; or a skeleton of `w` (2+ letters) is among all groups' skeletons; or `w` has a `syn` entry with role 1, 2, 3 or 4, or whose cats hold the row's category ("Nahdi pharmacy" is as much النهدي as "Nahdi"); or `w` is in `area_words` ("King Abdul Aziz street, Jubail" on a row in Jubail). `c = max(covered(n1), covered(n2))`.

`in_name` = the qualities of the required groups that have no cats and do not say where. `of_kind` = the row's category is in the cats of every required group that has cats. `exact = 1` if `c >= 1`, `of_kind`, `in_name` is not empty and its lowest value is at least 0.9.

In a `cat_query`: `c = 0`, `exact = 0`, and `rank_weight = 30`; otherwise `rank_weight = 15`.

```
score = 55*t + 15*c + 22*exact + rank_weight*rank/100 + kindWeight
        + 20 if road_hint and the row is a road
        + 40 if district_hint and the row is a district
        - away
kindWeight: region 8, city 30, town 22, village 10, district 14, POI 10, road 8
km = haversine, R = 6371 km.  away = min(75, 11*ln(1+km)), 0 without a position
if exact, the row is a city or town, no road_hint, and km < 150 or every required word is equal to or lev1 from a name word: away = min(away, 6)
if exact and the row is a district and km < 60: away = min(away, 18)
with a role 8 reading: away = min(away, 15), and score + 30 if the row's names hold all that reading's words (stop words aside)
if cat_query and the row's category key is "airport": away = away*0.3
with 2+ required groups, when the required words hold the whole name of the row's town (all words of area.city_ar, or of city_en, stop words aside): away = min(away, 15)
```

Sort by score, highest first, ties by lower id. Then **one place once**: going down the list, drop a row if an earlier one shares a sorted `query_words` of name or alt and (both are roads and share an area or are under 5 km apart) or (they are under 150 m apart). Return 12, each with `approx` from `src` bit 4.

**Shown:** `name`, or `alt` if the UI's language differs from the script of `name` and there is an `alt`. Detail: for POIs, roads and districts the category's name in the UI's language; then the district, then the town, each unless it repeats what is shown. Nothing else from the index ever reaches the screen.

## 6. Reverse (dropped pin, "where am I")

From `p` with `lat` within 7000 (0.007°) and `lon` within `7000/cos(lat)`, kinds 5 and above, without coarse rows; nearest of each kind:
1. A POI within 60 m.
2. Else a road row within 600 m (its points are about 1 km apart: it means "on or near this named road"; better checked against the route engine when it is there).
3. Else the area of the nearest row of any kind that has one.
4. Else a district row, else nothing.

## 7. On the unit

- Run on one background thread, one search at a time; a newer query cancels (the app's `generation`). A 250 ms debounce while typing. Catch `SQLiteException` around the reads and answer with nothing.
- `query_words` on a name is called for up to 200 rows: keep the fold table in a `CharArray` or a `when`.
- Use `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)`; do not create it through a helper, which would write. Use `rawQuery` with a bound `?` for `MATCH`.
- The database has `page_size=4096`. A `PRAGMA mmap_size` is not needed for this file's speed (not measured on the unit).
- Voice ("وديني اقرب بنزين", "ابغى اروح الراشد مول", "ستار بكس", "كي اف سي"): the same search. `syn` holds the stop words, the words of speech and the letters' names; the plan and other readings do the rest.

## 8. How to add to it

- A new word for a kind of place: `SYNONYMS` in `places_tables.py`, rebuild. Nothing in the app changes.
- A change in `norm`, `base`, `skeleton` or the tokens: raise `NORM_VERSION`, rebuild, and change the Kotlin in the same release.
- A later option, not done because it needs a download that was not on the approved list: a transliteration model on the PC's GPU to spell Latin names in Arabic for the index. It would change only what goes into `k`.
