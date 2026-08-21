package com.folium.reader.core.pdf

import org.junit.Assert.assertThrows
import org.junit.Test

class ReflowPageColorsTest {

    @Test fun acceptsSixDigitHexForEveryChannel() {
        ReflowPageColors(foregroundHex = "000000", backgroundHex = "ffffff", accentHex = "3366CC")
    }

    @Test fun rejectsAChannelThatIsNotSixHexDigits() {
        assertThrows(IllegalArgumentException::class.java) {
            ReflowPageColors(foregroundHex = "000", backgroundHex = "ffffff", accentHex = "3366cc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReflowPageColors(foregroundHex = "#000000", backgroundHex = "ffffff", accentHex = "3366cc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReflowPageColors(foregroundHex = "000000", backgroundHex = "gggggg", accentHex = "3366cc")
        }
    }
}
