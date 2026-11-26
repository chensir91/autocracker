# 明日方舟连点器

一个基于 Android 无障碍服务的自动连点器应用，专为游戏（如明日方舟）设计，用于辅助完成重复性点击操作。

> [!IMPORTANT]
> **v3.27 起更换了签名密钥**
>
> 旧版签名密钥曾意外提交到本仓库，存在被冒用的风险，因此 v3.27 起改用全新的签名证书。
> 由于 Android 不允许签名不同的 APK 覆盖安装，**升级到 v3.27 需要先卸载旧版本再安装**（卸载会清空已保存的连点方案，请提前记下或重新录制）。


## 功能特性

- **单点连击模式**：在一个固定位置反复点击
- **多点循环模式**：按顺序依次点击多个预设位置，循环执行
- **点击间隔随机化**：在设定范围内随机间隔（默认0.1s~0.2s），模拟人类操作节奏
- **录制功能**：通过悬浮窗一键录制点击位置
- **方案管理**：支持保存/加载多个录制方案，方便不同场景切换
- **小巧悬浮窗**：可拖拽移动，不遮挡操作区域

## 技术实现

- **无障碍服务 (Accessibility Service)**：使用 `dispatchGesture` 执行模拟点击
- **悬浮窗 (SYSTEM_ALERT_WINDOW)**：全局显示控制面板
- **最低 SDK**：Android 7.0 (API 24)
- **开发语言**：Kotlin

## 权限说明

本应用需要以下权限：

### 1. 无障碍服务权限
无障碍服务是执行自动点击的核心权限，但此权限需要用户**手动开启**，无法通过代码自动申请。

**开启方法**：
1. 安装应用后，打开应用
2. 根据提示点击"去开启"按钮，或手动进入 **设置 → 无障碍 → 明日方舟连点器 → 开启**
3. 在弹出的确认对话框中点击"确定"

### 2. 悬浮窗权限
用于在屏幕上显示悬浮控制面板。

**开启方法**：
1. 进入 **设置 → 应用 → 明日方舟连点器 → 悬浮窗**
2. 选择"允许"

或在应用内根据提示操作。

### 3. 通知权限（Android 13+）
用于保持后台服务运行。

## 使用方法

### 录制点位
1. 点击悬浮窗上的**录制按钮**（红色编辑图标）
2. 屏幕会出现录制覆盖层
3. 点击游戏界面上需要自动点击的位置
4. 再次点击悬浮窗上的录制按钮结束录制

### 开始连点
1. 点击悬浮窗上的**播放按钮**开始执行
2. 连点器将按录制顺序循环点击各点位
3. 点击**暂停按钮**可暂停，再次点击继续
4. 点击**停止按钮**完全停止

### 保存/加载方案
- **保存**：点击主界面的"保存方案"按钮，输入名称保存当前点位
- **加载**：点击主界面的"加载方案"按钮，选择已有方案

### 设置
- **点击间隔**：调整随机间隔范围（默认0.1s~0.2s）
- **循环次数**：可选择无限循环或指定次数

## 编译运行

### 环境要求
- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 34

### 编译步骤

1. **克隆/下载项目**

2. **用 Android Studio 打开项目**
   ```
   File → Open → 选择项目根目录
   ```

3. **同步 Gradle**
   Android Studio 会自动提示同步，点击 "Sync Now"

4. **编译 debug APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```
   或使用终端：
   ```bash
   cd 项目根目录
   ./gradlew assembleDebug
   ```

5. **安装到设备**
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### 签名配置（本地构建 release）

仓库里**不再保存任何签名文件**。要在本地打 release 包，请在项目根目录新建 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=/绝对路径/release.p12
storePassword=你的密码
keyAlias=release
keyPassword=你的密码
```

没有这个文件时，`assembleDebug` 仍然可用（自动使用 debug 签名），`assembleRelease` 会产出未签名 APK。

### 发版流程

正式版本由 GitHub Actions 构建并发布，本地不需要打包：

```bash
git tag v3.27
git push origin v3.27
```

CI 会自动：
1. 从 tag 解析版本号（`v3.27` → `versionName=3.27`、`versionCode=32700`）
2. 用仓库 Secret `SIGNING_CONFIG` 中的密钥签名
3. 构建 APK 并创建 GitHub Release，自动附带 APK 和变更说明

日常提交到 `main` 只会触发 debug 构建冒烟检查，不会发版。

## 项目结构

```
明日方舟连点器/
├── app/
│   ├── src/main/
│   │   ├── java/com/autoclicker/arknights/
│   │   │   ├── data/           # 数据模型和存储
│   │   │   │   ├── Models.kt   # 数据类定义
│   │   │   │   └── SettingsManager.kt  # 设置管理
│   │   │   ├── service/        # 服务层
│   │   │   │   ├── AutoClickAccessibilityService.kt  # 无障碍服务
│   │   │   │   └── FloatingWindowService.kt  # 悬浮窗服务
│   │   │   ├── ui/             # 界面层
│   │   │   │   ├── MainActivity.kt      # 主界面
│   │   │   │   ├── SettingsActivity.kt  # 设置页面
│   │   │   │   └── RecordingOverlayView.kt  # 录制覆盖层
│   │   │   └── util/            # 工具类
│   │   │       ├── ClickUtils.kt         # 点击工具
│   │   │       └── PermissionUtils.kt    # 权限工具
│   │   ├── res/
│   │   │   ├── layout/          # 布局文件
│   │   │   ├── values/          # 资源文件
│   │   │   └── xml/             # 无障碍服务配置
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

## 注意事项

1. **无障碍服务必须开启**：这是应用正常工作的前提，未开启时应用会显示提示
2. **部分设备兼容性**：不同厂商的 Android 系统可能对权限管理有额外限制，如遇到问题请尝试重启应用或设备
3. **游戏更新**：部分游戏更新后可能需要重新录制点位
4. **电池优化**：建议将应用加入电池白名单，避免后台被系统强制关闭
5. **安全风险提示**：本应用仅用于辅助操作，请勿用于作弊或违反游戏用户协议的行为

## 免责声明

本应用仅供学习交流使用，使用本应用产生的任何后果由用户自行承担。请遵守相关应用的使用协议。

## License

MIT License（见 [LICENSE](LICENSE)）
