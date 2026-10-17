package com.abdllh.aura.voice

import com.abdllh.aura.R
import com.abdllh.aura.util.TextMatch

/**
 * What the owner asked for, from what the speech recogniser heard (Gulf Arabic, or English). Simple rules over the
 * normalised words, the most specific first: questions (what is playing, how long to go, the date), the sound and the
 * screen, then places, apps, then the music. Long keys also match a word one letter off (the recogniser's spellings:
 * "الاضايه" for "الإضاءة", "بانزين" for "بنزين").
 */
internal sealed class Command {
    object Next : Command()
    object Previous : Command()
    object Pause : Command()
    object Play : Command()
    /** Up or down by [by] of the unit's volume steps ("ارفع الصوت": 3; "خمس درجات": 5). */
    data class VolumeStep(val up: Boolean, val by: Int = 3) : Command()
    data class VolumeSet(val level: Int) : Command()
    object VolumeMax : Command()
    object Mute : Command()
    object Unmute : Command()
    object OpenMusic : Command()
    object BluetoothMusic : Command()
    object OpenMaps : Command()
    data class Navigate(val home: Boolean) : Command()
    object HomeScreen : Command()
    object Time : Command()
    object Cancel : Command()
    // 1.13
    /** "وش الأغنية؟": the song and the singer. */
    object NowPlaying : Command()
    /** "كم باقي وأوصل؟": what is left of the route. */
    object Eta : Command()
    object Date : Command()
    /** The screen brighter or dimmer by [by] percent, or set to [level] percent. */
    data class Brightness(val up: Boolean, val by: Int = 20, val level: Int? = null) : Command()
    object ScreenOff : Command()
    /** "وين أقرب محطة بنزين": the nearest place of a kind. */
    data class Nearby(val place: Place) : Command()
    /** "ودني المطار": a place by name, on the map. */
    data class SearchPlace(val query: String) : Command()
    /** "افتح يوتيوب": an app by its name. */
    data class OpenApp(val name: String) : Command()
    /** "شغّل عبادي الجوهر": a song by name (YouTube Music). */
    data class PlaySong(val query: String) : Command()
    object Thanks : Command()
    object Who : Command()

    /** Kinds of places the nearby search knows: the words that name them, what is searched for, and the answer's word. */
    enum class Place(val label: Int, val ar: String, val en: String, vararg val keys: String) {
        FUEL(R.string.place_fuel, "محطة بنزين", "petrol station", "بنزين", "البنزين", "بانزين", "البانزين", "محطه", "المحطه", "محطات", "وقود", "الوقود", "petrol", "gas station", "fuel"),
        PHARMACY(R.string.place_pharmacy, "صيدلية", "pharmacy", "صيدليه", "الصيدليه", "صيدليات", "pharmacy", "drugstore"),
        RESTAURANT(R.string.place_restaurant, "مطعم", "restaurant", "مطعم", "المطعم", "مطاعم", "restaurant"),
        CAFE(R.string.place_cafe, "كوفي", "cafe", "كوفي", "الكوفي", "قهوه", "القهوه", "مقهى", "المقهى", "كافيه", "cafe", "coffee"),
        HOSPITAL(R.string.place_hospital, "مستشفى", "hospital", "مستشفى", "المستشفى", "مستشفيات", "طوارئ", "الطوارئ", "hospital", "emergency"),
        MOSQUE(R.string.place_mosque, "مسجد", "mosque", "مسجد", "المسجد", "جامع", "الجامع", "مصلى", "mosque"),
        ATM(R.string.place_atm, "صراف", "ATM", "صراف", "الصراف", "بنك", "البنك", "atm", "bank"),
        SUPERMARKET(R.string.place_supermarket, "سوبرماركت", "supermarket", "سوبرماركت", "السوبرماركت", "سوبر ماركت", "بقاله", "البقاله", "تموينات", "supermarket", "grocery"),
        HOTEL(R.string.place_hotel, "فندق", "hotel", "فندق", "الفندق", "فنادق", "hotel"),
        CARWASH(R.string.place_carwash, "مغسلة سيارات", "car wash", "مغسله", "المغسله", "غسيل سيارات", "car wash"),
        GARAGE(R.string.place_garage, "ورشة سيارات", "car repair", "ورشه", "الورشه", "ميكانيكي", "بنشر", "البنشر", "garage", "mechanic");
    }

