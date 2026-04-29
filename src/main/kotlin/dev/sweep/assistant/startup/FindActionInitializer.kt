package dev.sweep.assistant.startup

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sweep.assistant.views.CodeBlockDisplay
import dev.sweep.assistant.views.MarkdownDisplay
import java.awt.Component
import java.awt.Container

class FindActionInitializer : ProjectActivity {
    override suspend fun execute(project: Project) {
        // CRITICAL FIX: ProjectActivity.execute() runs on EDT. EditorActionManager.getInstance()
        // is a getService() call that must run off EDT to avoid flushNow deadlock.
        ApplicationManager.getApplication().executeOnPooledThread {
            // Wait for IDE startup to fully complete
            Thread.sleep(3000) // Increased from 2000ms to handle slower startup
            doFindActionSetup()
        }
    }

    private fun doFindActionSetup() {
        // setActionHandler is not thread-safe — must run on EDT
        ApplicationManager.getApplication().invokeLater {
            val editorActionManager = EditorActionManager.getInstance()
            val originalFindHandler = editorActionManager.getActionHandler(IdeActions.ACTION_FIND)

            editorActionManager.setActionHandler(
                IdeActions.ACTION_FIND,
                object : EditorActionHandler() {
                    override fun doExecute(
                        editor: Editor,
                        caret: Caret?,
                        dataContext: DataContext?,
                    ) {
                        var component: Component? = editor.component
                        var isInCodeBlock = false
                        var markdownDisplay: MarkdownDisplay? = null

                        while (component != null) {
                            when (component) {
                                is CodeBlockDisplay -> isInCodeBlock = true
                                is MarkdownDisplay -> {
                                    markdownDisplay = component
                                    break
                                }
                            }
                            component = component.parent
                        }

                        if (isInCodeBlock && markdownDisplay != null) {
                            val allDisplays = findAllMarkdownDisplays(markdownDisplay)
                            markdownDisplay.showFindDialog(allDisplays)
                            return
                        }

                        originalFindHandler.execute(editor, caret, dataContext)
                    }

                    private fun findAllMarkdownDisplays(markdownDisplay: MarkdownDisplay): List<MarkdownDisplay> {
                        var component: Component? = markdownDisplay.parent

                        while (component != null) {
                            if (component is Container) {
                                val displays = collectMarkdownDisplays(component)
                                if (displays.size > 1 || (displays.size == 1 && displays.contains(markdownDisplay))) {
                                    val parent = component.parent
                                    if (parent is Container) {
                                        val parentDisplays = collectMarkdownDisplays(parent)
                                        if (parentDisplays.size >= displays.size) {
                                            component = parent
                                            continue
                                        }
                                    }
                                    return displays
                                }
                            }
                            component = component.parent
                        }

                        return listOf(markdownDisplay)
                    }

                    private fun collectMarkdownDisplays(container: Container): List<MarkdownDisplay> {
                        val displays = mutableListOf<MarkdownDisplay>()
                        for (i in 0 until container.componentCount) {
                            val component = container.getComponent(i)
                            if (component is MarkdownDisplay) {
                                displays.add(component)
                            }
                        }
                        return displays
                    }
                },
            )
        } // invokeLater
    }
}
