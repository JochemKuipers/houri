package mihon.app.di

import upscale.UpscaleController

/**
 * Offline stub for check.sh: the graph-hook surface UpscaleReaderBridge and the WebGPU config
 * resolve through. The real globalAppGraph is set in App.onCreate. Never compiled into the app.
 */
object AppGraphStub {
    lateinit var upscaleController: UpscaleController
}

val globalAppGraph: AppGraphStub get() = AppGraphStub
