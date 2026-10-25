/**
 * 局部光源（来自 mod #20 通道导出的 aiplayer_lights.csv，静态快照）。
 * 只保留俯视投影需要的 (x,z) 与亮度等级；y 仅在需要时可用于状态展示。
 */
public final class LightRow {
    public final double x;
    public final double z;
    public final int light;   // 0..15，Minecraft 光照等级

    public LightRow(double x, double z, int light) {
        this.x = x;
        this.z = z;
        this.light = light;
    }
}
