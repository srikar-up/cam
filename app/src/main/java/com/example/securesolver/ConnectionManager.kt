package com.example.securesolver

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConnectionManager {
    private val selector = ActorSelectorManager(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    // Starts server socket to listen for incoming connections
    suspend fun startServer(port: Int, onMessageReceived: suspend (Socket, String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            serverSocket = aSocket(selector).tcp().bind("0.0.0.0", port)
            while (true) {
                val socket = serverSocket?.accept() ?: break
                handleClientConnection(socket, onMessageReceived)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun handleClientConnection(socket: Socket, onMessageReceived: suspend (Socket, String) -> Unit) = withContext(Dispatchers.IO) {
        val receiveChannel = socket.openReadChannel()
        try {
            while (true) {
                val line = receiveChannel.readUTF8Line() ?: break
                onMessageReceived(socket, line)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
        }
    }

    // Connects client to server
    suspend fun connectToServer(ip: String, port: Int): Socket = withContext(Dispatchers.IO) {
        val socket = aSocket(selector).tcp().connect(ip, port)
        clientSocket = socket
        socket
    }

    // Sends message from client to server or vice versa
    suspend fun sendMessage(socket: Socket, message: String) = withContext(Dispatchers.IO) {
        val writeChannel = socket.openWriteChannel(autoFlush = true)
        writeChannel.writeStringUtf8(message + "\n")
    }

    // Sends binary image data
    suspend fun sendImage(socket: Socket, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val writeChannel = socket.openWriteChannel(autoFlush = true)
        writeChannel.writeStringUtf8("IMAGE_START\n")
        writeChannel.writeStringUtf8("${bytes.size}\n")
        writeChannel.writeFully(bytes, 0, bytes.size)
    }

    // Receives binary image data
    suspend fun receiveImage(socket: Socket): ByteArray = withContext(Dispatchers.IO) {
        val receiveChannel = socket.openReadChannel()
        val sizeLine = receiveChannel.readUTF8Line() ?: throw Exception("Failed to read image size")
        val size = sizeLine.toInt()
        val buffer = ByteArray(size)
        receiveChannel.readFully(buffer, 0, size)
        buffer
    }

    fun close() {
        try {
            serverSocket?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
