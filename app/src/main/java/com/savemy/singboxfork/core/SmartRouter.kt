package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckStatus
import com.savemy.singboxfork.data.Node

/** Решение Smart Routing без побочных эффектов: что делать после неудачной проверки активного узла. */
object SmartRouter {
    /**
     * null — активный узел работает или запасных нет.
     * Иначе — самый быстрый проверенный узел со статусом ALIVE.
     */
    fun bestFallback(active: Node?, alternatives: List<Node>): Node? {
        if (active?.latencyMs != null) return null
        return alternatives
            .filter { it.status == CheckStatus.ALIVE && it.latencyMs != null }
            .minByOrNull { it.latencyMs!! }
    }
}
