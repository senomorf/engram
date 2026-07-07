package photos.engram.app

import android.content.Context

/**
 * Manual dependency container: one place wires the app. A DI framework buys
 * nothing at this size and costs a compiler plugin (design principle: simplest
 * working solution).
 */
class AppContainer(
    @Suppress("unused") private val context: Context,
)
