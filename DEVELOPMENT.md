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

**为什么不用 Gradle**：3 个源文件、4 个资源，Gradle 光 AGP + 依赖就得几百 MB 还得联网。手工链路从零到出包 3 秒。

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

两组共累计 129 项：

| 组 | 项数 | 测什么 |
|---|---|---|
| 编解码核心 | 53 | `BinCore` 的双向转换、分隔符、编码回退、阈值判定、长度上限与 hex 不截断 |
| 静态审查 | 76 | 读源码做约束检查（见下） |

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

静态审查加 29 项守着（47 → 76，含本轮修掉的 17 个 bug 的回归断言）。

### 方向自动识别

```java
textToBin = !BinCore.looksLikeBinary(text);
```

`looksLikeBinary()` 的阈值设计：

- **少于 16 位一律不当二进制**（`MIN_BITS = 16`）
- **少于 24 位时还要求同时含 0 和 1**（`2024` 这种纯数字不该被当二进制）
- 先 `cleanBits()` 去掉所有分隔符再数位数

---

## 编解码核心

`BinCore.java` 全是 static 方法，**不 import 任何 `android.*`**，所以能直接在桌面 JVM 上跑单测。

```java
textToBinary(String text, boolean compact)   // 文字 → 0/1 串
binaryToText(String bits)                    // 0/1 串 → DecodeResult
looksLikeBinary(String s)                    // 判断要不要按二进制处理
describe(String text)                        // "N 字符 · M 字节"
textToHex(String text)                       // 文字 → 十六进制
autoDetect(String s)                         // 返回方向
```

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
    BinCore.java               编解码核心
    FloatingService.java       悬浮球 + 面板
    MainActivity.java          设置界面
tools/
  CoreTest.java                53 项编解码测试
  StaticAudit.java             76 项源码约束检查
  push_github.sh               推送脚本
signing/
  README.md                    密钥说明（密钥本身不进 git）
build.sh                       手工构建
run_tests.sh                   一键回归
```

---

## 版本管理

仓库里进什么、不进什么：

| 进 git | 不进 git |
|---|---|
| 源码、资源、清单 | `build/` 全部中间产物 |
| `build.sh` / `run_tests.sh` | `dist/*.apk`（纯二进制，dex 一变整份存） |
| `tools/` 下测试与脚本 | `signing/*.keystore`（签名密钥） |
| `README.md` / `DEVELOPMENT.md` / `LICENSE` | `.idea/`、`.vscode/`、`__pycache__/` |

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
| 测试断言 | 467 项 | 89 项 |
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

---

## 后续可做

- **历史记录**：记最近 N 次转换结果
- **多编码手动切换**：现在自动回退，可以给个下拉让用户指定（目前只能靠回退顺序碰运气）
- **粘贴即转**：面板打开后监听输入框的 paste 事件
- **悬浮球样式**：现在只有 `01` / `BIN` 两行字，可以做透明度 / 尺寸档位
- **批量文件**：把剪切板里的整段 hex dump 批量转成文件（要写存储权限，暂不做）
