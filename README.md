# ReadGuo · 红果免费短剧界面工具模块

> 作者：酷安@残話 · 模块内置完整使用说明与免责声明 · [GitHub](https://github.com/staciepomar-spec/RedGuo)

一个面向 **红果免费短剧（com.phoenix.read）** 的 LSPosed/Xposed 界面增强模块。
不碰任何付费与版权机制，只做界面显示层面的个性化。

> ⚠️ 本项目仅供学习交流与个人技术研究使用，请勿用于商业用途。详见文末免责声明。

## 功能一览

### 一、状态栏

| 功能 | 说明 |
|------|------|
| 隐藏系统状态栏 | 独立开关，默认开启 |
| 状态栏底色 | 跟随背景 / 纯黑 / 纯白 三选一 |

> 「点击状态栏区域显示时间/电量数字」子功能**已移除** ——
> 现在状态栏隐藏后，顶部只保留一条静态底色横条，不承载任何文字与交互。

### 二、叠加组件透明度

全局系数 × 各项独立透明度，**双层乘法叠加**。
生效值 = 该项透明度 × 全局透明度；只想微调单项时把全局调回 100。

### 三、组件显隐（11 项）

默认全部显示，勾选「隐藏」才生效；每项可单独调透明度。

**首页（MainFragmentActivity）**

| # | 组件 | 匹配规则 |
|---|------|----------|
| 1 | 顶部导航栏 | `pcls:SlidingTabLayout` |
| 2 | 右侧互动栏（通用） | `cls:...rightview.ShortSeriesRightView` |
| 3 | 底部选集条 | `bar:观看完整\|观看全集\|立即观看` |
| 9 | 全屏观看按钮 | `text:全屏观看` |
| 10 | 备案号水印 | `text:网微剧备字` / `id:i0a` |
| 11 | 类型标签（剧名下方） | `cls:...widget.tag.PriorityTagLayout` |

**二级界面 / 短剧播放页（ShortSeriesActivity）**

| # | 组件 | 匹配规则 |
|---|------|----------|
| 4 | 顶部信息栏（←第N集 / 1.0x / ⋮） | `cls:...titlebar.CommonTitleBar` |
| 5 | 弹幕输入条 | `cls:...danmaku.PublishDanmakuEntranceView` |
| 6 | 标题行（播放页 / 首页卡片） | `cls:...infoheader.ShortSeriesInfoHeaderView` / `id:g41` / `cls:...infolayer.ShortSeriesExtendTextView` |
| 7 | 热评行 | `cls:...comment.view.InfoPanelHotCommentView` / `cls:...infopanel.hotcomment.SeriesHotCommentView` |
| 8 | 底部信息 / 选集条 | `cls:...infobottom.ShortSeriesInfoBottomView` / `cls:...bottombar.view.BottomRelateSeriesView` / `id:hh` |

> 首页信息流里的视频卡片复用同一套 ShortSeries 组件，因此「播放页」那几项在首页卡片上同样生效。

### 四、自定义隐藏清单

- **扫描当前页面**：自动下钻列出当前页面所有可隐藏组件（内部面积门槛 0.002，上限 120 条），
  顶栏标签 / 右侧栏单按钮 / 倍速按钮都能单独列出
- **手动添加**：支持 4 种规则写法，`|` 分隔多个

| 规则前缀 | 含义 |
|----------|------|
| `cls:类名` | 类名后缀匹配（可只写后半段；短混淆名要求长度 ≥ 4） |
| `@文案` | 等价于 `text:文案` |
| `text:文案` | 文案包含匹配 |
| `id:名字` | 资源 id 名匹配 |
| `pcls:类名` | 类名后缀节点的父级 |
| `bar:文案` | 文案 → 满宽可点击祖先 |

### 五、底部标签栏

按标签文字勾选隐藏（如 商城 / 赚钱 / 我的），剩余标签**自动摊满整行**。

> 这是唯一使用 `GONE` 而非 `INVISIBLE` 的组件 —— 它需要「腾地方」，是刻意设计。

### 六、底部导航栏 / 小白条隐藏

**开启后，只要在红果内，导航栏就全程不显示；退出红果（回到桌面或切到别的 App）才恢复。**

| 项 | 说明 |
|----|------|
| 开关 | 默认关闭（不填就保持宿主原样） |
| 生效范围 | 红果整个前台期间 —— 不止播放页，首页、剧场、商城等所有页面都隐藏 |
| 恢复时机 | 整个 App 退到后台时；App 内部切页面**不会**恢复 |

> 设计取舍：早期版本做过「静置 N 秒才隐藏、一碰就冒出来」，实测体验很差 ——
> 点个 tab、滑一下页面它就会弹回来，反复跳动反而比常显更烦。
> 因此改成**软件内常隐**，只保留「退出才恢复」这一个出口。

实现走系统 `WindowInsetsController`：`hide(WindowInsets.Type.navigationBars())` 收起，
`show(...)` 恢复，并设置 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`。

> **导航栏不是 DecorView 的子视图。** 实机 `dumpsys window` 显示它是
> `Window{... NavigationBar0}`，`pkg = com.android.systemui`，属于**跨进程独立窗口**
> （`ty = NAVIGATION_BAR`）。模块拿不到它的 View，`setVisibility` 一类做法必然无效，只能走系统 insets API。

需要留意的三点：

1. **单次 `hide()` 守不住。** 系统会在下拉通知栏、输入法弹出、Activity 切换等时机强制恢复导航栏，
   所以复用既有的 1.2s 重应用循环**周期性地重新 hide**（`hide()` 幂等，重复调用无副作用）。
2. **隐藏后 insets 会归零**，此时 `isVisible(navigationBars())` 反而报 `true`。
   所以用一份 `NAV_HIDDEN_MARK` 记录「确实由我们隐藏」的状态作为第一判据，
   insets 只作兜底 —— 否则会陷入每轮循环重复 `hide()` 的死循环。
3. **前台计数区分「内部切页」与「真的退出」**：`onStop` 里递减计数器，
   只有归零（整个 App 离开前台）才恢复导航栏，避免 App 内切 Activity 时误恢复。

面板里带一行诊断信息，显示导航栏高度、当前可见状态、是否已被本模块接管，以及
系统导航模式（手势 / 三键）。

### 七、播放画质 / 倍速

| 功能 | 挂载点 |
|------|--------|
| 默认最高画质 | `TTVideoEngineImpl.configResolution(Resolution)` → 替换为 `ExtremelyHigh` |
| 自定义倍速 1.0~4.0x 无级（0.1 步进） | `PlaybackParams.setSpeed(F)` |

改动对**下一个起播的视频**生效（自动连播即可见效）。倍速不依赖宿主档位限位。

### 八、手势

- **双击打开评论区**：可选「吞掉第二次点击」避免误触点赞
- 已屏蔽宿主自身的双击点赞链路（`r65.o0` 等三兄弟类）

### 九、清屏省心

清屏状态下 10 秒不碰屏幕，**自动收起其余控件只留视频**；触摸立即恢复。
仅二级播放页生效。

> 正确入口：长按视频 → 播放设置 → 清屏播放。（选集条右侧那个方块图标不是清屏。）

### 十、面板唤出

| 方式 | 说明 |
|------|------|
| 长按底部「首页」标签 | 主入口，可开关 |
| 播放设置弹层内入口 | 竖屏显示，可开关 |

### 十一、排错自查

`/sdcard/Android/data/com.phoenix.read/files/` 下：

- `rgtools_log.txt` —— 功能命中记录
- `rgtools_tree.txt` —— 进页面 1.8 秒后的视图树快照

面板内亦有「输出视图树日志」按钮。

## 安装

1. 设备已 root 并安装 [LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk）
2. 安装 Release 中的 APK
3. 在 LSPosed 中启用模块，作用域勾选「红果免费短剧」
4. 强制停止红果后重新打开
5. 桌面打开 ReadGuo 可查看完整使用说明；长按底部「首页」标签唤出设置面板

## 从源码构建

无需 Android Studio / Gradle，脚本一键构建（需要 JDK 17+、Android SDK 的 aapt2/d8/apksigner）：

```bash
python module/build.py
# 产物：out/RGTools-<版本>.apk
```

版本号在 `module/build.py` 的 `VERSION_CODE / VERSION_NAME` 中维护。

## 目录结构

```
module/
├── src/com/wodi/redguotools/   # 模块源码（Java，界面全部代码构建）
│   ├── RGModule.java           # Xposed 入口，所有 hook 注册
│   ├── UiController.java       # 界面控制 / 规则引擎 / 清屏空闲收起
│   ├── PlayerTweaks.java       # 播放器：最高画质 / 自定义倍速
│   ├── Config.java             # 配置读写（槽位表 / 清单）
│   ├── SettingsPanel.java      # 红果内设置面板
│   ├── SheetEntry.java         # 播放设置弹层注入入口
│   ├── Names.java              # 规则 → 图标 + 中文名
│   ├── Theme.java              # 配色与控件工厂
│   └── MainActivity.java       # 模块自身说明页
├── res/                        # 图标资源
├── build.py                    # 免 Gradle 构建脚本
└── AndroidManifest.xml
```

## 已验证环境

| 设备 | 系统 | 红果版本 | Root |
|------|------|----------|------|
| Xiaomi 15 | HyperOS / Android 17 | 7.3.9.32 | KernelSU |
| Lenovo TB-9707F | Android 15 | 7.3.6.32 | Magisk |

其它版本理论上可用（视图类名匹配做了多版本兼容），未逐一测试。

## 更新日志

### v2.41 (versionCode 59)

- **移除**：状态栏「点击显示数字」子功能。此前点击顶部横条会临时唤出时间与电量，
  并附带 1 秒无操作自动收起、15 秒定时刷新等一整套逻辑，现将它们全部下线。
- **保留**：「隐藏系统状态栏」开关与「状态栏底色」（跟随背景 / 纯黑 / 纯白）。
  自绘横条改为纯静态载体，只负责填底色，**不再有任何文字与点击交互**。

### v2.40 (versionCode 58)

- 导航栏行为由「静置隐藏 + 触摸唤出」改为**软件内常隐、退出才恢复**。
  此前静置一段时间才隐藏、一点屏幕就弹回来，切换 tab 时反复跳动，实际体验不佳。
  现改为进入红果即隐藏并全程保持，只有整个 App 退到后台才恢复。
  实现上复用 1.2s 重应用循环周期性重新 `hide()`（系统会在下拉通知栏、
  输入法弹出、Activity 切换等时机强制恢复导航栏，单次调用守不住），
  并用前台计数器区分「App 内部切页」与「真的退出」。

### v2.39 (versionCode 57)

- **修复**：导航栏隐藏后陷入死循环 —— 每约 1.2 秒重复调用一次 `hide()`。
  根因是隐藏后 `isVisible(navigationBars())` 反而报 `true`（insets 归零），
  以它作唯一判据会误判为「还露着」。
  现将 `NAV_HIDDEN_MARK`（本模块的接管标记）提升为第一判据，insets 仅作兜底。
  实测日志由「每 1.2 秒刷一次」收敛为「每个页面仅一次」。
- 收敛调试日志：隐藏后的延迟回读改为**仅在被宿主回退时**才记录。

### v2.38 (versionCode 56)

- **新增**：底部导航栏（小白条）自动隐藏。静置 1~10 秒（默认 3 秒）后自动收起，
  触碰屏幕或滑到底部边缘时重新出现。
  实现走系统 `WindowInsetsController`（导航栏是 SystemUI 的**跨进程独立窗口**，
  不是 DecorView 子视图，`setVisibility` 无效）；
  边缘感应带按缓存的导航栏真实高度计算，隐藏后仍可准确唤出。
- 面板新增「底部导航栏 / 小白条」卡片，含开关、时长滑块与一行诊断信息。
- 实机（小米 15 / 红果 7.3.9.32 / 手势导航）验证通过：静置后小白条消失，
  触摸屏幕立即恢复，对照截图确认。

### v2.37 (versionCode 55)

- **修复**：定位并屏蔽 feed 双击点赞真凶 `r65.o0`（feed 与二级页共用）。
  通过穷举宿主全部 `onDoubleTap` 声明类挂诊断钩子，用 `input motionevent` 串联快速双击，
  锁定三兄弟类 `e55.h` / `r65.o0` / `wp4.e`，逐一排除后确认 `r65.o0` 为根因。
- **修复**：feed 双击打开评论区后视频正常恢复播放（注入一击 `dispatchTouchEvent`）。

### v2.36 (versionCode 54)

- 内部构建版本，未单独发版。

### v2.35 (versionCode 53)

- **修复**：7.3.9.32 的 feed 双击点赞改由 `social.ui.t.onDoubleTap` 触发，
  加入屏蔽名单。
- **修复**：7.3.9.32 二级页单击视频 = 暂停/恢复切换，旧策略吞掉第二次点击导致暂停残留。
  改为**仅横屏吞点**，竖屏 tap2 放行（tap1 暂停 → tap2 恢复，净效果 = 播放中）。

### v2.33 (versionCode 51)

- 模块 App 主页卡片化改版（仿微信输入法工具模块）：大标题 + 状态卡 + 设备与构建卡 +
  底部四标签导航（主页 / 功能 / 技巧 / 关于）。
- **修复**：Android 11+ 包可见性导致读不到红果版本 → manifest 加 `<queries>`。
- **修复**：默认 Theme 带黑色 ActionBar 挤压布局 → 改用 `Theme.Material.Light.NoActionBar`。

### v2.31 (versionCode 49)

- 模块 App 说明页。

### v2.30 (versionCode 48)

- 默认最高画质 + 自定义倍速 1~4 倍无级调节。

### v2.16 (versionCode 34)

- 清屏 + 空闲 10 秒自动收起其余控件（仅二级播放页）。

### v2.11 (versionCode 29)

- 底部标签栏隐藏功能验证通过。

### v2.10 (versionCode 28)

- **修复**：「扫描当前组件」扫不出顶栏标签与右侧栏单按钮 → 记录块后继续下钻。

## 免责声明

- 本项目仅供学习交流与个人技术研究使用，请勿用于任何商业或非法用途。
- 本模块不修改、不破解、不绕过任何付费、会员或版权保护机制；所有功能仅调整本机的
  界面显示与个人偏好设置。
- 请在遵守法律法规及平台用户协议的前提下使用；请支持正版，相关内容、商标与软件
  版权归原作者及平台所有。
- 本项目按「现状」提供，不附带任何明示或默示的担保；因下载、安装或使用本项目而
  产生的一切后果，由使用者自行承担。
- 若本项目侵犯了相关方的合法权益，请联系删除。
