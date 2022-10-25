package org.kotlinlsp

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.logging.Level
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.kotlinlsp.logging.*
import org.kotlinlsp.lsp4kt.*

private val log by findLogger.atToplevel(object{})

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
        inStream,
        outStream,
        threads) { it }

    loggerTarget.inner = Lsp4jLoggerTarget(launcher.remoteProxy!!)

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}
/**
 * Starts a TCP server socket. Blocks until the first
 * client has connected, then returns a pair of IO streams.
 */
fun tcpStartServer(port: Int): Pair<InputStream, OutputStream> = ServerSocket(port)
    .also { log.info("Waiting for client on port ${port}...") }
    .accept()
    .let { Pair(it.inputStream, it.outputStream) }

/**
 * Starts a TCP client socket and connects to the client at
 * the specified address, then returns a pair of IO streams.
 */
fun tcpConnectToClient(host: String, port: Int): Pair<InputStream, OutputStream> =
    run { log.info("Connecting to client at ${host}:${port}...") }
    .let { Socket(host, port) }
    .let { Pair(it.inputStream, it.outputStream) }
