package mihon.core.navigation

/**
 * Marker for routes that can provide an assist URL (for `onProvideAssistContent`).
 * The current topmost route implementing this interface decides what URL is shared,
 * via [mihon.core.navigation.util.AssistContentManager].
 */
interface AssistContentRoute
