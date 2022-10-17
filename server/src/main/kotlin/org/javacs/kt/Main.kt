package org.javacs.kt

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter

import java.util.concurrent.Executors

import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.*
import java.util.logging.Level

import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture

import org.javacs.kt.util.ExitingInputStream
import org.javacs.kt.util.tcpStartServer
import org.javacs.kt.util.tcpConnectToClient
import org.javacs.kt.logging.*
import org.javacs.kt.lsp4kt.*

class Args {
    /*
     * The language server can currently be launched in three modes:
     *  - Stdio, in which case no argument should be specified (used by default)
     *  - TCP Server, in which case the client has to connect to the specified tcpServerPort (used by the Docker image)
     *  - TCP Client, in whcih case the server will connect to the specified tcpClientPort/tcpClientHost (optionally used by VSCode)
     */

    @Parameter(names = ["--tcpServerPort", "-sp"])
    var tcpServerPort: Int? = null
    @Parameter(names = ["--tcpClientPort", "-p"])
    var tcpClientPort: Int? = null
    @Parameter(names = ["--tcpClientHost", "-h"])
    var tcpClientHost: String = "localhost"
}

fun main(argv: Array<String>) {
    // Redirect java.util.logging calls (e.g. from LSP4J)
    val loggerTarget = DelegateLoggerTarget(QueueLoggerTarget())
    LoggerTargetJulHandler.install(loggerTarget)

    val args = Args().also { JCommander.newBuilder().addObject(it).build().parse(*argv) }
    val (inStream, outStream) = args.tcpClientPort?.let {
        // Launch as TCP Client
        loggerTarget.inner=FunctionLoggerTarget{ println(it.message) }
        tcpConnectToClient(args.tcpClientHost, it)
    } ?: args.tcpServerPort?.let {
        // Launch as TCP Server
        loggerTarget.inner=FunctionLoggerTarget{ println(it.message) }
        tcpStartServer(it)
    } ?: Pair(System.`in`, System.out)

    val scope = CoroutineScope(
        Dispatchers.Default
        + CoroutineName("kotlin-lsp-worker")
        + Job())

    val server = KotlinLanguageServer(scope)
    val threads = Executors.newSingleThreadExecutor { Thread(it, "client") }
    val launcher = LSPLauncher.createServerLauncher(
        server.asLsp4j(scope),
        ExitingInputStream(inStream),
        outStream,
        threads) { it }

    loggerTarget.inner = Lsp4jLoggerTarget(launcher.remoteProxy!!)

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}
