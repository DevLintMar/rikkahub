package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.model.Folder
import java.time.Instant
import kotlin.uuid.Uuid

class FolderRepository(
    private val folderDAO: FolderDAO,
    private val conversationDAO: ConversationDAO,
) {
    fun getFoldersOfAssistant(assistantId: Uuid): Flow<List<Folder>> {
        return folderDAO.getFoldersOfAssistant(assistantId.toString())
            .map { list -> list.map { it.toFolder() } }
    }

    suspend fun getFolderById(id: Uuid): Folder? {
        return folderDAO.getFolderById(id.toString())?.toFolder()
    }

    suspend fun createFolder(assistantId: Uuid, name: String): Folder {
        // sortIndex 排到当前助手文件夹末尾（max + 1），新文件夹出现在最后
        val maxSortIndex = folderDAO.getFoldersOfAssistant(assistantId.toString())
            .maxOfOrNull { it.sortIndex } ?: -1
        val folder = Folder(
            assistantId = assistantId,
            name = name,
            sortIndex = maxSortIndex + 1,
            createAt = Instant.now(),
        )
        folderDAO.insert(folder.toEntity())
        return folder
    }

    suspend fun renameFolder(id: Uuid, name: String) {
        folderDAO.rename(id.toString(), name)
    }

    /**
     * 拖拽重排序：按 [folders] 的新顺序持久化各自 sortIndex（下标即顺序）。
     * 逐条 @Update 在 Room 事务里由调用方（DAO 挂起函数在同一线程队列执行）
     * 完成；Room Flow 会在写完后自动触发列表刷新。
     */
    suspend fun reorderFolders(folders: List<Folder>) {
        folders.forEachIndexed { index, folder ->
            folderDAO.updateSortIndex(folder.id.toString(), index)
        }
    }

    /**
     * 删除文件夹，先把归属该文件夹的会话 folder_id 清空，再删除文件夹本身（不影响会话）。
     */
    suspend fun deleteFolder(id: Uuid) {
        conversationDAO.clearFolder(id.toString())
        folderDAO.deleteById(id.toString())
    }
}

private fun FolderEntity.toFolder(): Folder = Folder(
    id = Uuid.parse(id),
    assistantId = Uuid.parse(assistantId),
    name = name,
    sortIndex = sortIndex,
    createAt = Instant.ofEpochMilli(createAt),
)

private fun Folder.toEntity(): FolderEntity = FolderEntity(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)
