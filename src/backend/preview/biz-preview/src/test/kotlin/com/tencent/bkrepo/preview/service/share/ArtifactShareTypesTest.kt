package com.tencent.bkrepo.preview.service.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("作品分享类型解析")
class ArtifactShareTypesTest {

    @Test
    fun `metadata wins over path`() {
        assertEquals(
            "table",
            ArtifactShareTypes.resolve("table", "/artifact/site/v1/site_v1.html"),
        )
    }

    @Test
    fun `blank metadata falls back to path`() {
        assertEquals("html", ArtifactShareTypes.resolve("  ", "/artifact/BKCI介绍/v1/BKCI介绍_v1.html"))
        assertEquals("pdf", ArtifactShareTypes.fromPath("/docs/report.pdf"))
        assertEquals("image", ArtifactShareTypes.fromPath("/covers/hero.png"))
        assertEquals("code", ArtifactShareTypes.fromPath("/src/main.kt"))
        assertEquals("markdown", ArtifactShareTypes.fromPath("/readme.md"))
    }

    @Test
    fun `unknown or missing extension is null`() {
        assertNull(ArtifactShareTypes.fromPath("/sites/BKCI介绍"))
        assertNull(ArtifactShareTypes.fromPath("/tmp/file.unknown"))
        assertNull(ArtifactShareTypes.resolve(null, "/sites/BKCI介绍"))
    }

    @Test
    fun `media extensions map to video and audio`() {
        assertEquals("video", ArtifactShareTypes.fromPath("/clips/demo.mp4"))
        assertEquals("video", ArtifactShareTypes.fromPath("/clips/demo.WEBM"))
        assertEquals("audio", ArtifactShareTypes.fromPath("/sound/demo.mp3"))
        assertEquals("audio", ArtifactShareTypes.fromPath("/sound/demo.wav"))
        assertEquals("audio", ArtifactShareTypes.fromPath("/sound/demo.ogg"))
        assertEquals("audio", ArtifactShareTypes.fromPath("/sound/demo.oga"))
        assertEquals("audio", ArtifactShareTypes.fromPath("/sound/demo.m4a"))
        assertNull(ArtifactShareTypes.fromPath("/clips/demo.mov"))
        assertNull(ArtifactShareTypes.fromPath("/clips/demo.avi"))
        assertNull(ArtifactShareTypes.fromPath("/clips/demo.mkv"))
    }

    @Test
    fun `glb maps to 3d and gltf stays unsupported`() {
        assertEquals("3d", ArtifactShareTypes.fromPath("/models/hero.glb"))
        assertEquals("3d", ArtifactShareTypes.fromPath("/models/hero.GLB"))
        assertEquals("3d", ArtifactShareTypes.resolve("  ", "/models/hero.glb"))
        assertEquals("other", ArtifactShareTypes.resolve("other", "/models/hero.glb"))
        assertNull(ArtifactShareTypes.fromPath("/models/hero.gltf"))
        assertNull(ArtifactShareTypes.fromPath("/models/hero.obj"))
    }

    @Test
    fun `only native pdf uses native pdf viewer`() {
        assertTrue(ArtifactShareTypes.usesNativePdfViewer("pdf"))
        assertTrue(ArtifactShareTypes.usesNativePdfViewer("PDF"))
        assertTrue(ArtifactShareTypes.usesNativePdfViewer(null, "/docs/demo.pdf"))
        assertFalse(ArtifactShareTypes.usesNativePdfViewer("slides"))
        assertFalse(ArtifactShareTypes.usesNativePdfViewer("  slides  "))
        assertFalse(ArtifactShareTypes.usesNativePdfViewer("", "/talk/deck.pptx"))
        assertFalse(ArtifactShareTypes.usesNativePdfViewer("html"))
        assertFalse(ArtifactShareTypes.usesNativePdfViewer("video"))
        assertFalse(ArtifactShareTypes.usesNativePdfViewer(null, "/sites/index.html"))
        assertFalse(ArtifactShareTypes.usesNativePdfViewer(null, null))
    }
}
