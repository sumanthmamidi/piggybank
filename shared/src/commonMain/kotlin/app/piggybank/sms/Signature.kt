package app.piggybank.sms

/**
 * Turns an SMS into its *format*: the body with every variable part masked, so two SMS
 * that differ only in amount, merchant, account, date or reference share one signature (C3).
 * Matching is by structure plus sender, never by sender alone (C4).
 */
object Signature {
    private val operatorPrefix = Regex("""^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[A-Z])?$""")
    private val url = Regex("""https?://\S+|\b[\w-]+\.(?:in|com|co|me|ly|io)/\S*""", RegexOption.IGNORE_CASE)
    private val maskedAccount = Regex("""(?<![A-Za-z])[xX*•]+\s*\d{2,}""")
    /** Any token with a digit: refs, codes ("FEST500"), plain numbers. */
    private val digits = Regex("""[A-Za-z]*\d[A-Za-z0-9]*""")
    private val spaces = Regex("""\s+""")

    /** "VM-HDFCBK", "AD-HDFCBK-S" → "HDFCBK". Plain phone numbers collapse to "PHONE". */
    fun senderKey(sender: String): String {
        val s = sender.trim().uppercase()
        operatorPrefix.find(s)?.let { return it.groupValues[1] }
        return if (s.any { it.isLetter() }) s else "PHONE"
    }

    fun of(senderKey: String, body: String, parsed: ParsedSms?): String {
        var t = body
        val merchant = parsed?.merchant
        if (merchant != null && merchant != parsed.vpa && merchant.length >= 2) {
            t = t.replace(merchant, "{PARTY}", ignoreCase = true)
        }
        t = SmsParser.vpaPattern.replace(t, "{VPA}")
        t = url.replace(t, "{URL}")
        t = Amounts.pattern.replace(t, "{AMT}")
        for (p in SmsParser.datePatterns) t = p.replace(t, "{DATE}")
        t = SmsParser.timeRegex.replace(t, "{TIME}")
        t = maskedAccount.replace(t, "{ACC}")
        t = digits.replace(t, "{N}")
        t = spaces.replace(t.lowercase(), " ").trim()
        return "$senderKey|$t"
    }
}
