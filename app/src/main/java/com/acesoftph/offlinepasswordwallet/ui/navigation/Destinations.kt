package com.acesoftph.offlinepasswordwallet.ui.navigation

/** Central list of navigation routes. */
object Dest {
    const val SETUP = "setup"
    const val UNLOCK = "unlock"
    const val RECOVERY = "recovery"

    const val LIST = "list"
    const val DETAIL = "detail/{entryId}"
    const val EDIT = "edit/{entryId}" // entryId == "new" to create

    const val GENERATOR = "generator"

    const val SETTINGS = "settings"
    const val CHANGE_MASTER = "change_master"
    const val CHANGE_ANSWERS = "change_answers"

    const val IMPORT_CSV = "import_csv"
    const val EXPORT_CSV = "export_csv"
    const val EXPORT_BACKUP = "export_backup"
    const val RESTORE_BACKUP = "restore_backup"

    fun detail(entryId: String) = "detail/$entryId"
    fun edit(entryId: String) = "edit/$entryId"
    const val NEW_ENTRY_ID = "new"
}
