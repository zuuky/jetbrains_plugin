package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SweepProjectService : Disposable {
    override fun dispose() {
        // Nothing to do - just exists for lifecycle management
    }

    companion object {
        fun getInstance(project: Project): SweepProjectService = project.getService(SweepProjectService::class.java)
    }
}
