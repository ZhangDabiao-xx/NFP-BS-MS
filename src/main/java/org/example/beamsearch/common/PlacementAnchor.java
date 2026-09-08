package org.example.beamsearch.common;

/**
 * 矩形块在一个极大空闲矩形中的放置锚点。
 *
 * <p>该枚举只改变块在已验证可放置空间内的坐标选择，不改变空间可行性、
 * 重叠检查或后续空间切分规则。</p>
 */
public enum PlacementAnchor {
    /** 保持原有逻辑：使用空闲空间最接近板材外角的角点。 */
    NEAREST_BOARD_CORNER,
    /** 固定使用当前极大空闲矩形的左下角 {@code (x1, y1)}。 */
    SPACE_LOWER_LEFT
}
