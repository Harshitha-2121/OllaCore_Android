package com.ollacore.app.data.util

import java.util.UUID

object MessageIdGenerator {
    fun generate(): String = "local-${UUID.randomUUID()}"
}
