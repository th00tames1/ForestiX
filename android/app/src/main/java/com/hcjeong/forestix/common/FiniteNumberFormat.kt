package com.hcjeong.forestix.common

import java.util.Locale

/** Display unavailable results explicitly; CSV uses an empty field instead. */
fun finiteNumberFormat(locale: Locale, format: String, vararg arguments: Any): String {
    if (arguments.any { (it is Double && !it.isFinite()) || (it is Float && !it.isFinite()) })
        return "Unavailable"
    return String.format(locale, format, *arguments)
}
