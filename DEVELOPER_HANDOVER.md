# AIPlayer 开发者交接文档

> 给未来接手的开发者：把"在 Minecraft 世界里自主存活、学习、形成社会的 AI 代理"mod 的关键设计、红线、代码地图、已知坑一次交代清楚。
> 先读本文件 → 再读 `TODO.md`（待办/已实现）→ `README.md`（对外门面）。文中标注"以源码为准"处，请以实际代码为准核对，不要凭印象改动。

---

## 0. 一分钟摘要

Fabric mod（modid `aiplayer`，Minecraft 26.3，Java 25）。`/aiplayer add <name>` 生成一个 `FakePlayer`（服务端无客户端连接的真实玩家实体）。**bot 没有 LLM**：所有行为来自自研的**神经关联记忆 + 具身感知**，通过**编号（感知编址）**驱动。语言不教——从 24 字母自创。行为决策一律"可学习涌现、不硬编码"。

---

## 1. 项目红线（铁律，任何改动不得违背）

- **行为决策可学习涌现，不硬编码**：不写"该怕什么 / 该怎么做 / 走多近 / 家在哪 / 打不过就跑"这类规则。
- **编号 = 感知编址（地址），不是规则**：感知/行为输入映射成数字特征向量，只负责"哪个事件激活哪段神经"，不把语义塞进编号。
- **感知/认知接口交付给对应脑区部件**：听觉→听觉皮层、视觉→视觉皮层、坐标→空间区、血量→体感区……不要一股脑全连在单一皮层上。
- **喂词例外集只限**：玩家名、命名牌、铁砧重命名格。除此之外**禁止**系统投喂语言/语义（不喂英文池、不喂句子）。
- **已撤销 / 已移除**：
  - 进化系统（#13）已撤销——死亡换基因会抹除个体社会记忆；`config` 里的进化开关也已删除。
  - 真繁殖（#14）已放弃。
  - chatbuddy / Ollama 语言伙伴全部功能已移除（不要恢复；语言改为世界事件 + 自创）。
- **数据/时间戳统一世界时钟（天数计数器 + tick）**，持久化于存档，跨服务器重启继续流动、不归零。
- 视觉机制用 `botview` 渲染时，不同标签对应不同颜色；第偶数次使用命令关闭。

---

## 2. 代码地图（`src/main/java/mod/rs6048/aiplayer/`）

### 顶层
| 文件 | 职责 |
|---|---|
| `AIPlayer.java` | mod 入口 / Fabric lifecycle（注意：文件 119KB，是核心大文件） |
| `AIPlayerManager.java` | bot 注册 / 重生（死亡自动重建，保留原 gamemode，原名大小写）/ 世界重启自动复原 / tick 调度 |
| `FakePlayer.java` | bot 实体：驱动 `NeuralCenter.tick`，接管 `isClientAuthoritative=false`（服务端权威，重力/摔落/受伤正常） |

