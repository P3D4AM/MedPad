package com.medpad.tracker.ui

import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.textfield.TextInputEditText

/**
 * EditText where pasting (and selection menu copy/cut) is disabled —
 * required for the "متن چک‌اوت ستاره‌دار" rule: the report text must be typed.
 */
class NoPasteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextInputEditText(context, attrs) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        return when (id) {
            android.R.id.paste,
            android.R.id.pasteAsPlainText -> {
                // paste blocked — show a gentle shake/haptic instead
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                false
            }
            else -> super.onTextContextMenuItem(id)
        }
    }

    override fun setCustomSelectionActionModeCallback(callback: ActionMode.Callback?) {
        // strip copy/cut/paste from the selection toolbar
        val stripped = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                if (callback?.onCreateActionMode(mode, menu) == true) {
                    menu?.removeItem(android.R.id.copy)
                    menu?.removeItem(android.R.id.cut)
                    menu?.removeItem(android.R.id.paste)
                    return menu != null && menu.size() > 0
                }
                return false
            }
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) =
                callback?.onPrepareActionMode(mode, menu) ?: false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) =
                callback?.onActionItemClicked(mode, item) ?: false
            override fun onDestroyActionMode(mode: ActionMode?) {
                callback?.onDestroyActionMode(mode)
            }
        }
        super.setCustomSelectionActionModeCallback(stripped)
    }
}
