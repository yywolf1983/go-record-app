# KataGo 内置资源加载说明

本项目将 KataGo 引擎以**完全 JNI / lib 方式**集成：

- 引擎核心源码（`/Users/yy/github/KataGo/cpp`）连同自写的 JNI 封装
  `katago_jni.cpp`，**由 Android NDK 直接编译进 `libkatago.so`**，随 APK 打包。
  App 通过 `System.loadLibrary("katago")` 加载，**无任何子进程、无需 katago 二进制、
  无需 libOpenCL.so、无需 OpenCL 驱动**。
- KataGo 使用 **EIGEN 后端（纯 CPU）** 编译（`-DUSE_BACKEND=EIGEN`），
  对设备无 GPU/OpenCL 要求，兼容性最好。
- 仅**模型权重(.bin.gz)** 与**配置文件(gtp_android.cfg)** 仍放在 `assets/`，
  运行时解压到应用私有缓存；启动时把绝对路径传给 native `initialize`。

> ⚠️ **模型权重（大文件）已被 `.gitignore` 忽略，不进入 git 仓库。**
> 克隆仓库或切换分支后，必须按本文「二」把它复制回项目，否则构建/运行会失败。

---

## 一、架构（为什么是 lib 方式）

KataGo 官方 `android_lib_dist` 提供的 `libkatago.so` **只是一个核心库，
没有 JNI 符号**，无法直接 `System.loadLibrary` 调用。本项目改为：

1. 把 `cpp/katago_jni.cpp`（实现 `Java_com_gosgf_app_engine_KataGoEngine_*`
   四个 native 方法）加入 `cpp/CMakeLists_library.txt` 的
   `add_library(katago SHARED ...)` 源列表；
2. `externalNativeBuild` 直接指向 `cpp/CMakeLists_library.txt`，
   用 NDK + EIGEN 后端编译出**含真实 JNI 符号的 `libkatago.so`**；
3. JNI 内部复用 KataGo 公开的 `AsyncBot` + `Search::getAnalysisJson`，
   返回与官方 `analysis` 协议**完全一致**的 JSON（rootInfo / moveInfos），
   因此 App 端解析逻辑无需改动。

---

## 二、被忽略的文件清单

| 文件 | 路径 | 来源 | 是否入库 |
|------|------|------|----------|
| 模型权重 | `app/src/main/assets/kata1-b20c256x2-s5303129600-d1228401921.bin.gz` | 下载的权重文件 | ❌ 忽略 |
| 配置文件 | `app/src/main/assets/gtp_android.cfg` | 已手写 | ✅ 入库（无需复制） |

> `libkatago.so` 由 NDK 构建自动生成并打包进 APK，**不需要也不允许**手动放置。

### 复制命令（仅模型权重，一次性）

在 **`go-record-app/` 目录下** 执行：

```bash
# 复制模型权重（来自下载目录）
cp /Users/yy/Downloads/kata1-b20c256x2-s5303129600-d1228401921.bin.gz \
   app/src/main/assets/

# 配置文件 gtp_android.cfg 已入库，无需复制
```

> 替换其它 `.bin.gz` 模型时，需同步修改：
> - `app/src/main/assets/` 文件名
> - `KataGoEngine.java` 中的 `MODEL_ASSET` 常量
>
> 编译依赖（宿主机）：
> - Android NDK：`/Users/yy/Downloads/android-ndk/NDK`（已 build.gradle `ndkPath` 指定）
> - Eigen3 头文件：`brew install eigen`（构建参数 `EIGEN3_INCLUDE_DIRS` 指向
>   `/opt/homebrew/include/eigen3`）
> - KataGo 源码：`/Users/yy/github/KataGo`（已 build.gradle `externalNativeBuild.cmake.path` 指定）
> - CMake 版本需 ≥ 3.22

---

## 三、配置说明（gtp_android.cfg）

该文件已入库，内容针对移动端调优。运行时 `KataGoEngine.prepare()` 会把它
解压到应用缓存，并把其中 `model =` 行重写为缓存里模型文件的绝对路径，无需手动改路径。

```
model = kata1-b20c256x2-s5303129600-d1228401921.bin.gz
maxVisits = 500
numSearchThreads = 2
nnBatchSize = 8
rules = chinese
boardSize = 19
backend = eigen
logLevel = WARNING
```

---

## 四、加载机制（代码侧）

- `com.gosgf.app.engine.KataGoEngine`
  - static 块：`System.loadLibrary("katago")` 加载随包 JNI 库
  - native 方法：`initialize(modelPath, configPath)` / `getLastError()` /
    `analyzePosition(requestJson)` / `close()`（实现见 `cpp/katago_jni.cpp`）
  - `prepare(context)`：把 assets 中的模型 + 配置解压到缓存，重写 cfg 的 model 路径，
    调用 native `initialize` 加载神经网络权重并构建 `AsyncBot`
  - `analyze(...)`：把局面（走子序列、棋盘大小、maxVisits）组装成 KataGo
    `analysis` 协议 JSON，调用 native `analyzePosition`，得到与官方同格式的
    `rootInfo` / `moveInfos` JSON 后解析为候选着法
- `MainActivity`
  - 「初始化引擎」按钮 → 首次 `prepare()`（解压模型 + 加载权重，模型 83MB 约需几秒）
  - 「分析局面」按钮 → 若未准备则先 `prepare()`，再分析并标记棋盘 + 弹窗显示胜率

---

## 五、构建与运行

```bash
cd go-record-app
./gradlew assembleDebug
```

安装 APK，打开 App：
1. 点击「初始化引擎」（首次加载模型权重，约需几秒）
2. 加载/新建一局棋谱，走到想分析的局面
3. 点击「分析局面」→ 棋盘标注推荐点 + 弹窗显示胜率/领先目数/候选列表

> 因为是纯 lib 方式，**无需 SAF 选目录、无需 setExecutable、无 SELinux 限制**。

---

## 六、常见问题

- **`模型文件缺失`**
  → 模型被 git 忽略后丢失，回到「二」执行复制命令。
- **`引擎初始化失败`**
  → 查看 logcat 中 `getLastError()` 返回的 C++ 异常；通常是模型路径/格式问题，
    或 Eigen 后端与模型不匹配。
- **分析很慢 / 卡顿**
  → EIGEN 是纯 CPU 后端，移动端建议在 cfg 中把 `maxVisits` 调低（如 200），
    `numSearchThreads` 设为 2~4。
- **APK 体积**
  → `libkatago.so`（含引擎）约 30~60MB（arm64-v8a + x86_64 双 ABI）；
    模型权重 83MB 不进 git，但会进入 APK。可只保留 arm64-v8a 减小体积。
- **切换模型/重新编译引擎**
  → 修改 `cpp/katago_jni.cpp` 或 KataGo 源码后，`gradlew clean` 再 `assembleDebug`
    重新编译 `libkatago.so`。
