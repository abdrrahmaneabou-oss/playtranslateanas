package com.playtranslate.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.playtranslate.R
import com.playtranslate.language.LanguageBackupManager
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.launch

/** Export into the user-granted SAF folder, or restore from the user's own
 *  chosen ZIP. Works even before the language onboarding is complete. */
class LanguageBackupActivity : SettingsSubPageActivity() {
    override val layoutResId = R.layout.activity_language_backup

    private var pendingLanguage = SourceLangId.EN
    private var busy = false

    private val chooseFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            if (LanguageBackupManager.grantFolder(this, uri)) {
                updateFolderLabel()
                report(getString(R.string.backup_folder_saved))
            } else {
                report(getString(R.string.backup_folder_failed))
            }
        }
    }

    private val chooseImport = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) restore(pendingLanguage, uri)
    }

    override fun onContentCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState?.getString("restore_language") == "ar") {
            pendingLanguage = SourceLangId.AR
        }
        findViewById<View>(R.id.btnBackupChooseFolder).setOnClickListener {
            chooseFolder.launch(null)
        }
        findViewById<View>(R.id.btnBackupSaveEnglish).setOnClickListener {
            save(SourceLangId.EN)
        }
        findViewById<View>(R.id.btnBackupSaveArabic).setOnClickListener {
            save(SourceLangId.AR)
        }
        findViewById<View>(R.id.btnBackupRestoreEnglish).setOnClickListener {
            pendingLanguage = SourceLangId.EN
            chooseImport.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        findViewById<View>(R.id.btnBackupRestoreArabic).setOnClickListener {
            pendingLanguage = SourceLangId.AR
            chooseImport.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        updateFolderLabel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("restore_language", pendingLanguage.code)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        updateFolderLabel()
    }

    private fun updateFolderLabel() {
        val label = LanguageBackupManager.selectedFolder(this)?.lastPathSegment
            ?: getString(R.string.backup_no_folder)
        findViewById<TextView>(R.id.tvBackupFolder).text = label
    }

    private fun save(language: SourceLangId) {
        if (busy) return
        busy = true
        report(getString(R.string.backup_working))
        lifecycleScope.launch {
            try {
                val file = LanguageBackupManager.exportNow(this@LanguageBackupActivity, language)
                report(getString(R.string.backup_saved_as, file))
            } catch (e: Exception) {
                report(getString(R.string.backup_failed_reason, e.message ?: "Unknown error"))
            } finally {
                busy = false
            }
        }
    }

    private fun restore(language: SourceLangId, uri: Uri) {
        if (busy) return
        busy = true
        report(getString(R.string.backup_restoring))
        lifecycleScope.launch {
            try {
                val message = LanguageBackupManager.restore(this@LanguageBackupActivity, language, uri)
                report(message)
            } catch (e: Exception) {
                report(getString(R.string.backup_failed_reason, e.message ?: "Invalid backup"))
            } finally {
                busy = false
            }
        }
    }

    private fun report(message: String) {
        findViewById<TextView>(R.id.tvBackupStatus)?.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}