package dev.sweep.assistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Tracks changes made by the (removed) agent tools.
 *
 * In this local build there is no agent, so nothing ever records a change and
 * [wasLastChangeByAgent] always returns false. The service is kept so that
 * autocomplete listeners keep their existing guard logic.
 */
@Service(Service.Level.PROJECT)
class AgentChangeTrackingService {
    companion object {
        fun getInstance(project: Project): AgentChangeTrackingService = project.getService(AgentChangeTrackingService::class.java)
    }

    /**
     * No agent exists in this build, so the last change is never considered agent-made.
     */
    fun wasLastChangeByAgent(@Suppress("UNUSED_PARAMETER") lastUserEditTimestamp: Long): Boolean = false
}
