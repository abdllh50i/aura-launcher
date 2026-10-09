package com.abdllh.aura.nav

import com.abdllh.aura.util.TextMatch

/**
 * A Latin spelling of an Arabic search, for places the map data only knows under a Latin name ("الجبيل مول" →
 * "al jubail mall" finds "Jubail Mall", which has no Arabic name in OpenStreetMap). Known words (place types, Saudi
 * cities, common brands) are translated; the rest is transliterated with the usual Gulf spellings ("فناتير" →
 * "fanateer"), close enough for the search engine's typo tolerance.
 */
object ArabicQuery {
    /** The Latin variant, or null when the query has no Arabic letters. */
    fun latin(query: String): String? {
        if (!TextMatch.arabic(query)) return null
        val words = TextMatch.norm(query).split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            // two-word names first ("خميس مشيط", "حفر الباطن")
            val phrase = if (i + 1 < words.size) PHRASES["${words[i]} ${words[i + 1]}"] else null
            if (phrase != null) { out.add(phrase); i += 2; continue }
            val w = words[i]
            val known = WORDS[w] ?: WORDS[w.removePrefix("ال")] // "المول" is "mall" too
            when {
                known != null -> if (known.isNotEmpty()) out.add(known)
                TextMatch.arabic(w) -> out.add(transliterate(w))
                else -> out.add(w) // already Latin / digits
            }
            i++
        }
        return out.joinToString(" ").trim().ifEmpty { null }
    }

    private fun transliterate(w: String): String {
        val article = w.startsWith("ال") && w.length > 3
        val body = if (article) w.substring(2) else w
        val prefix = if (article) "al " else ""
        val sb = StringBuilder()
        for ((k, c) in body.withIndex()) {
            val prev = if (k > 0) body[k - 1] else ' '
            val next = if (k + 1 < body.length) body[k + 1] else ' '
            val cons = consonant(c)
            when {
                c == 'ا' || c == 'آ' -> sb.append('a')
                c == 'ى' -> sb.append('a')
                c == 'ه' && k == body.length - 1 -> sb.append('a') // ة (normalised to ه) at the end
                c == 'ي' -> sb.append(if (k == 0 || isVowelLetter(next)) "y" else if (k == body.length - 1) "i" else "ee")
                c == 'و' -> sb.append(if (k == 0 || isVowelLetter(next)) "w" else "o")
                c == 'ع' -> if (k == 0) sb.append('a') else if (!isVowelLetter(prev)) sb.append('a')
                c == 'ء' || c == 'ئ' || c == 'ؤ' -> {}
                cons != null -> {
                    sb.append(cons)
                    // two consonants in a row: a short vowel was most likely left out in between
                    if (next != ' ' && consonant(next) != null && !isVowelLetter(next)) sb.append('a')
                }
            }
        }
        return prefix + sb.toString()
    }

    private fun isVowelLetter(c: Char) = c == 'ا' || c == 'و' || c == 'ي' || c == 'ى' || c == 'آ'

    private fun consonant(c: Char): String? = when (c) {
        'ب' -> "b"; 'ت' -> "t"; 'ث' -> "th"; 'ج' -> "j"; 'ح' -> "h"; 'خ' -> "kh"; 'د' -> "d"; 'ذ' -> "th"; 'ر' -> "r"
        'ز' -> "z"; 'س' -> "s"; 'ش' -> "sh"; 'ص' -> "s"; 'ض' -> "d"; 'ط' -> "t"; 'ظ' -> "z"; 'غ' -> "gh"; 'ف' -> "f"
        'ق' -> "q"; 'ك' -> "k"; 'ل' -> "l"; 'م' -> "m"; 'ن' -> "n"; 'ه' -> "h"; 'پ' -> "p"; 'چ' -> "ch"; 'گ' -> "g"; 'ڤ' -> "v"
        else -> null
    }

    private val PHRASES = mapOf(
        "خميس مشيط" to "khamis mushait", "حفر الباطن" to "hafar al batin", "راس تنوره" to "ras tanura",
        "ابو ظبي" to "abu dhabi", "صب واي" to "subway", "هوم سنتر" to "home centre", "بيتزا هت" to "pizza hut",
        "برجر كنج" to "burger king", "دانكن دونتس" to "dunkin donuts", "باسكن روبنز" to "baskin robbins"
    )

    // normalised keys (أ/إ/آ → ا, ى → ي, ة → ه)
    private val WORDS = mapOf(
        // place types
        "مول" to "mall", "مستشفي" to "hospital", "مستوصف" to "clinic", "عياده" to "clinic", "عيادات" to "clinics",
        "جامعه" to "university", "كليه" to "college", "مدرسه" to "school", "مطعم" to "restaurant", "مقهي" to "cafe",
        "كافيه" to "cafe", "كوفي" to "coffee", "فندق" to "hotel", "حديقه" to "park", "منتزه" to "park", "سوق" to "souq",
        "مركز" to "center", "سنتر" to "center", "شارع" to "street", "طريق" to "road", "مسجد" to "mosque", "جامع" to "mosque",
        "مطار" to "airport", "محطه" to "station", "بنك" to "bank", "مصرف" to "bank", "صيدليه" to "pharmacy",
        "برج" to "tower", "تاور" to "tower", "بوابه" to "gate", "ميناء" to "port", "شاطئ" to "beach", "شاطي" to "beach",
        "كورنيش" to "corniche", "نادي" to "club", "ملعب" to "stadium", "سينما" to "cinema", "هايبر" to "hyper",
        "سوبرماركت" to "supermarket", "ماركت" to "market", "بلازا" to "plaza", "بارك" to "park", "وزاره" to "ministry",
        "حي" to "", "مجمع" to "complex", "معرض" to "showroom", "ورشه" to "workshop", "بنزين" to "petrol", "مغسله" to "laundry",
        // cities and regions
        "الرياض" to "riyadh", "رياض" to "riyadh", "جده" to "jeddah", "مكه" to "makkah", "المدينه" to "madinah",
        "الدمام" to "dammam", "الخبر" to "khobar", "الظهران" to "dhahran", "الجبيل" to "jubail", "جبيل" to "jubail",
        "الاحساء" to "al ahsa", "الهفوف" to "hofuf", "القطيف" to "qatif", "الطائف" to "taif", "تبوك" to "tabuk",
        "بريده" to "buraydah", "عنيزه" to "unaizah", "حائل" to "hail", "ابها" to "abha", "جازان" to "jazan",
        "نجران" to "najran", "ينبع" to "yanbu", "الخرج" to "al kharj", "الباحه" to "al baha", "سكاكا" to "sakaka",
        "عرعر" to "arar", "القصيم" to "qassim", "الرس" to "ar rass", "المجمعه" to "majmaah", "الزلفي" to "zulfi",
        "رابغ" to "rabigh", "القنفذه" to "qunfudhah", "بيشه" to "bisha", "الدرعيه" to "diriyah", "العلا" to "alula",
        "سيهات" to "saihat", "صفوي" to "safwa", "دبي" to "dubai", "الشارقه" to "sharjah", "الكويت" to "kuwait",
        "البحرين" to "bahrain", "المنامه" to "manama", "الدوحه" to "doha", "مسقط" to "muscat",
        // brands
        "ستاربكس" to "starbucks", "ماكدونالدز" to "mcdonalds", "ماكدونالد" to "mcdonalds", "كنتاكي" to "kfc",
        "هرفي" to "herfy", "البيك" to "albaik", "دانكن" to "dunkin", "دومينوز" to "dominos", "ايكيا" to "ikea",
        "كارفور" to "carrefour", "لولو" to "lulu", "بنده" to "panda", "العثيم" to "othaim", "الدانوب" to "danube",
        "التميمي" to "tamimi", "جرير" to "jarir", "اكسترا" to "extra", "ساكو" to "saco", "نون" to "noon",
        "ارامكو" to "aramco", "سابك" to "sabic", "بانده" to "panda", "تيم" to "tim", "هورتنز" to "hortons"
    )
}
