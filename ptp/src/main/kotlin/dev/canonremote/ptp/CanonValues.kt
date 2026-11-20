package dev.canonremote.ptp

/**
 * Libellés et payloads des propriétés de réglage Canon (EOS 90D).
 * Tables issues de libgphoto2.
 */
object CanonValues {
    private val iso: Map<Int, String> = mapOf(
        0x00 to "Auto",
        0x40 to "50",
        0x48 to "100",
        0x4B to "125",
        0x4D to "160",
        0x50 to "200",
        0x53 to "250",
        0x55 to "320",
        0x58 to "400",
        0x5B to "500",
        0x5D to "640",
        0x60 to "800",
        0x63 to "1000",
        0x65 to "1250",
        0x68 to "1600",
        0x6B to "2000",
        0x6D to "2500",
        0x70 to "3200",
        0x73 to "4000",
        0x75 to "5000",
        0x78 to "6400",
        0x7B to "8000",
        0x7D to "10000",
        0x80 to "12800",
        0x83 to "16000",
        0x85 to "20000",
        0x88 to "25600",
        0x90 to "51200",
    )

    private val shutter: Map<Int, String> = mapOf(
        0x00 to "Auto",
        0x0C to "Bulb",
        0x10 to "30\"",
        0x13 to "25\"",
        0x14 to "20\"",
        0x15 to "20\"",
        0x18 to "15\"",
        0x1B to "13\"",
        0x1C to "10\"",
        0x1D to "10\"",
        0x20 to "8\"",
        0x23 to "6\"",
        0x24 to "6\"",
        0x25 to "5\"",
        0x28 to "4\"",
        0x2B to "3\"2",
        0x2C to "3\"",
        0x2D to "2\"5",
        0x30 to "2\"",
        0x33 to "1\"6",
        0x34 to "1\"5",
        0x35 to "1\"3",
        0x38 to "1\"",
        0x3B to "0\"8",
        0x3C to "0\"7",
        0x3D to "0\"6",
        0x40 to "0\"5",
        0x43 to "0\"4",
        0x44 to "0\"3",
        0x45 to "0\"3",
        0x48 to "1/4",
        0x4B to "1/5",
        0x4C to "1/6",
        0x4D to "1/6",
        0x50 to "1/8",
        0x53 to "1/10",
        0x54 to "1/10",
        0x55 to "1/13",
        0x58 to "1/15",
        0x5B to "1/20",
        0x5C to "1/20",
        0x5D to "1/25",
        0x60 to "1/30",
        0x63 to "1/40",
        0x64 to "1/45",
        0x65 to "1/50",
        0x68 to "1/60",
        0x6B to "1/80",
        0x6C to "1/90",
        0x6D to "1/100",
        0x70 to "1/125",
        0x73 to "1/160",
        0x74 to "1/180",
        0x75 to "1/200",
        0x78 to "1/250",
        0x7B to "1/320",
        0x7C to "1/350",
        0x7D to "1/400",
        0x80 to "1/500",
        0x83 to "1/640",
        0x84 to "1/750",
        0x85 to "1/800",
        0x88 to "1/1000",
        0x8B to "1/1250",
        0x8C to "1/1500",
        0x8D to "1/1600",
        0x90 to "1/2000",
        0x93 to "1/2500",
        0x94 to "1/3000",
        0x95 to "1/3200",
        0x98 to "1/4000",
        0x9B to "1/5000",
        0x9C to "1/6000",
        0x9D to "1/6400",
        0xA0 to "1/8000",
    )

    /** Nombres d'ouverture, sans le préfixe "f/". */
    private val aperture: Map<Int, String> = mapOf(
        0x08 to "1.0",
        0x0B to "1.1",
        0x0C to "1.2",
        0x0D to "1.2",
        0x10 to "1.4",
        0x13 to "1.6",
        0x14 to "1.8",
        0x15 to "1.8",
        0x18 to "2.0",
        0x1B to "2.2",
        0x1C to "2.5",
        0x1D to "2.5",
        0x20 to "2.8",
        0x23 to "3.2",
        0x24 to "3.5",
        0x25 to "3.5",
        0x28 to "4.0",
        0x2B to "4.5",
        0x2C to "4.5",
        0x2D to "5.0",
        0x30 to "5.6",
        0x33 to "6.3",
        0x34 to "6.7",
        0x35 to "7.1",
        0x38 to "8.0",
        0x3B to "9.0",
        0x3C to "9.5",
        0x3D to "10",
        0x40 to "11",
        0x43 to "13",
        0x44 to "13",
        0x45 to "14",
        0x48 to "16",
        0x4B to "18",
        0x4C to "19",
        0x4D to "20",
        0x50 to "22",
        0x53 to "25",
        0x54 to "27",
        0x55 to "29",
        0x58 to "32",
        0x5B to "36",
        0x5C to "38",
        0x5D to "40",
        0x60 to "45",
        0x63 to "51",
        0x64 to "54",
        0x65 to "57",
        0x68 to "64",
        0x6B to "72",
        0x6C to "76",
        0x6D to "80",
        0x70 to "91",
    )

    private val whiteBalance: Map<Int, String> = mapOf(
        0 to "Auto",
        1 to "Lumière du jour",
        2 to "Nuageux",
        3 to "Tungstène",
        4 to "Fluorescent",
        5 to "Flash",
        6 to "Personnalisée",
        8 to "Ombre",
        9 to "Température K",
        23 to "Auto (blanc)",
    )

    /** Libellé affiché pour [value] de la propriété [prop]. Valeur inconnue → hexa, jamais d'exception. */
    fun label(prop: Int, value: Int): String {
        val text = when (prop) {
            CanonProp.ISO -> iso[value]
            CanonProp.SHUTTER_SPEED -> shutter[value]
            CanonProp.APERTURE -> when (value) {
                0xFF -> "—"
                else -> aperture[value]?.let { "f/$it" }
            }
            CanonProp.WHITE_BALANCE -> whiteBalance[value]
            else -> null
        }
        return text ?: "0x%02X".format(value)
    }

    private val majorShutter = setOf(
        0x10, 0x18, 0x20, 0x28, 0x30, 0x38, 0x40, 0x48, 0x50, 0x58,
        0x60, 0x68, 0x70, 0x78, 0x80, 0x88, 0x90, 0x98, 0xA0,
    )
    private val majorAperture = setOf(
        0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38, 0x40, 0x48, 0x50, 0x58, 0x60, 0x68, 0x70,
    )
    private val majorIso = setOf(0x00, 0x48, 0x50, 0x58, 0x60, 0x68, 0x70, 0x78, 0x80, 0x88, 0x90)

    /** true si [value] est un cran « principal » (stop entier) de [prop] : points du curseur. */
    fun isMajor(prop: Int, value: Int): Boolean = when (prop) {
        CanonProp.SHUTTER_SPEED -> value in majorShutter
        CanonProp.APERTURE -> value in majorAperture
        CanonProp.ISO -> value in majorIso
        else -> false
    }

    /** Payload SetDevicePropValue : longueur 12, puis propriété et valeur en u32 little-endian. */
    fun setPropPayload(prop: Int, value: Int): ByteArray {
        val w = ByteWriter()
        w.u32(12)
        w.u32(prop)
        w.u32(value)
        return w.toByteArray()
    }
}
