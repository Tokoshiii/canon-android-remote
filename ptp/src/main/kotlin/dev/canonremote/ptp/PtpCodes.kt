package dev.canonremote.ptp

object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
}

object CanonOp {
    const val SET_DEVICE_PROP_VALUE_EX = 0x9110
    const val SET_REMOTE_MODE = 0x9114
    const val SET_EVENT_MODE = 0x9115
    const val GET_EVENT = 0x9116
    const val REMOTE_RELEASE_ON = 0x9128
    const val REMOTE_RELEASE_OFF = 0x9129
    const val GET_VIEWFINDER_DATA = 0x9153
    const val DO_AF = 0x9154
    const val DRIVE_LENS = 0x9155
}

object PtpResponse {
    const val OK = 0x2001
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E
    const val CANON_NOT_READY = 0xA102
}

object CanonProp {
    const val APERTURE = 0xD101
    const val SHUTTER_SPEED = 0xD102
    const val ISO = 0xD103
    const val WHITE_BALANCE = 0xD109
    const val CAPTURE_DESTINATION = 0xD11C
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    const val EVF_MODE = 0xD1B3
}

/** Values of [CanonProp.CAPTURE_DESTINATION] (libgphoto2: 4 = host RAM; the card value varies by model). */
object CanonCaptureDest {
    const val HOST = 4
}

object CanonEventCode {
    const val PROP_VALUE_CHANGED = 0xC189
    const val AVAILABLE_LIST_CHANGED = 0xC18A
}
