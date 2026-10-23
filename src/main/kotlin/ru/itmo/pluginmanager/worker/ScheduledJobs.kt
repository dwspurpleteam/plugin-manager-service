package ru.itmo.pluginmanager.worker

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ScheduledJobs(private val jobs: BackgroundJobs) {
    @Scheduled(
        every = "\${plugin-manager.worker.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun activate() = jobs.runTasks()

    @Scheduled(every = "\${plugin-manager.cleanup.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun cleanup() = jobs.cleanUp()
}
