package com.yaz.sms.core.link

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkPreviewTest {

    @Test
    fun metaTagsByPropertyOrName() {
        val html = """<html><head><title>Plain</title>
            <meta property="og:title" content="The title">
            <meta content='A picture' property='og:image'>
            <META NAME="description" CONTENT="About it">
            <meta property="og:title" content="Second">
            </head><body><meta property="og:site_name" content="Not in head"></body></html>"""
        val tags = LinkPreview.metaTags(html)
        assertEquals("The title", tags["og:title"])
        assertEquals("A picture", tags["og:image"])
        assertEquals("About it", tags["description"])
        assertEquals(null, tags["og:site_name"])
    }
}
