package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.settings.SweepSettings

/**
 * Ensures the accept/reject autocomplete actions take priority when a suggestion is shown
 * and multiple actions are bound to the same key (e.g. TAB).
 */
class SweepActionPromoter : ActionPromoter {
    override fun promote(
        actions: List<AnAction>,
        context: DataContext,
    ): List<AnAction> {
        val project = context.getData(CommonDataKeys.PROJECT) ?: return actions
        val editor = context.getData(CommonDataKeys.EDITOR) ?: return actions

        val settings =
            runCatching {
                ApplicationManager.getApplication().getServiceIfCreated(SweepSettings::class.java)
            }.getOrNull()
        if (settings?.nextEditPredictionFlagOn != true) return actions

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

        return actions
    }
}
