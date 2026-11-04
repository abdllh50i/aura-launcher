package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Units
import kotlin.math.roundToInt

/**
 * What a voice prompt is made of: words of [Words]' table, each a clip once a clip pack has it, and free names, which only a
 * text-to-speech engine can say (maps.md section 12; the firmware has none, the owner installs Google's). A clip voice with no
 * engine drops a [Name] together with its [Name.lead] ([Speech.withoutNames]), and the sentence still stands: so no sentence here
 * leans on a name.
 */
sealed class Part {
    class Clip(val id: String) : Part()
    /** [lead]: the table's word that introduces it ("على", "باتجاه"), said and dropped with it. */
    class Name(val text: String, val lead: String? = null) : Part()
}

/** One line of the table. [arSaid], [enSaid]: when the voice should say it otherwise than the sign writes it. */
class Word(val ar: String, val en: String, val arSaid: String = ar, val enSaid: String = en)

/**
 * The choice of words, all in one table: change a word here and the sign, the voice and the English follow. Arabic is the way a
 * driver here is told the way: plain and Gulf-friendly, "لف يمين" rather than "انعطف يمينًا", and "عند الإشارة الثانية" rather than
 * a road's name nobody reads. English is lower case: [Speech] gives a sentence its capital. The ids are the clips' names.
 * (The words are here and not in res/values: the JVM tests must read them, and the app's other wording, [com.abdllh.aura.nav.Instructions], is code too.)
 */
