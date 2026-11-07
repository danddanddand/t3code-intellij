package io.github.danddanddand.t3code

import com.intellij.openapi.diagnostic.thisLogger
import org.cef.CefApp
import java.util.concurrent.ThreadPoolExecutor

/**
 * Works around JBR-10027 without restarting anything.
 *
 * Out-of-process JCEF serves callbacks from cef_server on a `ThreadPoolExecutor(core 3, max 10, keepAlive 60 s)`
 * whose thread factory reports *any* worker thread ending as "java-client is disconnected", which shuts the whole
 * Chromium stack down. Once cef_server has opened more than three handler connections, the extra worker goes idle,
 * expires after 60 s and kills every embedded browser in the IDE, about two minutes after the first page opens.
 *
 * Raising the core pool size to the maximum means worker threads never time out, so that false disconnect never
 * fires. Idle threads already waiting are also kept, because the pool re-checks the core size before letting one go.
 * The fields are private to JCEF, so everything here is best effort: on a build where they change, it logs once
 * and the plugin falls back to relaunching JCEF when cef_server dies.
 */
object T3CodeJcefKeepAlive {
    @Volatile private var patched: ThreadPoolExecutor? = null
    @Volatile private var failureLogged = false

    /** True when the current cef_server's handler pool no longer expires idle threads. */
    val isActive: Boolean get() = patched?.let { !it.isShutdown } == true

    /** Idempotent; cheap enough to call on every health tick so a relaunched cef_server gets patched too. */
    fun apply() {
        val pool = runCatching { handlersPool() }.getOrElse { logFailure(it); return } ?: return
        if (pool === patched) return
        if (pool.corePoolSize < pool.maximumPoolSize) pool.corePoolSize = pool.maximumPoolSize
        patched = pool
        thisLogger().info("JBR-10027 workaround applied: JCEF handler pool core size ${pool.corePoolSize}, idle threads no longer expire")
    }

    /** Null while JCEF is in-process or not started; throws when JCEF's internals no longer match. */
    private fun handlersPool(): ThreadPoolExecutor? {
        val server = CefApp.getInstanceIfAny()?.server ?: return null
        val handlersServer = readField(server, "myClientHandlersServer") ?: return null
        return readField(handlersServer, "executorService_") as? ThreadPoolExecutor
            ?: error("JCEF handler executor is not a ThreadPoolExecutor")
    }

    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let { field ->
                field.isAccessible = true
                return field.get(target)
            }
            type = type.superclass
        }
        error("Field $name not found on ${target.javaClass.name}")
    }

    private fun logFailure(e: Throwable) {
        if (failureLogged) return
        failureLogged = true
        thisLogger().warn("JBR-10027 workaround unavailable on this IDE build; falling back to relaunching JCEF", e)
    }
}
