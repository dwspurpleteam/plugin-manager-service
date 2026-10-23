package ru.itmo.pluginmanager.config

import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

@Singleton
class RuntimeBeans {
    @Produces
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}
