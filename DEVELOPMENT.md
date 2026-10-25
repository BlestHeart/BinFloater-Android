# BinFloater 开发笔记（ColorOS 16 / Android 16）

> 这份是开发向文档：构建方式、测试体系、设计取舍、踩过的坑。
> 想先了解这个工具是什么，看 [README.md](README.md)。

---

## 安装与真机验证

APK 装好之后，第一次打开需要手动给「显示在其他应用上层」权限（系统没有可以静默授予的途径）。授权流程：

1. `MainActivity` 里点「① 授予悬浮窗权限」→ `Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))`
2. 系统设置页里打开开关
3. 回到 App，`onResume()` 里 `refreshStatus()` 重新检查 `Settings.canDrawOverlays(this)`
4. 打开悬浮窗开关 → `startForegroundService(Intent(this, FloatingService.class))`

**只在 ColorOS 16 / Android 16 真机上验证过。** 其他 ROM 的以下行为未测：

- 后台启动前台服务是否被拦（ColorOS 有自启动管理）
- 悬浮窗在分屏/小窗模式下的坐标
- 常驻通知是否被系统折叠

---

## 构建

不依赖 Gradle。`build.sh` 手工调五步工具链：

```
aapt2 compile --dir app/res -o build/flat/res.zip
aapt2 link -o build/base.apk -I android.jar --manifest app/AndroidManifest.xml \
           --java build/gen --min-sdk-version 34 --target-sdk-version 36 \
           --auto-add-overlay build/flat/res.zip
javac --release 17 -classpath android.jar -d build/classes app/src/com/binf/floater/*.java
d8 --min-api 34 --output build/dex build/classes/**/*.class
# python zipfile 把 classes.dex 塞进 base.apk → zipalign → apksigner
```

**为什么不用 Gradle**：4 个源文件、6 个资源文件，Gradle 光 AGP + 依赖就得几百 MB 还得联网。手工链路从零到出包 3 秒。

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ANDROID_SDK` | `/f/AndroidSdk` | SDK 根目录 |
| `JAVA_HOME` | `/c/Program Files/Zulu/zulu-21` | JDK 17+ |
| `BINF_KS_PASS` | `binf123456` | 签名密钥口令 |

### 签名

密钥在 `signing/binf.keystore`，**不进 git**（`.gitignore` 排掉了）。首次构建时如果不存在会自动生成。

**这个密钥丢了会导致签名变化**，手机必须先卸载旧版才能装新版，设置数据全丢。所以本机要自己备份。

---

## 测试

```bash
bash run_tests.sh
```

两组共累计 208 项：

| 组 | 项数 | 测什么 |
|---|---|---|
| 编解码核心 | 90 | `BinCore` 的双向转换、分隔符、编码回退、阈值判定、长度上限、hex 不截断、Base64 编解码与判定 |
| 静态审查 | 118 | 读源码做约束检查（见下） |

### 为什么有「静态审查」这一组

悬浮窗有几个**编译期查不出来、运行期才炸**的约束：

- 面板窗口如果误加了 `FLAG_NOT_FOCUSABLE`，读剪切板永远返回空，而且不报错
- 气泡如果没加 `FLAG_NOT_FOCUSABLE`，点击会被窗口自己吃掉
- 清单里如果残留 `BIND_INPUT_METHOD`，安装时会被当输入法
- 输入法时代的 `InputMethodManager` / `PinyinEngine` 引用如果没清干净，编译能过但会崩

`StaticAudit.java` 直接读 `app/src/...` 下的源码文本做断言，包括**反向检查**（`!contains("BIND_INPUT_METHOD")`）。改代码时如果破坏了这些约束，回归会红。

**坑**：计数时要把 `//` 注释去掉，否则注释里提到 `FLAG_NOT_FOCUSABLE` 也会被算进去（`stripComments()` 就是干这个的）。

### 判定必须同时看退出码和断言数

`run_tests.sh` 里的 `run()` 函数除了看退出码，还校验**实际跑了多少项**：

```bash
elif [ "$pass" -lt "$expect_min" ]; then
  echo "  ✗ 只跑了 $pass 项，少于预期的 $expect_min 项"
```

