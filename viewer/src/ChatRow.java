/** aiplayer_chat.csv 的一行 */
public final class ChatRow {
    public final long time;
    public final int day;
    public final int tick;
    public final String speaker;
    public final String type;   // CHAT / SYSTEM
    public final String text;

    public ChatRow(int day, int tick, String speaker, String type, String text) {
        this.day = day;
        this.tick = tick;
        this.time = day * 24000L + tick;
        this.speaker = speaker;
        this.type = type;
        this.text = text;
    }

    public String line() {
        return String.format("[第%d天/%d] %s: %s", day, tick, speaker,
                text.replace("\r", "").replace("\n", " ⏎ "));
    }
}
