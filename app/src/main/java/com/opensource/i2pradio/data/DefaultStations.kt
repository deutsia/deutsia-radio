package com.opensource.i2pradio.data

object DefaultStations {
    fun getPresetStations(): List<RadioStation> {
        return listOf(
            RadioStation(
                name = "BBC World Service",
                streamUrl = "http://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
                genre = "News",
                isPreset = false,  // Changed to false
                useProxy = false
            ),
            RadioStation(
                name = "NPR News",
                streamUrl = "https://npr-ice.streamguys1.com/live.mp3",
                genre = "News",
                isPreset = false,  // Changed to false
                useProxy = false
            ),
            RadioStation(
                name = "Classical KING FM",
                streamUrl = "https://classicalking.streamguys1.com/king-aac-64k",
                genre = "Classical",
                isPreset = false,  // Changed to false
                useProxy = false
            )
        )
    }
}