这是被坑过的：早期版本编译失败时一行没跑，`pass=0 fail=0`，脚本照样打印「全通过」。

### 所有测试都在项目根目录跑

`StaticAudit` 读的是 `app/src/com/binf/floater/...` 相对路径。如果 `cd` 到 `build/r_xxx` 里跑会 `NoSuchFileException`。

---

## 悬浮窗设计取舍

### 为什么是「点一下读」而不是自动监听

Android 10（API 29）起，**没有输入焦点的应用读剪切板一律返回空**。Android 12 之后 `hasPrimaryClip()` 直接返回 false。这不是权限问题，是系统硬限制，`SYSTEM_ALERT_WINDOW` 也绕不过。

绕过办法只有三条：

| 方案 | 代价 |
|---|---|
| 抢焦点（本项目） | 读之前弹出获得焦点的窗口，会打断当前输入 |
| `READ_LOGS` | 需要 adb 授权 |
| Shizuku | 需要额外装 App 并每次重启后重新授权 |

选了第一条，做成按需触发：`showPanel()` 成功后 `handler.postDelayed(this::readClipIfPending, CLIP_DELAY_MS)`，`CLIP_DELAY_MS = 120`。

**为什么是 120ms**：`wm.addView(panel, panelLp)` 返回时窗口还没真正拿到焦点，立刻读剪切板会读到空。120ms 是实测能稳定拿到焦点的最短延迟，再短就偶发空值。

**为什么要 `autoReadPending` 标记**：面板重绘（比如切方向、改输入）时不能反复读剪切板，否则会覆盖用户正在编辑的内容。

### 面板不能加 `FLAG_NOT_FOCUSABLE`

```java
// 气泡：不要焦点，点击要能穿透到自己的 onClick
bubbleLp.flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS;

// 面板：必须能拿焦点，否则读剪切板永远是空
panelLp.flags = FLAG_NOT_TOUCH_MODAL;
```

`FLAG_NOT_TOUCH_MODAL` 的作用是让面板外的触摸事件传给下层窗口，这样面板不会挡住整个屏幕。

### 拖动

`setupDrag()` 用 `onTouch` 处理 `ACTION_DOWN`/`ACTION_MOVE`：

- `DOWN` 记 `downX/downY`（`getRawX/Y`）和 `startX/startY`（`lp.x/lp.y`），**返回 false** 让点击事件照常派发
- `MOVE` 超过 `dp(6)` 才置 `moved = true` 并 `updateViewLayout`，避免手指轻微抖动被当成拖动
- 气泡拖动时同步面板位置（`syncPanelPos()`）

用 `getRawX()` 而不是 `getX()`：后者是相对于父容器的，窗口移动后坐标会跳。

### 关闭按钮只收面板，不停服务

面板右上角那个 `✕` 一开始写的是 `hidePanel() + stopSelf()`。`stopSelf()` 会停掉整个
前台服务，悬浮球跟着一起消失——用户的理解是「点了叉把面板收起来」，结果连入口都没了，
只能回主界面重新打开。这不是预期行为。

现在拆成两个动作：

- **点一下** → 只 `hidePanel()`，悬浮球留着，随时能再点开
- **长按** → `hidePanel() + toast + stopSelf()`，这才是彻底关掉

要彻底关掉也可以回主界面关「悬浮窗」开关，那条路一直存在。

### 服务死活要和开关状态对上

`floating_enabled` 这个偏好决定主界面显示「✅ 悬浮窗运行中」还是「⚙️ 没开」。
它以前只在主界面的开关回调里写，于是长按 `✕` 走 `stopSelf()` 时没人更新它——
服务已经没了，主界面还理直气壮说「运行中」，桌面上却找不到小球。系统低内存回收
前台服务也是同一条路径。

现在改成服务自己维护：

```java
onCreate()  → setEnabledPref(true)
onDestroy() → setEnabledPref(false)
```

主界面的开关回调照旧写一次（用户点开关的即时反馈），服务这边兜住「服务实际死活」
这个事实源。两边写同一个值，谁后写都不会写错。

回填开关时有个副作用要防：`setChecked()` 会触发 `OnCheckedChangeListener`，不屏蔽的话
一次「同步状态」会顺带再 start/stop 一次服务。用 `syncingSwitch` 标志位挡住。

