package com.example.ninumao.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.example.ninumao.NinumaoApp
import com.example.ninumao.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// EditUidGuidedStepFragment 使用遥控器输入博主 UID。
class EditUidGuidedStepFragment : GuidedStepSupportFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings_edit_uid),
            getString(R.string.settings_uid),
            "",
            null,
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val app = requireActivity().application as NinumaoApp
        val uid = runBlocking { app.configRepository.getConfig().uid }
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_UID)
                .title(getString(R.string.settings_uid))
                .description(if (uid.isBlank()) "未设置" else uid)
                .editDescription(uid.ifBlank { "" })
                .build(),
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SAVE)
                .title(getString(R.string.settings_save))
                .build(),
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ACTION_SAVE) {
            return
        }
        val uid = findActionById(ACTION_UID)?.description?.toString()?.trim().orEmpty()
        if (uid.isBlank()) {
            Toast.makeText(requireContext(), R.string.error_no_uid, Toast.LENGTH_SHORT).show()
            return
        }
        val app = requireActivity().application as NinumaoApp
        lifecycleScope.launch {
            app.configRepository.updateUid(uid)
            app.notifyConfigUpdated()
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
    }

    companion object {
        private const val ACTION_UID = 1L
        private const val ACTION_SAVE = 2L
    }
}