### `brain/neural/`（100+ 脑区类）
- **中枢与决策**：`NeuralCenter`（神经中枢，134KB，核心大文件，组装所有脑区并逐 tick 驱动）、`PrefrontalCortex`、`ActionPolicy`、`DecisionRecord`、`DecisionSource`、`GoalState`、`Motivation`、`AgencySystem`（自主感）、`ValueSystem`（critic）
- **感知**：`VisionModule`（视网膜矩阵：中心细/外周粗，返回标签/名字/亮度/深度）、`VisualCortex`、`AuditoryCortex`（听觉，声音注册名）、`SomatosensoryCortex`（体感/血量/饱食/药水效果）、`AmbientPerception`、`Percept`（绑定当前时刻）、`VisibleObject`、`HeardSound`、`DamageHit`、`NearbyEntity`
- **空间/导航**：`ParietalSpatialWM`（顶叶空间工作记忆）、`SpatialSense`、`SpatialTerrainMap`、`CognitiveMap`、`Navigation`（raycast 转向，非 A*）
- **记忆**：`AssociativeMemory`（单一共享关联记忆）、`Hippocampus`（情景记忆/认知地图）、`BrainMemory`/`MemoryBlock`/`MemoryTier`（多级记忆 + 遗忘曲线）、`CausalAssociations`（统计因果）、`CognitiveMap`
- **情绪/内驱**：`LimbicSystem`、`EmotionalState`、`AutonomicNervousSystem`（自主神经）、`SurvivalInstinct`（生存本能）、`SocialDrive`、`Curiosity`、`IntrinsicReward`、`ExplorationDrive`、`ComfortSense`、`EfficiencyAwareness`/`EfficiencyLearner`
- **语言**：`PhonationLayer`（新造音节）、`CommunicativeVoice`（发声/想说层）、`SemanticLearner`（词↔感知绑定）、`NGramComposer`（序列组合）、`LanguageArea`、`WordEmbedder`
- **认知/想象**：`PredictiveImagination`（反事实）、`CounterfactualSimulator`、`StrategyCortex`/`StrategyExecutor`、`Experimenter`、`SelfNarrative`（自我叙事）、`ExtendedSelf`（延伸自我）、`SocialAgentModel`/`SocialLearner`
- **运动/交互**：`InteractionCortex`、`MotorCortex`、`MotorNerve`、`MotorCommand`、`MotorAction`、`Cerebellum`、`Brainstem`、`BodyModel`（efference copy）、`EatingCortex`、`InventoryCortex`、`RecipeInventor`、`ShelterBuilding`、`DefensiveCombat`、`TimeSense`、`NumberSense`、`WorldAnchor`、`HomeSense`、`MusicCortex`
- **事件**：`WorldEvent`、`WorldEventStream`（视野差分，需节流）

### 其他
- `command/AIPlayerCommand`：`/aiplayer` 命令（add/remove/list/botview 等）
- `mixin/PlayerListMixin`、`network/`（BotViewTogglePayload、DummyConnection、DummyServerGamePacketListenerImpl）
- `output/WorldOutputHub`：数据通道 #20（csv / 地形 PNG / 光源 / map 外参）
- `src/client/`：`AIPlayerClient`（botview 视网膜渲染）
- `viewer/`：独立 Swing 可视化工程（**不含** Minecraft/mod 代码，只读 `run/` 数据）

---

## 3. 感知 → 认知 → 语言 → 动作 链路（数据流）

```
感知（视网膜标签+名字+亮度 / 听觉注册名 / 准星 / 世界事件 / 容器 / 告示牌书）
  → 编码为编号特征向量（Percept）
  → 各脑区（皮层重激活 / 海马记忆 / 前额叶目标 / 情绪内驱调制）
  → 目标选择（习得策略采样 + 反事实想象 + 内在动机）
  → 运动皮层（InteractionCortex / MotorCortex）→ 实际动作
语言：PhonationLayer 新造音节 → CommunicativeVoice 发声；新造词绑定当前感知编号集（指称锚定）。英文标点（`.`, `,`, `!`, `?`）像字母一样自然涌现：`PhonationLayer.invent` 造词时按概率 `PUNCT_CHANCE` 从 `PUNCT` 抽一个标点附在词尾（这是发声层抽取，不是"意图→标点"的硬编码断句符）；`SemanticLearner.tokenize` 把句读标点保留为 token 的组成部分（不再剥成空格），使标点绑定状态、可学习固化、可被重复说出。造词长度与句子词数同样**无固定上限**——已删 `PhonationLayer.MAX_LEN` 与 `NGramComposer.SAFETY_MAX_CHAIN`，新音节长度从已学词汇长度分布采样（空词库自然偏向短），句子靠"当前状态词用尽"自然终止。
```

- 感知层已砍掉 near4/F_SPATIAL/F_ENTITY 等"主视觉列表"，行为层**依赖视网膜命中的实体对象**做行动锁定——改动感知会直接影响行为，务必两头一起改。
- 数字/数量感知：`NumberSense`（数觉，4 个内的 subitizing），实体间距离计算到最近 4 个。

---

## 4. 持久化与数据

- 每个 bot 的记忆/值/策略/语言/叙事等持久化到 `run/brain/<Name>.json`（**文件名保留原名大小写**，不要统一小写）。加载时兼容旧档（缺键按空读）。
- 记忆分多级：WORKING → EPISODIC → SEMANTIC → LONG_TERM；遗忘曲线 / LRU 淘汰；长期记忆有字节预算。
- 输出通道（#20，见 `viewer/README.md` 数据契约）：
  - `aiplayer_world.csv`：`day,tick,bot,x,y,z,hp,food,dim,panic,joy,goal`
  - `aiplayer_chat.csv`：`day,tick,speaker,type,text`（type ∈ CHAT / SYSTEM）
  - `aiplayer_map_latest.png`（地形）、`aiplayer_lights.csv`（光源）、`aiplayer_map_meta.csv`（地图外参 sidecar）