静态审查从 47 项加到 103 项（+56），守着本轮修掉的 17 个 bug、16 条复审问题、3 个真机 UI bug 的回归，以及球外观 / 历史 / 面板拖动 / Base64 / 主题 / 历史转义的约束。

### 方向自动识别与标题栏的两个按钮

方向不是一个四向轮转的字符串，而是**两个正交的轴**：

| 轴 | 取值 | 由谁改 |
|---|---|---|
| `decodeDir` | 正向（文字→编码）/ 反向（编码→文字） | 方向按钮、自动识别 |
| `codecB64` | 二进制 / Base64 | 编码按钮、自动识别（仅解码时） |

`dir` 是这两个布尔派生出来的缓存，`refresh()` / 历史 / 文案都读它。

标题栏因此放两个按钮而不是一个：

- **方向按钮**（`dirBtn`）三态循环：`自动 → 文→码 → 码→文 → 自动`
- **编码按钮**（`codecBtn`）两态循环：`BIN ⇄ B64`

自动识别挂在方向按钮的「自动」这一档上。这么做是因为自动识别以前有两个洞：

1. **只对剪切板生效。** 判定写在 `readClip()` 里，手打一串 `0/1` 不会被识别，
   还是当成文字去编码。现在判定挪进 `refresh()`，输入一变就判。
2. **没有 UI，只能靠「关面板复位」兜底。** 以前 `dirPinnedByUser` 一旦置真，
   自动识别在整个服务生命周期里都失效，只能收起面板再打开才能恢复。
   现在「自动」是按钮上的显式一档，用户看得见、点得回去，不需要复位兜底了。

自动识别的三条规则：

```java
bin2text → 反向，二进制解码
b64text  → 反向，Base64 解码
其它     → 正向，编码目标取 codecB64
```

正向的编码目标**始终**由 `codecB64` 决定，不因为「输入里出现了 0/1」就被改掉 ——
用户手动选的 Base64 不该被一段二进制悄悄改回二进制。

界面上的编码按钮读的是 `shownCodecB64()`（从 `dir` 推），不是 `codecB64`：
自动识别判定成 Base64 解码时实际生效的是 `dir` 里的格式，只看 `codecB64`
会出现「按钮写着 BIN、实际按 Base64 解」。

退出自动时（方向按钮第一下）先把 `codecB64 = shownCodecB64()` 接住当前生效格式，
再翻成正向。不接的话，自动解的是 Base64、按钮写着 B64，一点却跳成 BIN。

`looksLikeBinary()` 的阈值设计：

- **少于 16 位一律不当二进制**（`MIN_BITS = 16`）
- **少于 24 位时还要求同时含 0 和 1**（`2024` 这种纯数字不该被当二进制）
- 先 `cleanBits()` 去掉所有分隔符再数位数

### 按钮按下态

所有按钮（悬浮窗小按钮、操作按钮、长按菜单条目、历史胶囊、主界面按钮）
背景都走 `Palette.pressedBg(正常色)`，返回一个 `StateListDrawable`：
常态就是原来的纯色，按下时往白（深色主题）或往黑（浅色主题）混 16%。

以前全是 `setBackgroundColor(单色)`，`TextView` 本身没有按下反馈，
点下去屏幕一动不动，用户不知道点到没有，只好连点几下。

用 `StateListDrawable` 而不是 `RippleDrawable`：悬浮窗是
`TYPE_APPLICATION_OVERLAY` 窗口，ripple 要有宿主 View 的涟漪边界，
在这种窗口里容易画不出来。

### 标题栏的空间账

面板宽 `min(292dp, screenW * 0.9)`，这台机器 360dp 宽 → 292dp，
减去左右 padding 24dp，可用 268dp。标题栏原来已经有「8位 / ⇄ / ✕」三个按钮，
再加一个「BIN/B64」会把标题挤没。

所以显示格式按钮（`modeBtn`）从标题栏让位到第二行，和方向说明并排：

```
[二进制悬浮窗]        [自动] [BIN] [✕]     ← 标题栏（也是拖动把手）
[自动识别：文字 → 二进制]           [8位]   ← 第二行
```

