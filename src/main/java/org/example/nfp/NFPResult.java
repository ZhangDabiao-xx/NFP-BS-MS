package org.example.nfp;

import java.util.ArrayList;
import java.util.List;

/**
 * NFP 计算结果
 */
public class NFPResult {

    /** 外临界多边形 */
    public List<Point> outerNFP = new ArrayList<>();

    /** 外NFP中的孔洞 (凹多边形时可能产生) */
    public List<List<Point>> holes = new ArrayList<>();

    /** 内临界多边形：保留面积最大的 inner loop，兼容旧调用方 */
    public List<Point> innerNFP = new ArrayList<>();

    /**
     * 内 NFP 的全部闭环。
     * 用途：凹多边形可能产生多个内部可行边界，只保留最大环会丢失候选点；
     * PolygonStitcher 会遍历这些环的顶点和中点，再通过重叠校验过滤非法放置。
     */
    public List<List<Point>> innerLoops = new ArrayList<>();

    /** 计算耗时(毫秒) */
    public double computeTimeMs = 0;

    /** 是否计算成功 */
    public boolean success = true;

    /** 错误信息 */
    public String errorMsg = "";
}