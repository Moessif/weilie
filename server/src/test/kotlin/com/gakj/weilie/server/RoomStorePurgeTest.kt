package com.gakj.weilie.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomStorePurgeTest {

    private fun newStore(): Pair<RoomStore, java.nio.file.Path> {
        val dir = Files.createTempDirectory("weilie-store-test")
        return RoomStore(dir) to dir
    }

    @Test
    fun `清理超期房间并按空闲时间返回`() {
        val (store, _) = newStore()
        val fresh = store.createRoom("新房间")
        val old = store.createRoom("旧房间")
        old.lastActiveAt = System.currentTimeMillis() - 10L * 24 * 3600_000L

        val purged = store.purgeIdle(3L * 24 * 3600_000L)
        assertEquals(1, purged.size)
        assertEquals(old.roomId, purged.first().roomId)
        assertNull(store.findRoom(old.roomId))
        assertNotNull(store.findRoom(fresh.roomId))
    }

    @Test
    fun `关闭房间后邀请码索引同步移除`() {
        val (store, _) = newStore()
        val room = store.createRoom("待关闭")
        assertNotNull(store.findByCode(room.joinCode))
        assertTrue(store.removeRoom(room.roomId))
        assertNull(store.findByCode(room.joinCode))
        assertEquals(0, store.roomCount())
    }
}