格式按钮只在「文字 → 二进制」时可点，其余方向用 `setAlpha(0.4f)` 淡出。

---

## 编解码核心

`BinCore.java` 全是 static 方法，**不 import 任何 `android.*`**，所以能直接在桌面 JVM 上跑单测。

```java
textToBinary(String text, boolean compact)   // 文字 → 0/1 串
binaryToText(String bits)                    // 0/1 串 → DecodeResult
looksLikeBinary(String s)                    // 判断要不要按二进制处理
describe(String text)                        // "N 字符 · M 字节"
textToHex(String text)                       // 文字 → 十六进制
textToBase64(String text)                    // 文字 → Base64
base64ToText(String b64)                     // Base64 → DecodeResult
looksLikeBase64(String s)                    // 判断要不要按 Base64 处理
bitsToBase64(String bits)                    // 0/1 串 → Base64
renderBits(String bits, String mode)         // 按显示模式渲染
autoDetect(String s)                         // 返回方向
```

### Base64 判定的难点

Base64 的字母表就是普通英文字母，任何 4 个字母都能「解」出 3 个字节，
所以不能只看字符集。判据是**解出来之后像不像正常文本**：`scoreText()`
给常用汉字 / ASCII 可打印 / emoji 加分，给控制字符 / 私用区扣分，
分数不够就判定不是 Base64。这样 `test`、`HelloWorld` 不会被误判。

顺带修掉一个真实 bug：`scoreText()` 早期把 emoji 的代理对当「可疑字符」各扣 2 分，
导致纯 emoji 的 Base64 解出来被判成「不像正文」而被拒。现在代理对会合并成
一个 code point 打分，emoji 区间加分。

### 编码回退顺序

`bytesToText()` 依次尝试 **UTF-8 → GBK → Big5 → ISO-8859-1**，用 `CharsetDecoder` 的 `CodingErrorAction.REPORT` 严格模式，解码失败抛异常就换下一个。

最后用 ISO-8859-1 兜底是因为它**永远不会失败**（单字节映射），保证任意字节序列都有输出。

### 分隔符

```java
static final Pattern SEPARATOR = Pattern.compile("[\\s,;:_\\-|/.\\\\]+");
static final Pattern ZERO_B = Pattern.compile("(?i)0b");
```

先 `ZERO_B.matcher(s).replaceAll("")` 去掉 `0b` 前缀，再按 `SEPARATOR` 切。这样空格分组、逗号分组、完全连写都能吃。

---

## 目录结构

```
app/
  AndroidManifest.xml          4 个权限，一个前台服务
  res/values/strings.xml       App 名称
  res/values/colors.xml        配色
  res/values/styles.xml        主题
  res/mipmap-*/ic_launcher.png 图标
  src/com/binf/floater/
    BinCore.java               编解码核心（含 Base64）
    FloatingService.java       悬浮球 + 面板 + 历史 + 拖动
    MainActivity.java          设置界面（主题 / 历史 / 球外观）
    Palette.java               深浅色两套调色板
tools/
  CoreTest.java                90 项编解码测试
  StaticAudit.java             118 项源码约束检查
  push_github.sh               推送脚本
signing/
  README.md                    密钥说明（密钥本身不进 git）
build.sh                       手工构建
run_tests.sh                   一键回归
README.md                      项目首页（面向使用者）
DEVELOPMENT.md                 本文件（面向开发者）
CHANGELOG.md                   更新日志（每个版本改了什么）
```

---

## 版本管理

仓库里进什么、不进什么：

| 进 git | 不进 git |
|---|---|
| 源码、资源、清单 | `build/` 全部中间产物 |
| `build.sh` / `run_tests.sh` | `dist/*.apk`（纯二进制，dex 一变整份存） |
| `tools/` 下测试与脚本 | `signing/*.keystore`（签名密钥） |
| `README.md` / `DEVELOPMENT.md` / `CHANGELOG.md` / `LICENSE` | `.idea/`、`.vscode/`、`__pycache__/` |

**`dist/*.apk` 不进 git 的理由**：APK 是纯二进制，dex 一改整个文件就变，git 整份存储。几十个版本就是几百 MB。要发布用 GitHub Releases 挂附件。