- 共享世界事件 hash 表：把世界事件编号，向传入神经传编号值；多个 bot 共用（这是"掐喂词"后 bot 唯一的世界输入之一）。

---

## 5. 构建 / 运行 / 测试

- **构建**：`./gradlew build`（JDK 25，fabric-loom，依赖走阿里云镜像）。产物 `build/libs/aiplayer-1.0.0.jar`。
- **运行**：IDEA 的 "run Server" 配置，或 headless（`headless/` 无头服务器，构建产物同步到 `headless/mods/`）。
  - `/aiplayer add <name>` 生成 bot；`/aiplayer botview`（第偶数次关闭）在玩家 client 用 bot 视网膜风格渲染视野。
  - 无头服务器需 `eula.txt` 同意 + 服务器可在无玩家时 tick（依赖实体驱动区块）。
- **viewer 自检**：`viewer/run.bat --selftest`（生成合成数据跑逻辑断言，打印 PASS/FAIL）。
- **验收基线**：`BUILD SUCCESSFUL` + 无头冒烟（日志出现 `AIPlayer initialized` 与 `Done`，无异常），进程可正常停止。

---

## 6. 已知坑 / 待解决（务必先读）

1. **句子偏短**（#21）：bot 长期只产 1–2 词短句。机制缺口已补——`NGramComposer.composeChain` 用**双腿**（统计 bigram + 语义关联）链式选词，句子**无词数上限**、靠"状态词用尽"自然终止；造词长度也**无上限**、从词长分布采样。但"bot 是否会随时间把句子真正说长"仍需长跑观察。这是当前最大的行为观察点。
2. **性能 / 崩溃**（#18 历史）：`AssociativeMemory.enforceNodeCap` 曾因全量排序 O(n²~n³) 触发 Watchdog 强杀（30 bot × 40h）。已修复（缓存 commitment + 分批 prune），但 30 bot 长跑仍要留意：视野扫描（`WorldEventStream`）需节流，别每 tick 对 ~11000 方块做 `toString()`。
3. **核心大文件**：`NeuralCenter.java`(134KB)、`AIPlayer.java`(119KB)——改动时注意可维护性；经 GitHub 插件无法推送（需本地 git）。
4. **神经网络是全自研矩阵，不是 LLM**：别往"预训练/注意力/transformer"方向改，守住"编号驱动 + 关联记忆 + 可学习涌现"。
5. **视觉机制**：视网膜密度/步长有精确参数（fovea 步长、外周细分），改动会影响 bot 能否持续追踪小物体（蠹虫、经验球、药水粒子）。隐身的生物视网膜会击穿（不穿墙）。
6. **药水效果**：生物有药水效果时，会随机激活该生物对应的视网膜兴奋点位为"粒子效果标签"（考虑粒子颜色），且所有生物效果都应影响视觉（含对方隐身/发光）。
7. **末影人**：bot 需清楚自己准星位置才能激怒 `minecraft:ender_man`；头 = 更高的 bin（长条状学会"头"）。

---

## 7. 神经适应 / 习惯化 缓解 CPU（给下一棒的思路，未实现）

> 大脑演化出的"只处理变化"机制天然适合给视网膜减负（当前全量重扫 ~11000 方块是 CPU 热点，见 §6.2）。以下思路**只记录不实现**，下一棒需要时再落地。

- **特罗克斯勒褪色（Troxler fading）**：稳定不变的画面逐渐"褪色"，只感知变化。工程映射 = 懒重采样：上一帧采样过的格子下一帧沿用旧标签，只有变化的格子才重新射线/查询。
  - 只对**外周静态格子**启用（方块背景几乎不变，省 CPU 主要在这里）；**中央 fovea 保持高频**（动态目标不可褪色，否则丢目标）。
  - **自运动强制重扫**：bot 移动/转头时整个画面都在"变化"，褪色自动失效，需强制全量/增量重扫，否则缓存失效反而更慢。
  - **风险**：静止背景中缓慢出现的目标会被忽略（潜行的玩家/隐形怪）——可加"曾显著位置变化立即重扫"的注意门控。
