package com.group33.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MedicationReminderServerApplication

fun main(args: Array<String>) {
    runApplication<MedicationReminderServerApplication>(*args)
}