object Words {
    val TABLE: Map<String, Word> = mapOf(
        // what to do
        "turn.right" to Word("لف يمين", "turn right"),
        "turn.left" to Word("لف يسار", "turn left"),
        "turn.slight_right" to Word("خذ يمين", "bear right"),
        "turn.slight_left" to Word("خذ يسار", "bear left"),
        "turn.sharp_right" to Word("لفة حادة يمين", "make a sharp right"),
        "turn.sharp_left" to Word("لفة حادة يسار", "make a sharp left"),
        "straight" to Word("كمّل على طول", "continue straight"),
        "keep.right" to Word("الزم اليمين", "keep right"),
        "keep.left" to Word("الزم اليسار", "keep left"),
        "merge.right" to Word("اندمج يمين", "merge right"),
        "merge.left" to Word("اندمج يسار", "merge left"),
        "merge" to Word("اندمج مع الطريق", "merge"),
        "ramp.in" to Word("خذ المدخل", "take the ramp"),
        "ramp.in.right" to Word("خذ المدخل على اليمين", "take the ramp on the right"),
        "ramp.in.left" to Word("خذ المدخل على اليسار", "take the ramp on the left"),
        "ramp.out" to Word("خذ المخرج", "take the exit"),
        "ramp.out.right" to Word("خذ المخرج على اليمين", "take the exit on the right"),
        "ramp.out.left" to Word("خذ المخرج على اليسار", "take the exit on the left"),
        "ramp.number" to Word("خذ مخرج", "take exit"),
        "side.right" to Word("على اليمين", "on the right"),
        "side.left" to Word("على اليسار", "on the left"),
        "end.road" to Word("في آخر الطريق", "at the end of the road,"),
        "depart" to Word("انطلق", "head out"),
        // U-turn honesty: said as a place ahead, then as what to do there
        "uturn.gap" to Word("لف وارجع من الدوران", "make a U-turn at the gap"),
        "uturn.ahead" to Word("الدوران", "U-turn"),
        // arriving
        "arrive.right" to Word("وجهتك على يمينك", "your destination is on the right"),
        "arrive.left" to Word("وجهتك على يسارك", "your destination is on the left"),
        "arrive.ahead" to Word("وجهتك قدامك", "your destination is ahead"),
        "arrived" to Word("وصلت إلى وجهتك", "you have arrived"),
        "arrived.at" to Word("وصلت إلى", "you have arrived at"),
        // the ferry
        "ferry.board" to Word("اركب العبّارة", "take the ferry"),
        "ferry.leave" to Word("انزل من العبّارة وكمّل", "leave the ferry and drive on"),
        // joining words
        "in" to Word("بعد", "in"),
        "pause" to Word("،", ","),
        "pause.en" to Word("", ","),
        "then" to Word("ثم", "then"),
        "onto" to Word("على", "onto"),
        "toward" to Word("باتجاه", "toward"),
        "on" to Word("على", "on"),
        "for" to Word("لمسافة", "for"),
        "keep.on" to Word("كمّل", "continue"),
        "reroute" to Word("جاري تحديث الطريق", "rerouting"),
        // the roundabout
        "ring.at" to Word("عند الدوار", "at the roundabout,"),
        "ring.at.named" to Word("عند", "at"),
        "ring.enter" to Word("ادخل الدوار", "enter the roundabout"),
        "ring.leave" to Word("اخرج من الدوار", "exit the roundabout"),
        "ring.straight" to Word("كمّل على طول", "go straight"),
        "ring.right" to Word("لف يمين", "turn right"),
        "ring.left" to Word("لف يسار", "turn left"),
        "ring.slight_right" to Word("خذ يمين", "bear right"),
        "ring.slight_left" to Word("خذ يسار", "bear left"),
        "ring.back" to Word("لف وارجع", "turn back"),
        // landmarks: the leg's signals, a flyover, a tunnel
        "signal.next" to Word("عند الإشارة", "at the signal,"),
        "signal.2" to Word("عند الإشارة الثانية", "at the second signal,"),
        "signal.3" to Word("عند الإشارة الثالثة", "at the third signal,"),
        "bridge.after" to Word("بعد الجسر", "after the flyover,"),
        "bridge.onto" to Word("واطلع الجسر", "and go up the flyover"),
        "tunnel.after" to Word("بعد النفق", "after the tunnel,"),
        "tunnel.into" to Word("وادخل النفق", "and into the tunnel"),
        "poi.before" to Word("قبل", "just before"),
        "poi.at" to Word("عند", "at"),
        "poi.after" to Word("بعد", "just after"),
        // kinds of place, for a landmark with no name the voice can say
        "kind.fuel" to Word("محطة البنزين", "the petrol station"),
        "kind.mosque" to Word("المسجد", "the mosque"),
        "kind.pharmacy" to Word("الصيدلية", "the pharmacy"),
        "kind.hospital" to Word("المستشفى", "the hospital"),
        "kind.mall" to Word("المول", "the mall"),
        "kind.supermarket" to Word("السوبرماركت", "the supermarket"),
        "kind.bank" to Word("البنك", "the bank"),
        "kind.hotel" to Word("الفندق", "the hotel"),
        "kind.school" to Word("المدرسة", "the school"),
        "kind.university" to Word("الجامعة", "the university"),
        "kind.park" to Word("الحديقة", "the park"),
        "kind.police" to Word("مركز الشرطة", "the police station"),
        // warnings along the road
        "camera" to Word("ساهر", "speed camera"),
        "limit" to Word("السرعة", "limit"),
        "bump" to Word("مطب", "speed bump"),
        "signal.ahead" to Word("إشارة", "traffic signal"),
        // the limit on the sign: which it is (nothing for his own)
        "limit.known" to Word("من الخريطة", "from the map"),
        "limit.estimated" to Word("تقريبي", "approx."),
        // warnings before a trip starts
        "warn.guard" to Word("فيه بوابة حراسة على الطريق، ممكن ما يدخلونك", "there is a guarded gate on the way, you may be stopped there"),
        "warn.border" to Word("الطريق يمر بمنفذ حدودي", "the route crosses a border post"),
        "warn.toll" to Word("الطريق فيه رسوم", "the route has a toll"),
        "warn.causeway" to Word("الطريق يعبر جسر الملك فهد، فيه رسوم وجوازات", "the route crosses the King Fahd Causeway: toll and passports"),
        "warn.military.end" to Word("وجهتك داخل منطقة عسكرية", "your destination is inside a military area"),
        "warn.private.end" to Word("وجهتك داخل منطقة خاصة", "your destination is inside a private area"),
        "warn.military.road" to Word("الطريق يدخل منطقة عسكرية", "the route goes into a military area"),
        "warn.private.road" to Word("الطريق يدخل منطقة خاصة", "the route goes into a private area"),
        "warn.haram.route" to Word("الطريق يدخل حدود الحرم، والدخول للمسلمين فقط", "the route enters the Haram boundary: Muslims only"),
        "warn.haram.end" to Word("وجهتك داخل حدود الحرم، والدخول للمسلمين فقط", "your destination is inside the Haram boundary: Muslims only"),
        "warn.track.end" to Word("آخر الطريق ترابي", "the last stretch is a dirt track"),
        "warn.ferry" to Word("الطريق فيه عبّارة", "the route includes a ferry"),
        "warn.far" to Word("وجهتك بعيدة عن الطريق", "your destination is away from the road"),
        // stops
        "fuel.next" to Word("أقرب محطة", "next fuel"),
        "mosque.next" to Word("أقرب مسجد على طريقك", "nearest mosque on your way"),
        "parking.near" to Word("أقرب مواقف", "nearest parking"),
        "prayer.fajr" to Word("الفجر", "Fajr"),
        "prayer.dhuhr" to Word("الظهر", "Dhuhr"),
        "prayer.asr" to Word("العصر", "Asr"),
        "prayer.maghrib" to Word("المغرب", "Maghrib"),
        "prayer.isha" to Word("العشاء", "Isha"),
        // route choices
        "choice.fastest" to Word("الأسرع", "fastest"),
        "choice.same" to Word("نفس الوقت", "same time"),
        "choice.slower" to Word("أبطأ بـ", "slower by"),
        "choice.shorter" to Word("أقصر بـ", "shorter by"),
        "choice.longer" to Word("أطول بـ", "longer by"),
        "eta.estimate" to Word("تقريبًا", "estimate")
    ) + Landmarks.BRANDS.associate { "brand.${it.key}" to Word(it.ar, it.en) }

