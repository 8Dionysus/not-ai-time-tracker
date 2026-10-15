package dev.notai.tracker

import kotlinx.serialization.Serializable

@Serializable data class ArchiveAlias(val copyId: String,val memberId: String)
@Serializable data class ArchiveLinkNode(val opaqueId: String,val bytes: Long,val sha256: String,val aliases: List<ArchiveAlias>)
/** Payload bytes count distinct inodes, not filesystem free space (open FDs/snapshots may retain it).
 * Graph inspection never grants deletion authority or implies that references have been released. */
@Serializable data class ArchiveLinkGraph(val revision: Long,val selectedIds: Set<String>,val componentIds: Set<String>,
    val nodes: List<ArchiveLinkNode>,val logicalSelectedBytes: Long,val payloadBytesWithNoRemainingAlias: Long,
    val hardBlockers: Set<CopyBlocker>)