**`signing/*.keystore` 不进 git 的理由**：任何人拿到都能签出同包名同签名 APK，手机会当正常更新装进去。这个文件曾经被提交过，后来用 `git filter-branch` 从全部历史里清掉了。

### 什么时候才开新版本号

**不是修一个 bug 就发一个版本。** 这个项目是给一个人用的工具，版本号太碎没有意义，
用户也不会为了一个开关状态的小修去重新下载 APK。

约定：

- 攒着。同一批「同一类问题」的修复合成一个 tag，一个 Release 只挂一份 APK
- 已经发出去的版本发现还有同类问题，**就地把 tag 移过去、覆盖 Release 里的 APK**，
  不新开 v2.1.1 / v2.2。`git tag -d v2.1 && git tag -a v2.1` 然后
  `git push -f origin refs/tags/v2.1`，Release 的 `delete-asset` + `upload --clobber`
- 版本号（`AndroidManifest.xml` 的 `versionCode` / `versionName`、`build.sh` 里的
  `VERSION_CODE` / `VERSION_NAME`）只在真正要发新版本时才动，平时修 bug 不动它
- 真要开新版本时，`versionCode` 必须递增，否则手机上装不上

判断标准很简单：**用户需不需要知道这个版本存在**。内部修复、行为微调 → 不需要。

版本号现状：`2.4 (4)`。v2.0 是输入法改悬浮窗，v2.1 是 `✕` 语义与服务状态对齐，
v2.2 / v2.3 是同一轮里的功能与 bug 批次 —— 前面几轮按上面的约定都没动版本号，
直到 v2.3 一次性加了五项用户可见功能（球外观 / 历史 / 面板拖动 / Base64 / 主题），
用户需要知道这个版本存在，才把 `2` → `3`、`2.0` → `2.3` 一起补上。
v2.4 把标题栏拆成「方向 / 编码」两个按钮、并把方向自动识别做成按钮上的一档，
也是用户看得见的变化，于是 `3` → `4`、`2.3` → `2.4`。

**注意 App 里显示的版本号是从 `PackageManager` 读的**，不升 `versionCode` 就永远是旧值，
所以「改了功能但忘了升版本」在界面上是看得见的 —— 这也算一种自检。

### 行尾

`.gitattributes` 约定 `* text=auto eol=lf`，`*.bat text eol=crlf`，png/apk/keystore/gz/dex 标 binary。

---

## 从输入法改成悬浮窗（v2.0）

v1.x 是个安卓输入法（`com.binf.ime`），带 54.8 万条离线拼音词库、九键、整句解码、云端补词，467 项测试。后来判断输入法这条路线对「二进制转换」这个需求来说太重了——用户要的是「看一眼这串 0/1 是什么字」，不是「在输入框里敲二进制」。

v2.0 的改动：

**删掉的**

- `BinImeService.java`（输入法服务）
- `PinyinEngine` / `PinyinComposer` / `T9Engine` / `SentenceDecoder` / `CloudSuggest`（拼音与云补词）
- `app/assets/pinyin.dict.gz`（8MB 词库）
- `app/res/xml/method.xml`
- 18 个测试/工具脚本
- 清单里的 `BIND_INPUT_METHOD` 权限、`android.view.InputMethod` intent-filter、`INTERNET`、`ACCESS_NETWORK_STATE`

**保留的**

- `BinCore.java` 一字未改（编解码逻辑本来就与输入法无关）
- `FloatingService.java` 的悬浮球与面板骨架

**结果**

| | v1.2 | v2.0 |
|---|---|---|
| 包名 | `com.binf.ime` | `com.binf.floater` |
| APK 大小 | 8.1 MB | 29 KB |
| `classes.dex` | 105 KB | 29 KB |
| 源文件 | 9 个 | 3 个 |
| 测试断言 | 467 项 | 80 项（当前 208） |
| 权限 | 6 个（含 INTERNET） | 4 个 |

输入法代码全部在 git 历史里（v1.2 的 tag/commit），需要的话能翻出来。

---

## 踩过的坑

**1. 面板读不到剪切板，且不报错**

面板窗口加了 `FLAG_NOT_FOCUSABLE`，`getPrimaryClip()` 返回 null。没有任何异常，就是空。修法：面板只加 `FLAG_NOT_TOUCH_MODAL`。

