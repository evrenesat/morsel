package io.evren.morsel.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordDigestTest {

    @Test
    fun `known vector abc`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", PasswordDigest.digest("abc"))
    }

    @Test
    fun `digest is lowercase hex of utf-8 bytes`() {
        assertEquals(
            // md5 of the UTF-8 encoding of "paßwörd✓"
            "3c70163c9bfa22edc1cc151f9eba5a86",
            PasswordDigest.digest("paßwörd✓"),
        )
    }

    @Test
    fun `empty password digests deterministically`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", PasswordDigest.digest(""))
        assertEquals(PasswordDigest.digest("x"), PasswordDigest.digest("x"))
    }
}
