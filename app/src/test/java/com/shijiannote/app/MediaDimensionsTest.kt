package com.shijiannote.app
import org.junit.Assert.assertEquals
import org.junit.Test
class MediaDimensionsTest {
    @Test fun neverUpscalesAndPreservesLandscapePortraitProportions() {
        assertEquals(640 to 480, scaledMediaDimensions(640, 480, 1280))
        assertEquals(2560 to 1280, scaledMediaDimensions(4000, 2000, 2560))
        assertEquals(768 to 1280, scaledMediaDimensions(3000, 5000, 1280))
    }
}
