package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.Node
import com.savemy.singboxfork.vpn.BoxVpnService
import kotlinx.coroutines.flow.StateFlow

/**
 * Единственный контракт ядра для экранов, службы и воркеров: ни UI, ни WorkManager не зависят
 * от конкретного Engine и libbox.
 */
interface CoreController {
    val state: StateFlow<ConnectionState>
    val checking: StateFlow<Boolean>
    val check: StateFlow<CheckProgress?>
    val results: StateFlow<Map<String, NodeResult>>
    suspend fun connect(vpn: BoxVpnService)
    suspend fun disconnect(owner: BoxVpnService? = null)
    suspend fun checkAll(id: String? = null): List<Node>
    suspend fun applySettings()
    fun message(text: String)
}
