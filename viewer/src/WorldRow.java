/** aiplayer_world.csv 的一行：某个 bot 在某个游戏时刻的采样 */
public final class WorldRow {
    public final long time;      // day*24000 + tick（世界时钟，统一时间轴）
    public final int day;
    public final int tick;
    public final String bot;
    public final double x, y, z;
    public final float hp;
    public final int food;
    public final String dim;
    public final double panic;
    public final double joy;
    public final String goal;

    public WorldRow(int day, int tick, String bot, double x, double y, double z,
                    float hp, int food, String dim, double panic, double joy, String goal) {
        this.day = day;
        this.tick = tick;
        this.time = day * 24000L + tick;
        this.bot = bot;
        this.x = x;
        this.y = y;
        this.z = z;
        this.hp = hp;
        this.food = food;
        this.dim = dim;
        this.panic = panic;
        this.joy = joy;
        this.goal = goal;
    }

    public String statusText() {
        return String.format(
            "<html>%s<br>第 %d 天 tick=%d<br>坐标: %.1f, %.1f, %.1f<br>" +
            "血量: %.1f/20　食物: %d/20<br>恐慌: %.2f　愉悦: %.2f<br>目标: %s<br>维度: %s</html>",
            bot, day, tick, x, y, z, hp, food, panic, joy, goal, dim);
    }
}
