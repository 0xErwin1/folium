package com.folium.reader.reader

import com.folium.reader.core.ocr.OcrRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class OcrEnginesTest {
    @Test fun registeredDescriptorOwnsDefaultRequestAndDataIdentity() {
        val descriptor = OcrEngines.loadDescriptor()
        assertFalse(descriptor.textEngineVersion(OcrRequest.DEFAULT).value.isBlank())
        assertSame(descriptor.javaClass, OcrEngines.loadDescriptor().javaClass)
    }
}
