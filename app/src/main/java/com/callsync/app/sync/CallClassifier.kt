package com.callsync.app.sync

/**
 * Decides whether a call is *personal* (e.g. family, friends) and should be kept
 * out of the work Notion log, versus *business* and pushed as usual.
 *
 * Deliberately biased toward pushing: a call is only held back when there's a
 * clear personal signal. Everything else — unknown numbers, business-looking
 * contacts, ambiguous calls — goes to Notion. Tune [PERSONAL_THRESHOLD]: higher
 * means fewer calls treated as personal (more pushed).
 */
object CallClassifier {

    /** Contact-name fragments that mark a call personal outright (case-insensitive). */
    private val personalNames = listOf(
        "mum", "mom", "mama", "mutti", "mami", "dad", "papa", "vati", "papi",
        "home", "zuhause", "oma", "opa", "schatz", "schatzi", "liebling",
        "bruder", "schwester", "sis", "bro",
    )

    /** Exact phone numbers to always treat as personal. Grow as needed. */
    private val personalNumbers = listOf<String>()

    /** Words that mark a call clearly business — they veto a personal call. */
    private val businessWords = listOf(
        "gmbh", "ag", "kg", "mbh", "klinik", "klinikum", "krankenhaus", "praxis",
        "medizintechnik", "medtech", "medizin", "zentrum", "angebot", "termin",
        "projekt", "export", "compliance", "rechnung", "firma", "vertrieb",
        "einkauf", "abteilung", "lieferung", "bestellung", "katalog", "demo",
    )

    /** Informal phrases that lean personal (German + English). */
    private val personalPhrases = listOf(
        "hab dich lieb", "bis später", "bis bald", "wie geht's dir", "wie geht es dir",
        "liebe grüße", "mach's gut", "love you", "see you later", "miss you",
    )

    /** Higher = fewer calls held back as personal. 0.6 keeps the bar high (push most). */
    const val PERSONAL_THRESHOLD = 0.6

    /**
     * @param contactName call-log contact name, if known
     * @param number raw phone number
     * @param transcript full transcript (may be empty for a no-answer call)
     */
    fun isPersonal(contactName: String?, number: String, transcript: String): Boolean {
        val name = contactName?.lowercase().orEmpty()
        val t = transcript.lowercase()

        // Hard rule: a business-looking contact name is never personal.
        if (businessWords.any { name.contains(it) }) return false

        // Hard rule: explicit personal contact.
        if (personalNames.any { name.contains(it) }) return true
        if (personalNumbers.any { number.contains(it) }) return true

        // Otherwise score the transcript; only block on a clear personal lean.
        var score = 0.0
        personalPhrases.forEach { if (t.contains(it)) score += 0.4 }
        businessWords.forEach { if (t.contains(it)) score -= 0.5 }
        // Lots of informal "du" address is a mild personal signal.
        val duHits = Regex("\\b(du|dich|dir|dein|deine|deinem)\\b").findAll(t).count()
        if (duHits >= 4) score += 0.3

        return score >= PERSONAL_THRESHOLD
    }
}
