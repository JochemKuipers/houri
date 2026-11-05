package exh.log

/**
 * Offline stub for check.sh: mirrors the XLog wrapper surface in
 * `core/common/src/main/kotlin/exh/log/Logging.kt` (extension functions on Any).
 */
fun Any.xLogD(log: String) { }
fun Any.xLogW(log: String) { }
fun Any.xLogE(log: String) { }
fun Any.xLogI(log: String) { }

fun Any.xLogD(log: String, e: Throwable) { }
fun Any.xLogW(log: String, e: Throwable) { }
fun Any.xLogE(log: String, e: Throwable) { }
fun Any.xLogI(log: String, e: Throwable) { }
