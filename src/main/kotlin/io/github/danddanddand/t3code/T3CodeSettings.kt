package io.github.danddanddand.t3code

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "io.github.danddanddand.t3code.Settings", storages = [Storage("T3Code.xml")])
class T3CodeSettings : PersistentStateComponent<T3CodeSettings> {
    var serverUrl = "http://127.0.0.1:3773/"
    var cliPath = "t3"
    var pairTtl = "5m"
    var healthCheckIntervalSeconds = 10
    var healthCheckTimeoutSeconds = 30
    var notifyOnFailure = true
    var diagnosticLogging = true
    var ignoreJcefModeAdvice = false

    override fun getState() = this

    override fun loadState(state: T3CodeSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: T3CodeSettings
            get() = ApplicationManager.getApplication().getService(T3CodeSettings::class.java)
    }
}
