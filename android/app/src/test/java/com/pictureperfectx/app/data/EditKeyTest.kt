package com.pictureperfectx.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an unfinished edit is found again tomorrow.
 *
 * A draft is stored under a name derived from the photo it belongs to, so the only two things that
 * matter are that the name is stable across launches and that two photos never collide. Both are
 * silent when wrong: an unstable name loses the work, and a colliding one hands you somebody else's.
 */
class EditKeyTest {

    private val uri = "content://media/external/images/media/1234"

    @Test
    fun `the same photo always gets the same name`() {
        assertEquals(EditStore.keyFor(uri), EditStore.keyFor(uri))
    }

    @Test
    fun `different photos get different names`() {
        assertNotEquals(EditStore.keyFor(uri), EditStore.keyFor(uri + "5"))
        assertNotEquals(
            EditStore.keyFor("file:///storage/emulated/0/a.jpg"),
            EditStore.keyFor("file:///storage/emulated/0/b.jpg"),
        )
    }

    @Test
    fun `the name is safe to put in a filename`() {
        // A URI is full of slashes and colons, which is the reason it is digested rather than used.
        val key = EditStore.keyFor(uri)
        assertTrue("was '$key'", key.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("SHA-1 as hex", 40, key.length)
    }

    @Test
    fun `an empty uri still produces a usable name rather than nothing`() {
        assertEquals(40, EditStore.keyFor("").length)
    }
}
