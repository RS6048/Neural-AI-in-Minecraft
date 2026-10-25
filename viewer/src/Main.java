import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * AIPlayer 地形查看器入口（TODO #19，纯消费端，不依赖 Minecraft）。
 *
 * 用法：
 *   java -cp out Main [--dir <数据目录>] [--selftest]
 *
 * --dir 省略时：当前目录下有 run\ 子目录就用 run\，否则用当前目录。
 * --selftest：生成/加载合成数据跑逻辑自检，不弹窗。
 *
 * 大文件处理：world.csv 分块懒加载，加载放在后台线程 + 进度反馈，不阻塞界面；
 * 打开后从时间轴起点 t=0 开始播放。
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        File dir = null;
        boolean selftest = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--dir") && i + 1 < args.length) {
                dir = new File(args[++i]);
            } else if (args[i].equals("--selftest")) {
                selftest = true;
            }
        }

        if (selftest) {
            File dataDir = (dir != null) ? dir : new File("selftest_data");
            SelfTest.run(dataDir);
            return;
        }

        if (dir == null) {
            File run = new File("run");
            dir = run.isDirectory() ? run : new File(".");
        }
        if (!dir.isDirectory()) {
            System.err.println("数据目录不存在: " + dir.getAbsolutePath());
            System.exit(2);
        }

        loadAndShow(dir);
    }

    /** 后台加载 + 进度反馈 + 控制台日志，加载完再弹主窗口。 */
    private static void loadAndShow(File dir) {
        // 加载进度窗口（不确定进度条 + 阶段文字），先显示，避免"未响应"
        JFrame splash = new JFrame("AIPlayer 地形查看器 - 加载中");
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JLabel stage = new JLabel("正在扫描 world.csv（分块索引）…", SwingConstants.CENTER);
        splash.setLayout(new BorderLayout(8, 8));
        splash.add(stage, BorderLayout.CENTER);
        splash.add(bar, BorderLayout.SOUTH);
        splash.setSize(420, 120);
        splash.setLocationRelativeTo(null);
        splash.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        splash.setVisible(true);

        SwingWorker<DataStore, String> worker = new SwingWorker<DataStore, String>() {
            @Override
            protected DataStore doInBackground() throws Exception {
                publish("正在扫描 world.csv（分块索引）…");
                DataStore s = DataStore.load(dir);
                return s;
            }

            @Override
            protected void process(List<String> chunks) {
                String last = chunks.get(chunks.size() - 1);
                stage.setText(last);
            }

            @Override
            protected void done() {
                try {
                    DataStore store = get();
                    splash.dispose();
                    printStats(dir, store);
                    MainFrame.show(store);   // 显示主窗口（从 t=0 开始）
                } catch (Exception e) {
                    splash.dispose();
                    System.err.println("加载失败: " + e.getMessage());
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(null,
                            "加载失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private static void printStats(File dir, DataStore store) {
        System.out.println("==============================================");
        System.out.println("数据目录 : " + dir.getAbsolutePath());
        System.out.println("bot 数   : " + store.botNames().size());
        System.out.println("world 行 : " + store.worldRowCount()
                + "（分块懒加载，" + DataStore.CHUNK_LINES + " 行/块，" + DataStore.CACHE_BLOCKS + " 块常驻内存）");
        System.out.println("chat 行  : " + store.chatCount());
        System.out.println("底图     : " + (store.mapFileName == null ? "（无）" : store.mapFileName));
        System.out.println("时间轴   : 第 " + (store.minTime / 24000L) + " 天 tick=" + (store.minTime % 24000L)
                + "  →  第 " + (store.maxTime / 24000L) + " 天 tick=" + (store.maxTime % 24000L));
        System.out.println("地图范围 : x[" + (long) store.minX + "," + (long) store.maxX
                + "] z[" + (long) store.minZ + "," + (long) store.maxZ + "]");
        System.out.println("内存     : 仅保留 " + DataStore.CACHE_BLOCKS
                + " 个 world 块 + 全量 chat，未一次性读入所有 world 行");
        System.out.println("播放起始 : t=0（时间轴起点）");
        System.out.println("==============================================");
    }
}
