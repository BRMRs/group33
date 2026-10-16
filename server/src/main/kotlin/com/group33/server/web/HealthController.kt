package com.group33.server.web

import com.group33.shared.model.HealthResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {
    @GetMapping("/health")
    fun health(): HealthResponse = HealthResponse(
        status = "ok",
        service = "group33-server",
    )
}
