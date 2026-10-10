package com.abdllh.aura.voice

/**
 * What the owner asked for, from what the speech recogniser heard (Gulf Arabic, or English). Simple rules over the
 * normalised words, the most specific first: the sound, then the places, then the music.
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
            if (word("خلاص", "الغاء", "الغ", "cancel") || t == "لا" || t.contains("ولا شي") || t.contains("لا شي")) return Cancel

            // the sound
            if (has("صوت", "الصوت", "volume")) {
                val n = number(words)
                val down = has("نزل", "وطي", "قلل", "اخفض", "خفض", "نقص", "صغر", "خفف", "down")
                val up = has("ارفع", "رفع", "علي", "زود", "زيد", "كبر", "up")
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
                    else -> null
                }
            }
            if (word("اكتم", "كتم", "mute")) return Mute

            // places and screens
            if (has("البيت", "بيتي", "المنزل", "home") && has("ودن", "وديني", "خذني", "روح", "نروح", "رح", "طريق", "go", "take")) return Navigate(true)
            if (has("الدوام", "الشغل", "العمل", "work") && has("ودن", "وديني", "خذني", "روح", "نروح", "رح", "طريق", "go", "take")) return Navigate(false)
            // ("ماب" only as a whole word: "مابي" is "I don't want")
            val maps = has("الملاحه", "ملاحه", "الخريطه", "خريطه", "الخرايط", "الخرائط", "خرائط", "navigation", "maps", "gps") ||
                word("الماب", "ماب", "map")
            if (maps) return if (stop) null else OpenMaps // "وقف الملاحه": not a request it knows, and not "open the map"
            if (has("الرئيسيه", "الشاشه الرئيسيه", "home screen")) return HomeScreen
            if (has("كم الساعه", "الساعه كم", "كم الوقت", "الوقت", "what time")) return Time

            // music: stopping first ("طف الموسيقى" stops it, it does not open it)
            if (stop) return Pause
            if (has("بلوتوث", "البلوتوث", "bluetooth")) return BluetoothMusic
            if (has("الموسيقى", "موسيقى", "الموسيقي", "موسيقي", "الاغاني", "اغاني", "ميوزك", "music") && !has("الاغنيه", "اغنيه")) return OpenMusic
            if (has("غير", "غيرها", "التاليه", "التالي", "الجايه", "الجاي", "اللي بعد", "الي بعد", "بعدها", "next", "skip")) return Next
            if (has("اللي قبل", "الي قبل", "قبلها", "السابقه", "السابق", "previous", "back")) return Previous
            if (has("شغل", "شغلها", "كمل", "play", "resume")) return Play
            return null
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
                    '،', ',', '.', '!', '?', '؟', '-', '_' -> sb.append(' ')
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