    companion object {
        /** The first of [heard] (the recogniser's guesses, best first) that is a command. */
        fun parse(heard: List<String>): Command? = heard.firstNotNullOfOrNull { parse(it) }

        fun parse(text: String): Command? {
            val t = normalize(text)
            if (t.isBlank()) return null
            val words = t.split(' ')
            // each word also without a joined "و" (and), and "لل" (to the) read as "ال": "للبيت" is "البيت"
            val w = words.flatMap { wd ->
                listOfNotNull(wd, wd.removePrefix("و").takeIf { it != wd && it.length > 1 },
                    if (wd.startsWith("لل") && wd.length > 3) "ال" + wd.substring(2) else null)
            }
            // the keys are written as they are spelled; the text is normalised (ى → ي...), so they are too
            fun has(vararg k: String) = k.any { raw ->
                val key = normalize(raw)
                if (' ' in key) t.contains(key) else w.any { it == key || it.startsWith(key) }
            }
            fun word(vararg k: String) = k.any { raw -> val key = normalize(raw); w.any { it == key } }
            val stop = has("طف", "طفي", "طفها", "وقف", "وقفها", "اوقف", "سكر", "اسكت", "pause", "stop")

            // "الغ الكتم" takes the mute away (and does not cancel)
            if (has("الكتم") && has("فك", "الغ", "الغاء", "شيل", "وقف", "رجع")) return Unmute
            if (word("خلاص", "الغاء", "الغ", "cancel", "انسى", "انساها") || t == "لا" || t.contains("ولا شي") || t.contains("لا شي") ||
                t.contains("مابي شي") || t.contains("ما ابي شي") || t.contains("لا شكرا") || t.contains("never mind")) return Cancel
            if (words.size <= 4 && (word("شكرا", "مشكور", "تسلم", "thanks") || t.contains("يعطيك العافيه") || t.contains("thank you"))) return Thanks
            if (t.contains("مين انت") || t.contains("وش اسمك") || t.contains("ايش اسمك") || t.contains("who are you") || t.contains("your name")) return Who

            // questions
            val ask = word("وش", "ايش", "اش", "شو", "شنو", "مين", "what", "who", "which") || has("وش اسم", "ايش اسم", "اسم الاغنيه")
            if (ask && has("الاغنيه", "اغنيه", "هالاغنيه", "يغني", "يغنيها", "المغني", "المطرب", "الفنان", "شغال", "song", "singer", "sings", "playing")) return NowPlaying
            if (has("كم باقي", "كم بقي", "كم باقي على", "متى اوصل", "متى نوصل", "متى بنوصل", "متى بوصل", "وقت الوصول", "كم المسافه",
                    "how far", "how long", "eta", "when will", "arrive")) return Eta
            if (has("التاريخ", "date") || t.contains("وش اليوم") || t.contains("اليوم كم") || t.contains("اي يوم") || t.contains("what day")) return Date

            // the screen
            val up = has("ارفع", "رفع", "علي", "زود", "زيد", "كبر", "up")
            val down = has("نزل", "وطي", "قلل", "اخفض", "خفض", "نقص", "صغر", "خفف", "down")
            if (has("الشاشه", "screen") && has("طف", "سكر", "اقفل", "off")) return ScreenOff
            if (has("الاضاءه", "اضاءه", "الاضائه", "اضائه", "الاضاه", "السطوع", "سطوع", "brightness", "brighter", "dimmer", "dim") ||
                (has("الشاشه", "screen") && (up || down))) {
                val n = number(words)
                return when {
                    has("للاخر", "للاخير", "على الاخر", "اعلى شي", "full", "max") -> Brightness(true, level = 100)
                    has("نص", "half") -> Brightness(true, level = 50)
                    n != null -> Brightness(true, level = n.coerceIn(0, 100))
                    down || has("dimmer", "dim", "غامق", "اغمق") -> Brightness(false)
                    else -> Brightness(true) // "ارفع الإضاءة", "brighter", "الإضاءة ضعيفة"
                }
            }

            // the sound
            if (has("صوت", "الصوت", "volume")) {
                val n = number(words)
                return when {
                    has("للاخر", "للاخير", "الاخير", "اخر شي", "اعلى شي", "على الاخر", "فل", "full", "max") -> VolumeMax
                    has("اكتم", "كتم", "mute") -> Mute
                    (has("طف", "سكر", "اقفل", "وقف") && n == null) -> Mute
                    has("رجع الصوت", "شغل الصوت", "unmute") -> Unmute
                    // "ارفع الصوت خمس درجات": by that many, not to it
                    has("درجه", "درجات", "درجتين") && (up || down) -> VolumeStep(!down, if (has("درجتين")) 2 else (n ?: 1).coerceIn(1, 20))
                    n != null -> VolumeSet(n)
                    down -> VolumeStep(false)
                    up -> VolumeStep(true)
                    // "الصوت عالي" (too loud), "الصوت واطي" / "ما اسمع" (too low)
                    has("عالي", "مرتفع", "loud") -> VolumeStep(false)
                    has("واطي", "ضعيف", "منخفض", "ما اسمع", "low") -> VolumeStep(true)
                    else -> null
                }
            }
            if (word("اكتم", "كتم", "mute")) return Mute

            // places and screens
            val go = has("ودن", "وديني", "ودينا", "خذني", "خذنا", "وصلني", "وصلنا", "روح", "نروح", "رح", "طريق", "go", "take", "navigate")
            if (has("البيت", "بيتي", "المنزل", "home") && go && !has("home screen")) return Navigate(true)
            if (has("الدوام", "الشغل", "العمل", "work") && go) return Navigate(false)
            Place.values().firstOrNull { p -> has(*p.keys) }?.let { p ->
                // "وين أقرب محطة"; also the kind alone ("صيدلية؟"), or with "أبي" / "ودني"
                if (go || words.size <= 3 || has("اقرب", "قريب", "وين", "دور", "ابي", "ابغى", "ابغي", "فيه", "find", "nearest", "nearby", "where", "need")) return Nearby(p)
            }
            if (go) {
                val dest = after(words, "ودني", "وديني", "ودنا", "ودينا", "خذني", "خذنا", "وصلني", "وصلنا", "روح", "نروح", "رح", "take", "go", "navigate",
                    drop = setOf("على", "الى", "الي", "لين", "عند", "me", "to", "the", "يا", "لي"))
                if (dest != null && !mapsWord(dest)) return SearchPlace(dest)
            }
            // ("ماب" only as a whole word: "مابي" is "I don't want")
            val maps = has("الملاحه", "ملاحه", "الخريطه", "خريطه", "الخرايط", "الخرائط", "خرائط", "navigation", "maps", "gps") ||
                word("الماب", "ماب", "map")
            if (maps) return if (stop) null else OpenMaps // "وقف الملاحه": not a request it knows, and not "open the map"
            if (has("الرئيسيه", "الشاشه الرئيسيه", "home screen")) return HomeScreen
            if (has("كم الساعه", "الساعه كم", "كم الوقت", "الوقت", "what time")) return Time

            // an app by name: "افتح يوتيوب" (the music and Bluetooth screens are the requests below)
            if (has("افتح", "افتحي", "فتح", "open", "launch")) {
                val name = after(words, "افتح", "افتحي", "فتح", "open", "launch", drop = setOf("لي", "تطبيق", "برنامج", "app", "the"))
                if (name != null && CAR.any { normalize(it) == name }) return null // "افتح الشباك": nothing the unit can do
                if (name != null && !musicWord(name) && !has("بلوتوث", "البلوتوث", "bluetooth")) return OpenApp(name)
            }

            // music: stopping first ("طف الموسيقى" stops it, it does not open it)
            if (stop) return Pause
            if (has("بلوتوث", "البلوتوث", "bluetooth")) return BluetoothMusic
            if (has("الموسيقى", "موسيقى", "الموسيقي", "موسيقي", "الاغاني", "اغاني", "ميوزك", "music") && !has("الاغنيه", "اغنيه")) return OpenMusic
            if (has("غير", "غيرها", "التاليه", "التالي", "الجايه", "الجاي", "اللي بعد", "الي بعد", "بعدها", "طوفها", "next", "skip")) return Next
            if (has("اللي قبل", "الي قبل", "قبلها", "السابقه", "السابق", "اللي فاتت", "رجعها", "previous", "back")) return Previous
            if (has("شغل", "شغلها", "شغلي", "حط", "حطي", "كمل", "play", "resume")) {
                val q = after(words, "شغل", "شغلي", "شغللي", "حط", "حطي", "play", drop = FILLERS)
                return when {
                    q == null -> Play
                    CAR.any { normalize(it) == q } -> null // "شغل المكيف": not a song, and nothing the unit can do
                    APPS.any { TextMatch.score(it, q) >= 0.9 } -> OpenApp(q) // "شغل يوتيوب", "شغل الراديو"
                    else -> PlaySong(q)
                }
            }
            return null
        }

        /** Words around a song's name that are not the name ("شغل لي أغنية ...", "شغل لنا شي حلو"). */
        private val FILLERS = setOf("لي", "لنا", "اغنيه", "الاغنيه", "اغنيات", "شي", "شيء", "حلو", "حلوه", "نسمعه", "نسمع", "اي",
            "يا", "song", "the", "some", "something", "music", "me", "us", "a")

        /** What one asks a car to switch on that is no song (the unit cannot do these). */
        private val CAR = listOf("المكيف", "التكييف", "الشباك", "الدفايه", "الماسحات", "المساحات", "الانوار", "الفلاشر", "الكشافات")

        /** Apps one "plays" ("شغل يوتيوب"). */
        private val APPS = listOf("يوتيوب", "youtube", "الراديو", "راديو", "radio", "انغامي", "anghami", "سبوتيفاي", "spotify", "شاهد", "نتفلكس", "netflix")

        private fun mapsWord(s: String) = s.split(' ').any { it in setOf("الخريطه", "الملاحه", "الماب", "map", "maps") }

        private fun musicWord(s: String) = s.split(' ').any {
            it in setOf("الموسيقى", "موسيقى", "الموسيقي", "الاغاني", "اغاني", "ميوزك", "music", "الاغنيه")
        }

        /**
         * The words after the first of [verbs] (a word that is one, or starts with one: "شغللي"), without [drop]ped
         * ones at their start or end; null when nothing is left.
         */
        private fun after(words: List<String>, vararg verbs: String, drop: Set<String>): String? {
            val keys = verbs.map { normalize(it) }
            val i = words.indexOfFirst { wd -> keys.any { k -> wd == k || (wd.startsWith(k) && wd.length <= k.length + 2) } }
            if (i < 0) return null
            val rest = words.drop(i + 1).toMutableList()
            val dropped = drop.map { normalize(it) }.toSet()
            while (rest.isNotEmpty() && rest.first() in dropped) rest.removeAt(0)
            while (rest.isNotEmpty() && rest.last() in dropped) rest.removeAt(rest.size - 1)
            return rest.joinToString(" ").trim().takeIf { it.isNotEmpty() }
        }

        /** Without diacritics and tatweel, one alef, ه for ة, ي for ى, western digits, lower case, single spaces. */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s.lowercase()) {
                when (ch) {
                    in 'ً'..'ْ', 'ـ', 'ٰ' -> {}
                    'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                    'ى' -> sb.append('ي')
                    'ة' -> sb.append('ه')
                    'ؤ' -> sb.append('و')
                    'ئ' -> sb.append('ي')
                    in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                    in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                    '،', ',', '.', '!', '?', '؟', '-', '_', '«', '»', '"', '\'' -> sb.append(' ')
                    else -> sb.append(ch)
                }
            }
            return sb.toString().trim().replace(Regex("\\s+"), " ")
        }

        private val UNITS = mapOf(
            "صفر" to 0, "واحد" to 1, "وحده" to 1, "اثنين" to 2, "ثنين" to 2, "اثنان" to 2, "ثلاث" to 3, "ثلاثه" to 3, "ثلاثة" to 3,
            "اربع" to 4, "اربعه" to 4, "خمس" to 5, "خمسه" to 5, "ست" to 6, "سته" to 6, "سبع" to 7, "سبعه" to 7,
            "ثمان" to 8, "ثماني" to 8, "ثمانيه" to 8, "ثمنيه" to 8, "تسع" to 9, "تسعه" to 9, "عشر" to 10, "عشره" to 10
        )
        private val TEENS = mapOf(
            "احدعش" to 11, "احدعشر" to 11, "اثنعش" to 12, "اثنعشر" to 12, "ثنعش" to 12, "ثلاطعش" to 13, "ثلطعش" to 13,
            "اربعطعش" to 14, "اربعتعش" to 14, "خمسطعش" to 15, "خمستعش" to 15, "ستعش" to 16, "سطعش" to 16, "سبعطعش" to 17,
            "سبعتعش" to 17, "ثمنطعش" to 18, "ثمانطعش" to 18, "تسعطعش" to 19, "تسعتعش" to 19
        )
        private val TENS = mapOf(
            "عشرين" to 20, "عشرون" to 20, "ثلاثين" to 30, "ثلاثون" to 30, "اربعين" to 40, "اربعون" to 40,
            "خمسين" to 50, "خمسون" to 50, "ستين" to 60, "سبعين" to 70, "ثمانين" to 80, "تسعين" to 90, "مية" to 100, "ميه" to 100, "مائه" to 100
        )

        /** The first number said, in digits or in words ("خمسه وعشرين", "خمسطعش", "خمسه عشر", "للعشرين"). */
        fun number(words: List<String>): Int? {
            for (wd in words) Regex("\\d+").find(wd)?.value?.toIntOrNull()?.let { return it }
            // the word itself, or without a joined "و" (and), "ل" / "لل" (to), "ال" (the)
            fun base(x: String): String? = sequenceOf(x, x.removePrefix("و"), x.removePrefix("لل"), x.removePrefix("ل"), x.removePrefix("ال"))
                .firstOrNull { it in UNITS || it in TEENS || it in TENS }
            for (i in words.indices) {
                val wd = base(words[i]) ?: continue
                TEENS[wd]?.let { return it }
                TENS[wd]?.let { return it }
                val u = UNITS[wd] ?: continue
                val next = words.getOrNull(i + 1)?.let { base(it) }
                if (u < 10 && (next == "عشر" || next == "عشره")) return u + 10 // خمسه عشر
                if (u < 10) TENS[next]?.let { return it + u }                 // خمسه وعشرين
                return u
            }
            return null
        }
    }
}
