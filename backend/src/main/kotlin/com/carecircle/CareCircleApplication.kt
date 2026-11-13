package com.carecircle

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class CareCircleApplication

fun main(args: Array<String>) {
    runApplication<CareCircleApplication>(*args)
}
