package com.livetranslate.headphones.glasses.vendor

/**
 * Bridges [OudmonConnectionReceiver] (LBM / main thread) to the active [OudmonGlassesBleClient]
 * connection listener without importing repository types into the receiver.
 */
internal object OudmonConnectionHub {
    @Volatile
    private var listener: ((Boolean) -> Unit)? = null

    fun setListener(onChanged: ((Boolean) -> Unit)?) {
        listener = onChanged
    }

    fun onConnectionChanged(connected: Boolean) {
        listener?.invoke(connected)
    }
}