**2. 窗口刚 addView 就读剪切板，读到空**

`wm.addView()` 返回不等于窗口已经拿到焦点。加了 `postDelayed(..., 120)` 才稳定。

**3. 拖动时坐标跳变**

用 `event.getX()` 算位移，窗口一动父容器坐标系就变，越拖越偏。改成 `getRawX()`（屏幕绝对坐标）。

**4. 点击被拖动逻辑吃掉**

`ACTION_DOWN` 直接返回 true，`onClick` 就再也不触发了。修法：`DOWN` 返回 false，只在 `MOVE` 超过阈值时才算拖动。

**5. 静态审查的计数被注释污染**

面板那段代码的注释里写了 `FLAG_NOT_FOCUSABLE`（解释为什么不能用），`countOccurrences` 把它也算进去了，断言误报。加了 `stripComments()`。

**6. `python - <<'EOF'` heredoc 里的转义**

用 heredoc 生成 Java 代码时，脚本里的 `src.split("\n")` 被 shell/heredoc 处理成了真实换行，生成出非法 Java。改成先写 `/tmp/xxx.py` 文件再 `python /tmp/xxx.py` 执行。

**7. 回归测试假绿**

编译失败时一行测试没跑，`pass=0 fail=0`，脚本仍然打印「全通过」。修法：`run()` 同时校验退出码和断言数下限。

### 第二轮审计（v2.2 之后）修掉的 16 条

这轮是让子 agent 只读通读四个源文件查出来的，有几条是自己写的时候完全没意识到的：

**1. 面板拖动从来没生效过** —— `setupPanelDrag` 的 `ACTION_DOWN` 返回 `false`，而 `head` 是不可点击的 `LinearLayout`、又嵌在 `ScrollView` 里：DOWN 没人消费就被 ScrollView 拿去当滚动起点，后续 MOVE 全被 `onInterceptTouchEvent` 截走，`head` 只收到 `ACTION_CANCEL`。对比悬浮球能拖 —— 它有 `setOnClickListener` 所以 clickable，`onTouchEvent` 返回 true 才收得到 MOVE。**修法：DOWN 直接 `return true`**；标题栏里的按钮自己是 clickable，会先消费 DOWN，不受影响。

**2. 悬浮窗关着时点外观按钮会凭空启动服务** —— `applyLookToService()` 直接 `startService(ACTION_REFRESH_LOOK)`，服务没跑时系统会新建实例，`onCreate` 里 `setEnabledPref(true)` + `startForegroundSafe()` + `createBubble()`。于是点一下「球大一点」就冒出悬浮球和常驻通知，开关却还显示「关」。**修法：先判 `isRunning()` 再发 Intent。**

**3. 换主题时剪切板覆盖用户手打的内容** —— `recolorPanel()` 把 panel 置 null 后调 `showPanel()`，`showPanel` 无条件 `autoReadPending = true` 并 120ms 后读剪切板；`recolorPanel` 随后才 `input.setText(keep)` 恢复内容，120ms 后又被剪切板整段替换。**修法：加 `suppressAutoReadOnce` 标志，内部重建时不读。**

**4. 点一次历史胶囊，自动识别方向永久失效** —— chip 的 onClick 里 `dirPinnedByUser = true`，而 `readClip` 的自动判定包在 `if (!dirPinnedByUser)` 里，该标志在全服务生命周期内再不复位。之后每复制一段新内容都按旧方向转，面板显示「无法解码」。**修法：`hidePanel()` 里复位 —— 「用户手动选的方向」只对本次面板有效。**

**5. 球贴边后调大，右半超出屏幕** —— `relayoutBubble()` 先 `clampBubble()`，而 clamp 用 `bubbleW()`：`bubble.getWidth()` 此刻还是**旧宽度**（`setPadding`/`setTextSize` 只是 requestLayout，下一帧才重排）。**修法：`bubble.post()` 再夹一次。**

