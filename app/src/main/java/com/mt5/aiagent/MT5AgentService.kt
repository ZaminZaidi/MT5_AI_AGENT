    fun applyVoiceProfile() {
        if (!isTtsReady) return
        val voices = tts?.voices ?: emptySet()
        if (isHindi()) {
            tts?.language = Locale("hi", "IN")
            val bestHi = voices
                .filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }
                .sortedByDescending { it.quality }
                .firstOrNull { it.name.contains("hid", true) || it.name.contains("hie", true) || it.name.contains("male", true) }
                ?: voices.filter { it.locale.language == "hi" }.maxByOrNull { it.quality }
            if (bestHi != null) tts?.voice = bestHi
            tts?.setPitch(0.96f)
            tts?.setSpeechRate(1.02f)
        } else {
            tts?.language = Locale.UK
            val bestUk = voices
                .filter { it.locale.language == "en" && it.locale.country == "GB" }
                .sortedByDescending { it.quality }
                .firstOrNull { it.name.contains("gbd", true) || it.name.contains("rjs", true) || it.name.contains("male", true) }
                ?: voices.filter { it.locale.language == "en" && it.locale.country == "GB" }.maxByOrNull { it.quality }
            if (bestUk != null) tts?.voice = bestUk
            tts?.setPitch(0.95f)
            tts?.setSpeechRate(1.02f)
        }
    }