    private val ORDINAL_AR = listOf("الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن")
    private val ORDINAL_EN = listOf("1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th")

    /** The words of [id]; dynamic ids: `dist.m.300`, `dist.km.12`, `dist.km.1.5`, `num.80`, `ring.exit.3`. "" for an unknown one (no id may crash a trip: [WordsTest] looks for them). */
    fun text(id: String, arabic: Boolean, said: Boolean = true): String {
        TABLE[id]?.let { w -> return if (arabic) (if (said) w.arSaid else w.ar) else (if (said) w.enSaid else w.en) }
        return when {
            id.startsWith("dist.m.") -> id.substring(7).toDoubleOrNull()?.let { Units.spokenDistance(it, arabic) }
            id.startsWith("dist.km.") -> id.substring(8).toDoubleOrNull()?.let { Units.spokenDistance(it * 1000, arabic) }
            id.startsWith("num.") -> id.substring(4)
            id.startsWith("ring.exit.") -> id.substring(10).toIntOrNull()?.let { n ->
                if (arabic) "المخرج ${ORDINAL_AR.getOrElse(n - 1) { "رقم $n" }}" else "take the ${ORDINAL_EN.getOrElse(n - 1) { "${n}th" }} exit"
            }
            else -> null
        }.orEmpty()
    }

    /** The clip for a distance, rounded as [Units.spokenDistance] says it. */
    fun distanceId(m: Double): String {
        if (m < 950) return "dist.m.${if (m >= 300) (m / 100).roundToInt() * 100 else (m / 50).roundToInt().coerceAtLeast(1) * 50}"
        val halves = if (m < 3000) (m / 500).roundToInt() else (m / 1000).roundToInt() * 2
        return if (halves % 2 == 1) "dist.km.${halves / 2}.5" else "dist.km.${halves / 2}"
    }
}

/** From a list of parts to what the text-to-speech engine or a sign gets. */
object Speech {
    private fun Part.Clip.isPause() = id == "pause" || id == "pause.en"

    /** One line. [said]: as the voice says it, else as the sign writes it. */
    fun text(parts: List<Part>, arabic: Boolean, said: Boolean = true): String {
        val b = StringBuilder()
        for (p in parts) when (p) {
            is Part.Clip -> {
                val w = Words.text(p.id, arabic, said)
                if (w.isEmpty()) continue
                if (!p.isPause() && b.isNotEmpty()) b.append(' ')
                b.append(w)
            }
            is Part.Name -> {
                p.lead?.let { l -> if (b.isNotEmpty()) b.append(' '); b.append(Words.text(l, arabic, said)) }
                if (b.isNotEmpty()) b.append(' ')
                b.append(p.text)
            }
        }
        val s = b.toString().removeSuffix(",")
        return if (arabic) s else s.replaceFirstChar { it.uppercase() }
    }

    /** What a clip voice with no engine can say. */
    fun withoutNames(parts: List<Part>): List<Part> = parts.filterIsInstance<Part.Clip>()

    fun clipIds(parts: List<Part>): List<String> = parts.filterIsInstance<Part.Clip>().map { it.id }
}
