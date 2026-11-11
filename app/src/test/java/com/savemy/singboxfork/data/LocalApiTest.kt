package com.savemy.singboxfork.data

import com.savemy.singboxfork.core.LocalApi
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocalApiTest {
    private fun respond(response:String,action:(LocalApi)->Unit):String {
        val listener=ServerSocket(0);var request="";var failure:Throwable?=null
        val worker=thread {
            try { listener.accept().use { socket ->
                socket.soTimeout=2000
                val reader=socket.getInputStream().bufferedReader()
                val headers=mutableListOf<String>()
                while(true) { val line=reader.readLine() ?: break;if(line.isEmpty()) break;headers+=line }
                request=headers.joinToString("\n")
                socket.getOutputStream().write(response.toByteArray())
            } } catch(e:Throwable) { failure=e }
        }
        try { action(LocalApi(listener.localPort,"test-key")) } finally { worker.join(3000);listener.close() }
        failure?.let { throw it };return request
    }
    @Test fun realHttpDelayAndAuthentication() {
        val body="{\"delay\":42}"
        val request=respond("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body") { api -> assertEquals(body,api.delay("node-id")) }
        assertTrue(request.startsWith("GET /proxies/node-id/delay?"));assertTrue(request.contains("Authorization: Bearer test-key"))
    }
    @Test fun chunkedResponse() {
        respond("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nc\r\n{\"delay\":42}\r\n0\r\n\r\n") { assertEquals("{\"delay\":42}",it.delay("node")) }
    }
    @Test fun failedProxyNotReportedWorking() {
        respond("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n") { api -> assertThrows(IllegalStateException::class.java) { api.delay("node") } }
    }
    @Test fun cancelClosesPendingSocket() {
        ServerSocket(0).use { listener ->
            val accepted=CountDownLatch(1);val finished=CountDownLatch(1)
            val server=thread { listener.accept().use { socket -> accepted.countDown();finished.await(3,TimeUnit.SECONDS) } }
            val api=LocalApi(listener.localPort,"secret")
            val client=thread { try { api.delay("node") } catch(_:Exception) {} finally { finished.countDown() } }
            assertTrue(accepted.await(2,TimeUnit.SECONDS));api.cancelPending()
            assertTrue("Остановка не должна ждать сетевого тайм-аута",finished.await(1,TimeUnit.SECONDS))
            client.join(2000);server.join(2000)
        }
    }
}
