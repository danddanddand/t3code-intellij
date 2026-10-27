package io.github.danddanddand.t3code

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.thisLogger
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Runs `t3 pair` and extracts the pairing URL so the embedded browser can
 * complete the handshake without the user copying it from a terminal.
 */
object T3Pairing {
    sealed interface Result {
        data class Paired(val url: String) : Result
        data class Failed(val message: String) : Result
    }

    fun pair(settings: T3CodeSettings): Result {
        val cli = resolveCli(settings.cliPath) ?: return Result.Failed(
            "Could not find the t3 CLI (looked for '${settings.cliPath}' on PATH and in ~/.local/bin). " +
                "Set the path under Settings, Tools, T3 Code."
        )

        val command = GeneralCommandLine(cli, "pair", "--ttl", settings.pairTtl, "--label", "intellij")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val output = try {
            ExecUtil.execAndGetOutput(command, 30_000)
        } catch (e: Exception) {
            thisLogger().warn("t3 pair failed to launch", e)
            return Result.Failed("Failed to run '$cli pair': ${e.message}")
        }

        if (output.isTimeout) return Result.Failed("'t3 pair' did not finish within 30 seconds.")
        if (output.exitCode != 0) {
            return Result.Failed("'t3 pair' exited with code ${output.exitCode}:\n${output.stderr.ifBlank { output.stdout }.trim()}")
        }

        val url = PAIRING_URL.find(output.stdout)?.groupValues?.get(1)
            ?: return Result.Failed("'t3 pair' succeeded but printed no pairing URL:\n${output.stdout.trim()}")

        // t3 prints the LAN address; the loopback host from settings is the one the IDE should pair against.
        return Result.Paired(rewriteHost(url, settings.serverUrl))
    }

    private fun resolveCli(configured: String): String? {
        val direct = Path.of(configured.replaceFirst("~", System.getProperty("user.home")))
        if (direct.isAbsolute && direct.exists()) return direct.toString()

        val home = Path.of(System.getProperty("user.home"))
        val pathDirs = (System.getenv("PATH") ?: "").split(':').map(Path::of) + listOf(home.resolve(".local/bin"))
        return pathDirs.map { it.resolve(configured) }.firstOrNull { Files.isExecutable(it) }?.toString()
    }

    private fun rewriteHost(pairingUrl: String, serverUrl: String): String {
        val pairing = runCatching { URI(pairingUrl) }.getOrNull() ?: return pairingUrl
        val server = runCatching { URI(serverUrl) }.getOrNull() ?: return pairingUrl
        if (server.host.isNullOrBlank()) return pairingUrl
        return URI(server.scheme, null, server.host, server.port, pairing.path, pairing.query, pairing.fragment).toString()
    }

    private val PAIRING_URL = Regex("""Pairing URL:\s*(\S+)""")
}
