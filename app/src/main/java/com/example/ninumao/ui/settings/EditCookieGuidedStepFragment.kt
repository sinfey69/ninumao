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

// EditCookieGuidedStepFragment 使用遥控器输入微博 Cookie。
class EditCookieGuidedStepFragment : GuidedStepSupportFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings_edit_cookie),
            getString(R.string.settings_cookie),
            "",
            null,
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val app = requireActivity().application as NinumaoApp
        val cookie = runBlocking { app.configRepository.getConfig().cookie }
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_COOKIE)
                .title(getString(R.string.settings_cookie))
                .description(if (cookie.isBlank()) "可选" else "已设置，点击进入编辑")
                .editDescription(if (cookie.isBlank()) "" else cookie)
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
        val cookie = findActionById(ACTION_COOKIE)?.description?.toString()?.trim().orEmpty()
        val normalizedCookie = if (cookie == "可选") "" else cookie
        val app = requireActivity().application as NinumaoApp
        lifecycleScope.launch {
            app.configRepository.updateCookie(normalizedCookie)
            app.notifyConfigUpdated()
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
    }

    companion object {
        private const val ACTION_COOKIE = 1L
        private const val ACTION_SAVE = 2L
    }
}
