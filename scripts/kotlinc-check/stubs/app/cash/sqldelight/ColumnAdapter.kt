package app.cash.sqldelight

/**
 * Harness-only stand-in for SQLDelight's ColumnAdapter interface, matching the real
 * runtime signature. The stub is never compiled into the app; it exists so an adapter
 * object (whose behaviour IS the subject under test) can run on a plain JVM.
 */
interface ColumnAdapter<T : Any, R : Any> {
    fun decode(databaseValue: R): T
    fun encode(value: T): R
}
