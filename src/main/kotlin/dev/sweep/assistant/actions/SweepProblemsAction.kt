package dev.sweep.assistant.actions

import com.intellij.analysis.problemsView.toolWindow.ProblemNode
import com.intellij.analysis.problemsView.toolWindow.ProblemsView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.tree.TreeUtil
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.SweepConstants

class SweepProblemsAction : AnAction() {
    init {
        templatePresentation.apply {
            text = "Fix in Sweep Chat"
            description = "Let Sweep handle the fix"
            icon = SweepIcons.Sweep16x16
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val selectedPanel = ProblemsView.getSelectedPanel(project) ?: return
        val tree = selectedPanel.tree
        val selectionPath = tree.selectionPath ?: return
        val problemNode = TreeUtil.getLastUserObject(ProblemNode::class.java, selectionPath) ?: return
        // Extract problem information using reflection for platform compatibility
        val message = getProblemNodeText(problemNode)
        val file = problemNode.file
        val document = file.findDocument() ?: return

        val line = getProblemNodeLine(problemNode)
        // Get context with +/- 1 line, handling bounds
        val startLine = maxOf(0, line - 1)
        val endLine = minOf(document.lineCount - 1, line + 1)
        val context =
            document.charsSequence
                .subSequence(
                    document.getLineStartOffset(startLine),
                    document.getLineEndOffset(endLine),
                ).toString()

        // open/append to sweep chat
        val fixErrorPrompt = "\nFor following code:\n```\n$context\n```\nWe have the error: $message\nFix this.\n"

        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
            if (toolWindow?.isVisible == false) {
                toolWindow.show(null)
            }

            val chatComponent = ChatComponent.getInstance(project)
            chatComponent.appendToTextField(fixErrorPrompt)
            chatComponent.requestFocus()
        }
    }

    override fun update(e: AnActionEvent) {
        e.project?.let { project ->
            ProblemsView.getSelectedPanel(project)?.tree?.selectionPath?.let { path ->
                TreeUtil.getLastUserObject(ProblemNode::class.java, path)?.let { node ->
                    e.presentation.isVisible = getProblemNodeSeverity(node) >= 400
                    return
                }
            }
        }

        e.presentation.isVisible = false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        private fun getProblemNodeText(node: ProblemNode): String {
            return try {
                val field = ProblemNode::class.java.getDeclaredField("text")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                field.get(node) as String
            } catch (e: Exception) {
                node.toString()
            }
        }

        private fun getProblemNodeLine(node: ProblemNode): Int {
            return try {
                val field = ProblemNode::class.java.getDeclaredField("line")
                field.isAccessible = true
                field.getInt(node)
            } catch (e: Exception) {
                0
            }
        }

        private fun getProblemNodeSeverity(node: ProblemNode): Int {
            return try {
                val field = ProblemNode::class.java.getDeclaredField("severity")
                field.isAccessible = true
                field.getInt(node)
            } catch (e: Exception) {
                0
            }
        }
    }
}
