# AIPlayer TODO（2026-10-24 重建自 Linux 会话）

> 说明：本文件原为 Linux 侧会话文件，切回 Windows（源码可访问）后重建于此。
> 已从源码核对到 `brain/neural/` 下 100+ 类（Hippocampus / RightHemisphere / LeftHemisphere / CerebralHemisphere / PrefrontalCortex / PredictiveImagination / CounterfactualSimulator / NGramComposer / PhonationLayer / EvolutionEngine 等均已存在）——许多早期项可能已实现，**执行前先对照源码核对状态**，不重复实现。

## 铁律（项目红线，任何实现不得违背）
- 行为决策一律**可学习涌现**，不硬编码。
- **编号 = 感知编址（地址）**，不是规则；不把"该怕什么/该怎么做"塞进编号。
- 感知/认知接口**交付给对应脑区部件**，不一股脑全连在单一皮层上。
- 喂词例外集**只限**：玩家名、命名牌、铁砧重命名格。
- 已撤销：进化系统（#13，死亡换基因会抹除个体社会记忆）；已放弃：真繁殖（#14）；已移除 chatbuddy 全部功能。

## 一、待完成项（当前重点）

### #18 崩溃修复（2026-10-21 实测崩溃，根因已定位）
- **症状**：30 bot × 40h，单 tick 卡 60s → Server Watchdog 强杀。
- **根因**：`AssociativeMemory.enforceNodeCap` 每次 link 触发**全量排序**，comparator 现场 O(n) 重算 `commitment()` → O(n² log n)~O(n³)，每 tick 每 bot 都在 associate。
- **修复方向**：
  1. `commitment()` 结果**缓存**（关联变化才重算）；
  2. `enforceNodeCap` **不用全量 sort**：堆/分区/随机采样选最弱 k 个；
  3. cap 分批 prune，避免一次全量；
  4. 30 bot **错峰 tick**（治标）。
- **涉及文件**：`AssociativeMemory.java`（commitment/enforceNodeCap/link）、`CortexNetwork.java`、`NeuralCenter.java`。

### #19 独立 Java 窗口程序（csv 动态可视化）
- 纯消费展示，**不本地复刻世界生成器**。
- 加载 mod 导出**地形 PNG** 作底图；csv 导入 bots 按 x/z 显示。
- 时间进度条（播放/暂停/拖动），按 csv **gameTime** 推进。
- 显示对应时刻位置 + 状态（血量/饱食度等，字段来自 #20）。
- 独立浮窗显示**世界聊天 + 系统信息**（来自 #20 输出通道）。
- 技术栈：Swing 默认（JavaFX 待用户拍板）。
- 建议独立工程目录（不在 mod 内）。

### #20 mod 端统一输出通道
- ① 世界聊天 + 系统信息流；
- ② csv 详细记录：每 bot x/z + 血量 + 饱食度 + 状态；
- ③ 地形 PNG 导出（种子/已加载区块）。
- **时间戳统一"游戏内天数计数器 + tick"**（世界时间，持久化于存档，跨服务器重启继续流动、不归零；非服务器运行时长）。csv 与聊天记录同一时间轴，供 #19 播放。

### #21 突破两词上限 + 指称压力改进（核心待解决）
- **问题**：bots 只会说 1–2 个词的句子（40h 实测仍卡双词），缺**序列组合/句法生成**机制，纯时间无法突破。
- 路径：
  1. **指称压力造词**：有表达意图但词库无对应词 → 触发造词（PhonationLayer 新造音节），新造词绑定当前感知编号集（指称锚定）；
  2. **序列链式选词**：从记忆按关联强度上词→下词，长度不设上限（NGramComposer）；
  3. 造词必要性/频率由"想说却无词"强度 + 对象出现频率**动态决定（可学习）**，避免过度/少造。
- 关联 #8 交流意图层。

### #22 细化脑的结构（脑区分工）
- 把"皮层+记忆+决策"单层结构按真实脑区拆分映射；各模块挂到对应脑区，不全接皮层。
- **脑区 → 模块映射**（类名以源码为准核对）：

| 脑区/系统 | 真实功能 | 现有模块/类（拟映射，需核对） | 拆解 / 缺口 |
|---|---|---|---|
| 枕叶·背侧流 | where/how：位置·运动 | 视网膜扫描 + F_SPATIAL/F_* 位置特征 | 确保位置/运动走独立空间通道 |
| 枕叶·腹侧流 | what：识别 | 视网膜标签 + 名字通道 | 识别与空间分离 |
| 顶叶 | 自我中心坐标·空间注意·心理旋转 | F_LOCATION_BASE/F_SPATIAL_DIR_BASE/F_HAND_ITEM | **缺顶叶空间工作记忆 + 心理旋转** |
| 海马-内嗅 | 认知地图(place/grid/朝向/边界) | 共享事件 hash 表 + HomeSense | **缺大尺度认知地图** |
| 前额叶 | 预测·想象·规划 | CounterfactualSimulator + PrefrontalCortex + StrategyCortex | **缺生成式想象**（PredictiveImagination 是否已实现需核对） |
| 基底节/小脑 | 动作选择·协调·习惯 | MotorCommand + MotorNerve + InteractionCortex | 已执行层 |
| 杏仁核 | 恐惧·威胁快反应 | DefensiveCombat + 恐惧压制 + A2 紧急度 | 低层快通道 |
| 奖赏系统 | 奖赏·动机 | LimbicSystem/EmotionalState + reward | 可独立 |
| 海马·联想 | 记忆巩固·关联 | AssociativeMemory + 遗忘曲线 | 记忆归属 |
| 语言区 | 语言产生·理解 | PhonationLayer + CommunicativeVoice + SemanticLearner | 自创词语言链 |

- 三项主要缺口：**顶叶空间工作记忆+心理旋转、前额叶生成式想象、海马大尺度认知地图**。

## 二、早期已实现项（供核对，非待办）
感知层（视网膜矩阵/名字身份通道/实体姿态朝向/容器感知/听觉修正）、语言（掐喂词+自创词绑定/交流意图层）、认知与意识（自我行为归因/ToM去硬编码/自主神经系统/时间意识四层）、视觉（夜视修复/药水效果/准星/视锥/botview 渲染）、配方→物品格标签、多级记忆+遗忘曲线、睡眠感知、自主起床、首次触碰种子、乱打不过再跑攻击、丢弃物品、告示牌/书读取等。