- **完整清单**（系统分类，原理 → 工程映射）：
  - **A. 时间性适应**（恒定刺激被动降响应，特罗克斯勒同类）：亮度/光适应、对比适应（contrast adaptation）、发放率适应（spike-frequency adaptation）、感觉适应（嗅觉/触觉/温度）；**习惯化（habituation, Kandel）**：最简单非联想学习，重复无意义刺激反应减弱 → 常驻噪音（流水/风声）不再喂感知。
  - **B. 主动“选少做”**（动作级节省）：**中央凹化 + 扫视**（只在注视点全采样、外周低采样——**已实现**，中心细/外周粗）；**扫视抑制**（扫视期间抑制视觉 → 移动时降采样/不重扫，与自运动门控同一处）；**眨眼抑制**（眨眼瞬间不处理闪断）；**注意窗/显著性选择（Itti-Koch）**（只在显著位置全处理，其余低采样）。
  - **C. 编码压缩**（空间/统计冗余消除，真正省带宽）：**侧抑制/边缘编码（lateral inhibition）**（神经节只编码边界与变化 on/off-surround → 只对“边界/变化格”重采样）；**高效编码/冗余压缩（efficient coding, Barlow）**（同质方块只存一次标签）；**预测编码（predictive coding）**（只上传“预测误差”→ 最强省带宽：能预测的格子不重扫）；**稀疏编码（sparse coding）**（大多数格子静默、少数激活，MoE 思路）；**对立通道编码（opponent coding）**（差分而非绝对值）。
  - **D. 门控/过滤**（屏蔽重复或恒定输入）：**感觉门控（sensory gating, P50）**、**前脉冲抑制（PPI）**、**丘脑门控**（只有“相关”输入才放行）。
  - **E. 负面对照**（“省”的代价）：**变化盲 / 非注意盲视（inattentional blindness）**——专注时漏掉别处变化，与特罗克斯勒漏“缓慢出现目标”同源，需注意门控兜底。

  - **速查表**（想给视网膜省 CPU → 用哪个机制）：
    | 目标 | 生物机制 | 工程映射 |
    |---|---|---|
    | 静止背景不重扫 | 特罗克斯勒 / 对比适应 | 帧差分、懒重采样 |
    | 移动时不扫 | 扫视抑制 | 自运动门控降采样 |
    | 只扫显著处 | 注意窗 / 显著性 | 只在显著区全采样 |
    | 只传“变了”的 | 预测编码 | 世界差分 / 变化检测 |
    | 常驻噪音降权 | 习惯化 / 感觉门控 | 噪音屏蔽 |
    | 稀疏计算 | 稀疏编码 | 稀疏激活（MoE 思路） |

---

## 8. 修改规范

- 遵循红线（§1）。加感知 → 接对应脑区；改行为 → 走可学习/动机层，不写死。
- 改持久化格式要兼容旧档（缺键按空读）。
- 跑长测试前后，`run/` 下数据按时间戳区分新旧（世界时钟，跨重启连续，不归零）。
- git：根目录排除 `run/`（测试目录）、`headless/`（部署运行时/世界数据/玩家数据）、**所有图片文件**、日志与杂项（见 `.gitignore`）。

---

## 9. 遗留 / 风险

- **GitHub 发布**：本机无代理时本地 git push 被网络阻断；远端已有部分过程文件（构建配置/文档/viewer 部分），完整发布需本地网络后 `git push --force-with-lease origin main` 覆盖为本地最终 commit（本地历史含全部 131 文件）。认证账号 `RS6048` 即仓库 owner。
- viewer 坐标映射：有 `aiplayer_map_meta.csv` 时精确；无外参时回退 bot 包围盒近似（可能偏移）。map 瞄准偏移问题已修（mod 端 `WorldOutputHub.exportMap` 写外参，viewer `MapPanel` 用真实外参映射）。
- 早期项状态**以源码为准**：`TODO.md` 标注"执行前先对照源码核对状态"，许多早期 Step 内容可能已实现或已演进，不要按旧 README 的叙述重复实现。
