// Google Play AI pack: part of a model file, delivered on demand. Filled by preparePlayPacks.
plugins { id("com.android.ai-pack") }
aiPack {
    packName = "whisper_small"
    dynamicDelivery { deliveryType = "on-demand" }
}