**6. Base64 判定和解码口径不一致** —— `looksLikeBase64` 用**原始长度**卡 `% 4`，而 `base64ToBytes` 走 `normalizeBase64` 会自动补 padding，于是 `SGVsbG8`（7 字符）自动识别认不出、手动解码又能解。而且判定没有长度上限，超长串被判成 `b64text` 后解码只报「内容过长」。**修法：判定统一用补齐后的长度，并和 `base64ToText` 一样卡 `MAX_BITS/8` 字节；同时只解一次（原来 probe 解一遍、正式再解一遍，长串在主线程白解两遍）。**

**7. 读剪切板无长度上限** —— 几百 KB 文本整段塞进 EditText，再同步跑 `cleanBits` 的正则替换和 Base64 试探解码，全在主线程。**修法：截到 `MAX_CLIP_CHARS = 50000` 并明确提示被截断；超过 `MAX_AUTODETECT_CHARS = 20000` 直接按「文字 → 二进制」处理，不做自动识别。**

**8. 面板夹到 62% 高度后永远缩不回去** —— 只在「变高」时把 `lp.height` 钉成 cap，之后 `panel.getHeight()` 恒等于 cap，条件 `h > cap` 永远不成立，清空输入后仍是一块 62% 屏高的空壳。**修法：内容缩回 cap 以内时把高度改回 `WRAP_CONTENT`。**

