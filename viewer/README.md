# AIPlayer 地形查看器（TODO #19）

纯消费展示端：加载 mod 导出的地形 PNG + world/chat CSV，按游戏时间回放 bot 位置与状态。
**本目录不含任何 Minecraft / mod 代码，不复刻世界生成器**，只读 `run/` 下的数据文件。

## 构建 / 运行

```bat
build.bat                      :: 编译 src\*.java 到 out\
run.bat                        :: 默认数据目录：当前目录下有 run\ 就用 run\，否则用当前目录
run.bat --dir D:\path\to\run   :: 指定数据目录（如 headless\run\）
run.bat --selftest             :: 生成合成数据并跑逻辑自检，不弹窗，打印 PASS/FAIL
run.bat --selftest --dir X     :: 指定合成数据输出目录
```

依赖：JDK 25（`java` / `javac` 在 PATH），零外部库。

## 数据契约（由 mod 导出端 #20 产出，本程序严格消费）

| 文件 | 说明 |
|---|---|
| `aiplayer_world.csv` | 表头 `day,tick,bot,x,y,z,hp,food,dim,panic,joy,goal`；每行某 bot 一个采样；`tick = gameTime % 24000`，统一世界时钟 |
| `aiplayer_chat.csv` | 表头 `day,tick,speaker,type,text`；type ∈ {CHAT, SYSTEM}；text 含逗号/引号/换行按标准 CSV 双引号转义 |
| `aiplayer_map_latest.png` | 主世界俯视地形（北朝上）；缺失时回退任意 `aiplayer_map_*.png` |
| `aiplayer_lights.csv` | 局部光源静态快照（可选）；表头 `x,y,z,light`（light = MC 光照等级 0..15），覆盖式导出地表发光方块（火把/灯笼/营火/发光石/岩浆等） |
| `aiplayer_map_meta.csv` | **地图外参 sidecar**（可选但强烈建议）；表头 `minX,maxX,minZ,maxZ,imgW,imgH`，记录 PNG 覆盖的真实世界方块范围，供 bot/光源坐标精确映射 |

## 坐标映射（README 记录，重要）

- MC 坐标系：**x 东西向、z 南北向**；底图**北朝上**。
- **优先使用地图外参**（`aiplayer_map_meta.csv`，PNG 覆盖的真实世界方块范围）：`imgX=(x-minX)/(maxX-minX)*imgW`，`imgY=(maxZ-z)/(maxZ-minZ)*imgH`——与 mod 导出端一致，bot 精确落在真实位置。
- **回退近似**：无外参时用 CSV 全部 bot 采样的 (x,z) 包围盒铺满整张图（旧假设，可能偏移）。
- y（方块高度）仅在状态浮窗显示，不参与俯视投影。
- 时间轴：`time = day*24000 + tick`，从 CSV 全部行的 min 到 max。

## 功能

- 地形底图按 x/z 铺满，标注东南西北。
- bots 按 `world.csv 分块懒加载`：加载只做一遍流式扫描建块索引（`CHUNK_LINES=8192` 行/块），不把全部行读进内存；`latestRow(bot, time)` 按块二分 + 块内查找，命中 LRU 缓存（`CACHE_BLOCKS=8` 块常驻）。实测 18 万行（30 bot × 25 天）加载 <0.4s、完整顺序播放扫描 <0.5s。chat.csv 量小，全量引号感知读入。
- 顶部时间轴：播放/暂停、倍速（tick/s）、拖动滑块跳转；**打开即从 t=0（时间轴起点）开始**；聊天面板只显示 `time ≤ 当前` 的行，最新在底部自动滚动；CHAT 绿色、SYSTEM 灰色。
- **夜晚光照**（默认开，可取消勾选"夜晚光照"看原图）：按世界时间自动昼夜变换，采用太阳高度角光照模型（参考 Mineways / 种子查询器的日夜渲染思想，纯渲染层、不读存档）。白天原图；黄昏（tick 11000-13200）暖橙压暗；夜晚（13200-22800）深蓝黑月光 + bot 自发光光晕；黎明（22800-24000）冷蓝渐亮。时间标签同步显示 `[DAY/DUSK/NIGHT/DAWN]`。
- **局部光源**（场景灯光）：夜晚叠加光源光晕（径向渐变暖黄，半径/亮度随 light 等级），数据来自 `aiplayer_lights.csv`；越暗越亮，白天几乎不画。无该文件时回退纯全局光照。
- **异步渲染**（拖进度条/播放不卡）：EDT 永不读磁盘——只维护目标时间并立即用上一帧快照重绘，磁盘查询（`latestRow`）在单线程 IO 池完成，未就绪时顶部显示"⏳ 加载中（目标 t=…，显示 t=…）"。
- **分区懒加载**：world.csv 按时间分块（`CHUNK_LINES=8192` 行/块）+ 按 bot 分区（每 bot 独立时间二分 + 包围盒）+ 空间格子索引（`SECTOR=512` 方块，`sectorCount()` 供将来视野裁剪）。
- **缩放 / 拖拽**：滚轮以光标为中心缩放，按住左键拖拽平移，双击空白处重置视图；底图之外的区域显示黑色。
- 悬停 bot 圆点看状态 tooltip；点击锁定 bot，底部状态栏常显其 hp/food/panic/joy/goal/dim。
- 右侧图例列出 bot 名与颜色。
- 加载在后台线程（SwingWorker）+ 进度窗口，不阻塞界面；完成后控制台打印统计（bot 数 / world 行 / chat 行 / 底图 / 时间轴 / 坐标范围 / 内存说明）。

## 已知假设 / 缺口

1. 有 `aiplayer_map_meta.csv` 时坐标精确；无外参时回退 bot 包围盒近似（可能偏移）。
2. 非主世界维度（dim ≠ overworld）的 bot 仍投影在同一张图上，不做维度过滤/切换。
3. 不做轨迹插值/拖尾，只画当前最近位置。
4. mod 导出端 #20 尚未产出真实数据时，用 `--selftest` 的合成数据验证逻辑。
5. `aiplayer_lights.csv` 由 mod 端 `exportMap` 随地形图一起导出，依赖实体驱动的区块 ticking——无头无玩家/无 bot 时不产出（属预期）；真实 bot 运行时会随地图导出。
