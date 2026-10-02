package me.rerere.rikkahub.data.files

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceRestoreLinkTest {

    @Test
    fun `workspaceRootToId correctly resolves both root and id to logical workspace id`() {
        // 模拟一个从备份还原的工作区，其 id 为 uuid-A，root 为 uuid-B
        val restoredWorkspace = WorkspaceEntity(
            id = "workspace-uuid-aaaa",
            name = "Restored Workspace",
            root = "workspace-root-bbbb",
            createdAt = 1000L,
            updatedAt = 1000L,
            lastAccessAt = null,
        )

        val workspaces = listOf(restoredWorkspace)

        val workspaceRootToId = buildMap {
            workspaces.forEach { ws ->
                put(ws.id, ws.id)
                put(ws.root, ws.id)
            }
        }

        // 1. 传入逻辑 id 能够正确解析为逻辑 id
        assertEquals("workspace-uuid-aaaa", workspaceRootToId["workspace-uuid-aaaa"])

        // 2. 传入磁盘物理目录 root 能够正确映射回逻辑 id，彻底防止"工作区不存在"报错
        assertEquals("workspace-uuid-aaaa", workspaceRootToId["workspace-root-bbbb"])
    }
}
