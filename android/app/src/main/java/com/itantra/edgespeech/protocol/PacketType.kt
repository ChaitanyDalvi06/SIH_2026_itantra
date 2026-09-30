package com.itantra.edgespeech.protocol

enum class PacketType(val code: Byte) {
    HELLO(0x01),
    HELLO_ACK(0x02),
    CAPABILITIES(0x03),
    CAPABILITIES_ACK(0x04),
    SESSION_START(0x10),
    SESSION_END(0x11),
    TEXT_PARTIAL(0x1F),
    TEXT_FINAL(0x20),
    TEXT_ACK(0x21),
    ALERT(0x30),
    ALERT_ACK(0x31),
    HEARTBEAT(0x40),
    HEARTBEAT_ACK(0x41),
    ERROR(0x50),
    CLOSE(0x51);

    companion object {
        fun fromCode(code: Byte): PacketType = entries.find { it.code == code }
            ?: throw IllegalArgumentException("Unknown packet type 0x%02X".format(code))
    }
}

enum class Language(val code: Byte, val iso: String, val displayName: String) {
    HINDI(0, "hi", "हिन्दी (Hindi)"),
    GUJARATI(1, "gu", "ગુજરાતી (Gujarati)"),
    MARATHI(2, "mr", "मराठी (Marathi)"),
    KANNADA(3, "kn", "ಕನ್ನಡ (Kannada)"),
    MALAYALAM(4, "ml", "മലയാളം (Malayalam)"),
    TAMIL(5, "ta", "தமிழ் (Tamil)"),
    TELUGU(6, "te", "తెలుగు (Telugu)"),
    ODIA(7, "or", "ଓଡ଼ିଆ (Odia)"),
    BENGALI(8, "bn", "বাংলা (Bengali)"),
    ENGLISH(9, "en", "English (Indian)");

    companion object {
        fun fromCode(code: Byte): Language = entries.find { it.code == code } ?: HINDI
        fun fromIso(iso: String): Language = entries.find { it.iso == iso } ?: HINDI
    }
}
