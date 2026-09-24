package org.example.nfp;

/** 不调用模型或几何求解器的 NFP 策略集合回归测试。 */
public final class NfpStitchingPolicyTest {

    private NfpStitchingPolicyTest() {
    }

    public static void main(String[] args) {
        require(NfpStitchingPolicy.fromId("balanced") == NfpStitchingPolicy.BALANCED,
                "平衡策略 ID 应可解析。");
        require(NfpStitchingPolicy.PRESERVE_SCARCE_SMALL_RECTANGLES
                        .preserveSmallRectanglesForPacking(),
                "稀缺小矩形策略必须保留后续排样资源。");
        require(!NfpStitchingPolicy.CAVITY_FIRST.preserveSmallRectanglesForPacking(),
                "凹腔优先策略不应误启用小矩形保留过滤。");
        require(NfpStitchingPolicy.CAVITY_FIRST.cavityFirst(),
                "凹腔优先策略必须保持凹腔候选优先级。");
        System.out.println("NfpStitchingPolicyTest passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