**9. 历史条目分隔符没转义** —— 条目用 `dir + "\u0002" + 内容` 拼接、整体用 `\u0001` 分隔。用户从别的 App 复制来带这两个控制字符的内容时，`split` 会把一条拆成几条，chip 的 `indexOf('\u0002')` 也切错位置。**修法：`escapeHist()` / `unescapeHist()`，把 `\` `\u0001` `\u0002` 都转义掉。**

**10. 清空历史后一收面板又冒出一条** —— `hidePanel()` 无条件 `recordHistory(当前输入)`，把刚清掉的又写回去。**修法：`skipHistoryOnHide` 标志。**

**11. 浅色主题下悬浮球几乎看不见** —— 球背景取 `withAlpha(pal.bg, ...)`，浅色 `pal.bg` = `0xFFF4F6FB`（近白），叠在浅色桌面上没有描边、没有对比。**修法：改用 `GradientDrawable` 画圆角 + 一圈 `withAlpha(pal.accent, 55)` 描边，任何壁纸上都能看出轮廓。**

**12. 刚开开关又立刻切回前台，prefs 被写成 false** —— 服务从 `startForegroundService` 到 `onCreate` 把 `running` 置 true 之间有空档，这期间 `isRunning()` 还是 false；`onResume` 的 `syncEnabledPref()` 照着 false 写回 prefs，开关显示「关」可球已经出来了。**修法：`pendingStartUntil` 宽限期，期间不覆盖 prefs。**

**13. 外观按钮整页重建丢滚动位置** —— 每点一次「球大一点」就 `setContentView(buildUi())`，滚动位置打回顶部。**修法：`rebuildUiKeepingScroll()` 记下 `getScrollY()`，重建后 `post` 回去。**

**14. `renderBits` 支持 `base64` 档但 UI 循环里没有它** —— prefs 里遗留/被外部写成 `base64` 时，输出是 Base64、按钮却显示「8位」，点一下就被转回 `group8`。**修法：`cycleRenderMode` 补第五档，`modeLabel` 加 `"B64"`。**

**15. 历史截断 120 字符后回填失真** —— 用户以为点回来的是原文，实际只有前 120 字符。**修法：chip 上标省略号，并在 README「已知限制」里写明。**

**16. 开关回填与 `onResume` 竞态**（同 12 的另一个入口）—— 一并由 `pendingStartUntil` 兜住。

### 真机验收又踩到 3 个 UI bug

代码看着没问题、单测和静态审查全绿，装到手机上一摸就露馅：

**1. 「8位」按钮整块空白** —— 非「文字 → 二进制」方向下这个按钮用不上（二进制显示格式只对那条方向有效），代码把它「灰掉」：

```java
modeBtn.setTextColor(usesRenderMode() ? pal.muted : pal.cardHi);  // ← 错
```

`pal.cardHi` 正好是按钮自己的背景色，文字和背景融成一片，按钮看起来像消失了。**修法：`setAlpha(0.4f)` 淡出，不动文字颜色。**

教训：**禁用态不要靠改文字颜色实现**，尤其别用一个可能和背景同色的值。要么 `setAlpha`，要么换一个真正的 disabled 色。

**2. 主界面外观档位不同步** —— 长按悬浮球菜单也能改球大小 / 不透明度（服务侧写同一个 `bubble_scale` / `bubble_alpha` 键）。主界面此时在后台、不会重建，回前台 `onResume` 只刷状态文字和开关，卡片上还印着「大小：中」，可球已经是「大」了。

**修法**：`buildUi()` 记下这次画的是哪两档（`shownScale` / `shownAlpha`），`onResume` 比对 prefs，不一致就重建界面。

教训：**同一个设置项有多个写入口时，每个显示它的界面都要有「回前台重新对齐」的机制**，不能只在 `onCreate` 读一次。

**3. 面板收着时换主题，再点开还是旧配色** —— `hidePanel()` 只 `wm.removeView(panel)`，**不置 `panel = null`**，那棵 View 树一直缓存着。`applyLookRefresh()` 只在 `panelVisible` 时才 `recolorPanel()`，收着的时候只把字段 `pal` 换掉了，旧 View 树没人管；`showPanel()` 又直接复用 `panel`（它只判 `panel == null` 才重建），于是「主界面已经变浅色，点开面板还是黑的」。

**修法**：`applyLookRefresh()` 里 `themeChanged && !panelVisible` 时把 `panel` 置 null，逼下次 `showPanel()` 走 `buildPanel()`。

教训：**`removeView()` ≠ 释放**。一个「按需创建、缓存复用」的 View 树，凡是改动配色/字号/布局这类全局外观的路径，都要么就地重刷、要么把缓存丢掉；只改字段不改 View，缓存的那份就是脏的。

**这轮的教训**：静态审查 103 项全是 `contains` 字符串匹配，**上面一条也测不出**。字符串断言能守「结构不能变」，守不住「行为对不对」。像「DOWN 返回 true/false」「先 clamp 还是先重排」这种顺序问题，只有通读代码或真机才能发现。所以流程改成：改完代码先让子 agent 只读通读一遍，再自己逐条核对行号，最后才跑测试、装真机点一遍。

---

## 后续可做

- **开机自启**：加 `RECEIVE_BOOT_COMPLETED` + 一个 BootReceiver，重启后自动把球放出来（用户当前没要）
- **多编码手动切换**：现在自动回退，可以给个下拉让用户指定（目前只能靠回退顺序碰运气）
- **粘贴即转**：面板打开后监听输入框的 paste 事件
- **批量文件**：把剪切板里的整段 hex dump 批量转成文件（要写存储权限，暂不做）
- **历史加密**：现在转换历史明文存在 App 私有目录，要更稳妥可以上 EncryptedSharedPreferences
- **自定义球图标**：现在只有 `01` / `BIN` 两行字，可以让用户换字
- **平板 / 折叠屏专用布局**：宽度已经跟着屏幕走（`min(292dp, 屏宽 90%)`），
  但没有 `sw600dp` 目录，横屏大屏上按钮仍然偏挤
- **自动读剪切板失败时的提示**：自动路径（`silent=true`）读不到内容时完全静默。
  没做是因为「点开面板必然读一次」，读不到就说明剪切板本来就空，弹提示反而是噪音；
  真需要的话加 300ms 重试 + 仅失败时 toast

### 评估过、暂不做的

这两条在真机验收后讨论过，结论是先不做，理由记在这里免得以后反复纠结：

- **把拖动/面板坐标抽成纯函数单测**。真机上出过两次问题，但都不是算术错：
  一次是 `ACTION_DOWN` 返回 `false` 被 `ScrollView` 截走（事件分发），
  一次是 `removeView()` 之后缓存了旧 View 树（生命周期）。这两类 bug
  纯函数单测一条也测不出来。真要是坐标算错，`clamp` 那几行静态审查就能盯住。
- **`run_tests.sh` 的健壮性**。现在断言下限还是手写常量、失败判定还是
  `grep -c FAIL`。可改动收益有上限：真正会让它假绿的「编译失败但打印全通过」
  在 v2.2 已经修过了（`run()` 同时校验退出码和断言数下限），剩下的只是难看，不是不对。
