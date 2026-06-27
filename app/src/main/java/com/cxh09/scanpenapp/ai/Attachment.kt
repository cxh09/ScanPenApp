package com.cxh09.scanpenapp.ai

import java.util.Locale

/**
 * 「问 AI」多模态附件密封类。生命周期与 [com.cxh09.scanpenapp.ai.ConversationStore]
 * 内的 session 一致：随「新对话」清空、随发送多轮复用。
 *
 * - [ImageAttachment] 图片：内存里直接持有 base64 JPEG 字符串和原始宽高，避免反复解码。
 * - [TextAttachment] 文本：持有原文片段（已 UTF-8 校验、可能已截断）+ 元数据，便于在 chip 上展示。
 *
 * 不持久化、不写盘，符合「仅内存态」的产品定位；只走 SAF 选择 URI，读完就转成内存数据。
 */
sealed class Attachment {

    /**
     * 图片附件：来自 SAF 的任意图片格式（最终统一压缩为 JPEG 后 base64 编码）。
     *
     * @param base64Jpeg 已压缩的 JPEG base64 字符串（无前缀）。
     * @param width      缩放后的像素宽度。
     * @param height     缩放后的像素高度。
     */
    data class ImageAttachment(
        val base64Jpeg: String,
        val width: Int,
        val height: Int,
    ) : Attachment()

    /**
     * 文本附件：来自 SAF 的文本文件，UTF-8 解码、最多 50KB。
     *
     * @param displayName 文件显示名（取自 [android.provider.OpenableColumns.DISPLAY_NAME]，缺失时回退「未命名.txt」）。
     * @param content     文本内容（可能带截断提示后缀）。
     * @param sizeBytes   原始文件字节数（来自 [android.provider.OpenableColumns.SIZE]，缺失时回退 content 字节数）。
     * @param mimeType    MIME 类型（可空）。
     */
    data class TextAttachment(
        val displayName: String,
        val content: String,
        val sizeBytes: Long,
        val mimeType: String? = null,
    ) : Attachment() {

        /**
         * 截断时附加在 content 末尾的提示文本。统一格式：
         * `\n[已截断，原文共 X.X KB]`
         */
        fun truncatedNote(): String {
            val kb = sizeBytes / 1024.0
            return String.format(Locale.ROOT, "\n[已截断，原文共 %.1f KB]", kb)
        }
    }

    companion object {

        /**
         * 字节数 → 可读字符串的格式化工具。
         * - < 1MB：`%.1f KB`
         * - ≥ 1MB：`%.2f MB`
         */
        fun formatSize(bytes: Long): String {
            if (bytes < 1024L * 1024L) {
                return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            }
            val mb = bytes / 1024.0 / 1024.0
            return String.format(Locale.ROOT, "%.2f MB", mb)
        }
    }
}
