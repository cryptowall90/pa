package com.pictureperfectx.app.layers

import com.pictureperfectx.app.capture.AspectRatio
import com.pictureperfectx.app.capture.CropRect
import com.pictureperfectx.app.capture.ImageGeometry
import com.pictureperfectx.app.capture.ToneAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An edit that reopens *nearly* the same is worse than one that doesn't reopen at all — the first
 * kind you don't notice until the photo is already wrong. So this holds the round trip to equality
 * across every layer kind rather than spot-checking a couple of fields.
 *
 * The single deliberate exception is a mask's soft edge, which is stored to 8 bits and so rounds by
 * up to a 255th. That one is checked to a tolerance, and separately, so the strict test stays strict.
 */
class EditDocumentTest {

    private fun lassoed() = MaskLasso.trace(
        Mask.forRatio(4f / 3f),
        listOf(MaskPoint(0.2f, 0.2f), MaskPoint(0.8f, 0.25f), MaskPoint(0.6f, 0.8f)),
    )

    /**
     * A hard-edged mask, exercising every field a mask has.
     *
     * Deliberately all-or-nothing coverage: values land exactly on the 8-bit grid the format stores,
     * so this can be held to *equality*. A soft edge cannot be — see the antialiasing test below —
     * and mixing the two would leave the strict check unable to say anything strictly.
     */
    private fun crisp(): Mask {
        val blank = Mask.blank(64, 48)
        return blank.copy(
            coverage = FloatArray(blank.columns * blank.rows) {
                if (it % blank.columns < blank.columns / 2) 1f else 0f
            },
            feather = 0.2f,
            inverted = true,
            path = listOf(MaskPoint(0.1f, 0.1f), MaskPoint(0.9f, 0.2f), MaskPoint(0.5f, 0.9f)),
        )
    }

    /** One of everything, each carrying a value that isn't its default. */
    private fun everything(): Document {
        var document = Document()
        document = document.add { Layer.Tone(it, adjustments = ToneAdjustments(shadows = 25, warmth = -8)) }
        document = document.add { Layer.Look(it, filterId = "kodak-gold", intensity = 62) }
        document = document.add { Layer.Blur(it, radius = 31) }
        document = document.add {
            Layer.Text(
                it,
                content = "Hello, world",
                centre = MaskPoint(0.3f, 0.7f),
                size = 0.12f,
                rotation = -14f,
                colour = GradientColour(hue = 210f),
                font = TextFont.Serif,
            )
        }
        document = document.add {
            Layer.Heal(it, dabs = listOf(HealDab(MaskPoint(0.4f, 0.4f), MaskPoint(0.5f, 0.45f), 0.02f)))
        }
        document = document.add { Layer.Smooth(it, amount = 70, mask = crisp()) }
        document = document.add {
            Layer.Shape(it, kind = ShapeKind.Ellipse, width = 0.5f, height = 0.2f, stroke = 0.01f)
        }
        document = document.add {
            Layer.Curve(
                it,
                spec = CurveSpec(
                    rgb = listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.2f), CurvePoint(1f, 1f)),
                    red = listOf(CurvePoint(0f, 0.05f), CurvePoint(1f, 1f)),
                ),
            )
        }
        document = document.add {
            Layer.Gradient(
                it,
                spec = GradientSpec(style = GradientStyle.Diamond, midpoint = 0.35f),
                from = GradientColour(hue = 300f),
                to = GradientColour(tone = ColourTone.Clear),
                blend = BlendMode.Overlay,
                opacity = 0.72f,
                mask = crisp(),
            )
        }
        return document
    }

    @Test
    fun `every layer kind comes back exactly as it went in`() {
        val edit = EditDocument(
            geometry = ImageGeometry(
                quarterTurns = 3,
                straightenDegrees = -6.5f,
                flipHorizontal = true,
                crop = CropRect(0.1f, 0.2f, 0.85f, 0.9f),
                aspect = AspectRatio.R4x5,
            ),
            document = everything(),
        )

        val reopened = EditDocument.decode(EditDocument.encode(edit))
        assertNotNull("should decode", reopened)
        assertEquals(edit.geometry, reopened!!.geometry)
        assertEquals(edit.document.layers.size, reopened.document.layers.size)
        assertEquals(edit.document, reopened.document)
        assertEquals("stamped with the version that wrote it", EditDocument.VERSION, reopened.version)
    }

    @Test
    fun `which layer was selected survives, so it is still selected on reopening`() {
        val document = everything()
        val edit = EditDocument(document = document)
        val reopened = EditDocument.decode(EditDocument.encode(edit))!!
        assertEquals(document.selectedId, reopened.document.selectedId)
        assertNotNull(reopened.document.selected)
    }

    @Test
    fun `a mask with no coverage still means everywhere, not nowhere`() {
        val edit = EditDocument(document = Document().add { Layer.Tone(it) })
        val reopened = EditDocument.decode(EditDocument.encode(edit))!!
        val mask = reopened.document.layers.single().mask
        assertTrue("an unmasked layer must stay unmasked", mask.isEmpty)
        assertEquals("which renders as covering everything", 1f, mask.coverageAt(3, 3), 0f)
    }

    @Test
    fun `a lasso's points and a fade's placement are kept, so they stay draggable`() {
        val faded = MaskGradient.fill(Mask.forRatio(1f), GradientSpec(style = GradientStyle.Angular))
        val edit = EditDocument(
            document = Document()
                .add { Layer.Tone(it, mask = lassoed()) }
                .add { Layer.Tone(it, mask = faded) },
        )
        val reopened = EditDocument.decode(EditDocument.encode(edit))!!
        assertEquals(lassoed().path, reopened.document.layers[0].mask.path)
        assertEquals(faded.gradient, reopened.document.layers[1].mask.gradient)
    }

    @Test
    fun `a soft edge comes back within a step of where it was`() {
        // The one thing that isn't exact. A lasso's antialiased rim carries values between 0 and 1
        // that don't land on the 8-bit grid, so they round — by less than a level of the coverage
        // grid, which is itself far coarser than the photo it will be scaled over.
        val edit = EditDocument(document = Document().add { Layer.Tone(it, mask = lassoed()) })
        val reopened = EditDocument.decode(EditDocument.encode(edit))!!

        val before = lassoed().coverage
        val after = reopened.document.layers.single().mask.coverage
        assertEquals("same grid", before.size, after.size)
        before.indices.forEach { index ->
            assertEquals("cell $index", before[index], after[index], 1f / 255f)
        }
        assertTrue("and the soft edge is still soft", after.any { it > 0f && it < 1f })
    }

    @Test
    fun `a field this build has never heard of is ignored rather than fatal`() {
        // What a stack written by a newer build looks like to an older one.
        val encoded = EditDocument.encode(EditDocument(document = everything()))
        val meddled = encoded.replaceFirst("{", """{"somethingNewer":{"a":1},""")
        assertNotNull(EditDocument.decode(meddled))
    }

    @Test
    fun `text that isn't an edit is refused rather than half-read`() {
        assertNull(EditDocument.decode(""))
        assertNull(EditDocument.decode("not json"))
        assertNull(EditDocument.decode("""{"document":{"layers":[{"type":"nonesuch"}]}}"""))
    }

    @Test
    fun `an untouched edit encodes to almost nothing`() {
        // Defaults aren't written, so an unedited photo costs a few bytes rather than a document.
        assertTrue(EditDocument.encode(EditDocument()).length < 64)
    }
}
