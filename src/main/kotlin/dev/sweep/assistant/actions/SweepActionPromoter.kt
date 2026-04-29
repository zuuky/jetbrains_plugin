package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.components.AcceptCodeBlockAction
import dev.sweep.assistant.components.AcceptFileAction
import dev.sweep.assistant.components.RejectCodeBlockAction
import dev.sweep.assistant.components.RejectFileAction
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.actions.AcceptCodeBlockAction as NewAcceptCodeBlockAction
import dev.sweep.assistant.actions.RejectCodeBlockAction as NewRejectCodeBlockAction

class SweepActionPromoter : ActionPromoter {
    override fun promote(
        actions: List<AnAction>,
        context: DataContext,
    ): List<AnAction> {
        val project = context.getData(CommonDataKeys.PROJECT) ?: return actions
        val editor = context.getData(CommonDataKeys.EDITOR)

        // Priority 1: Autocomplete actions (when completion is shown)
        // This must be checked FIRST because autocomplete is time-sensitive and ephemeral
        val settings =
            runCatching {
                ApplicationManager.getApplication().getServiceIfCreated(SweepSettings::class.java)
            }.getOrNull()

        if (editor != null && settings?.nextEditPredictionFlagOn == true) {
            val tracker =
                runCatching {
                    project.getServiceIfCreated(RecentEditsTracker::class.java)
                }.getOrNull()
            if (tracker?.isCompletionShown == true) {
                // Promote autocomplete accept/reject actions with highest priority
                val autocompleteActions =
                    actions.filter { action ->
                        action is AcceptEditCompletionAction || action is RejectEditCompletionAction
                    }
                if (autocompleteActions.isNotEmpty()) {
                    return autocompleteActions
                }
            }
        }

        // Priority 2: Code block actions (when applied blocks exist)
        val manager =
            runCatching {
                project.getServiceIfCreated(AppliedCodeBlockManager::class.java)
            }.getOrNull()
        if (manager?.getTotalAppliedBlocksForCurrentFile()?.isNotEmpty() == true) {
            val sweepActions = mutableListOf<AnAction>()
            val otherActions = mutableListOf<AnAction>()

            actions.forEach { action ->
                when (action) {
                    is AcceptFileAction, is RejectFileAction, is AcceptCodeBlockAction, is RejectCodeBlockAction,
                    is NewAcceptCodeBlockAction, is NewRejectCodeBlockAction,
                    -> {
                        sweepActions.add(action)
                    }

                    else -> {
                        otherActions.add(action)
                    }
                }
            }

            // Return Sweep actions first, then other actions in their original order
            return sweepActions + otherActions
        }

        // Priority 3: New chat action (when Sweep tool window is focused)
        // This allows Cmd+N to create a new chat instead of showing IntelliJ's "New..." popup
        if (SweepNewChatAction.isSweepToolWindowFocused(project)) {
            val newChatAction = actions.filterIsInstance<SweepNewChatAction>()
            if (newChatAction.isNotEmpty()) {
                return newChatAction + actions.filter { it !is SweepNewChatAction }
            }
        }

        // No promotion needed
        return actions
    }
}
