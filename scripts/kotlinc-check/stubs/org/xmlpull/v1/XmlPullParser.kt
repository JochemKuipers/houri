// Offline compile stubs - never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")
package org.xmlpull.v1

import java.io.InputStream
import java.io.Reader

interface XmlPullParser {
    var inputEncoding: String?
    val eventType: Int
    val name: String
    val namespace: String?
    val text: String?
    val depth: Int
    val attributeCount: Int
    fun next(): Int
    fun setInput(input: InputStream, inputEncoding: String?)
    fun setInput(input: Reader)
    fun setFeature(name: String, state: Boolean)
    fun getAttributeName(index: Int): String?
    fun getAttributeValue(namespace: String?, name: String): String?
    fun getAttributeValue(index: Int): String?
    companion object {
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        const val CDSECT = 5
        const val FEATURE_PROCESS_NAMESPACES = "http://xmlpull.org/v1/doc/features.html#process-namespaces"
        const val FEATURE_PROCESS_DOCDECL = "http://xmlpull.org/v1/doc/features.html#process-docdecl"
    }
}
