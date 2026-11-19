# LUMEN HUD · 启明全息终端仪表

一款科幻风格 Android 全息终端仪表盘：Kotlin + Jetpack Compose **单文件实现**（约 1000 行），
是学习 Compose 自绘动画、传感器融合、TTS 与自适应图标的一站式示例。

## 功能

- **打字机启动序列** → CORE 仪表盘：电量 / 内存 / 存储 / CPU 折线图 / 反应环（点击校准）
- **罗盘雷达**：磁力计 + 加速度计 `getRotationMatrix` 融合方位角，旋转罗盘环
- **终端命令行**：BasicTextField + 快捷命令芯片（HELP / STATUS / SCAN / JOKE / RED），
  本地 `handleCommand` 解析，`theme red` 一键换肤
- **TTS 语音问候** · 三主题（青 / 血红 / 琥珀）持久化 · 24 颗漂移粒子背景
- **自适应矢量图标**（深底青弧环 + 白核）

## 构建

要求：Gradle 8.9+ · JDK 17 · compileSdk 35 · minSdk 26

```bash
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 单文件哲学

全部逻辑在 `app/src/main/java/com/lumen/hud/MainActivity.kt`。
这不是偷懒，而是刻意为之：把传感器、Canvas 自绘、动画、状态管理压缩进一个文件，
方便从头读到尾理解整个 Compose 应用的骨架。

## License

MIT — 见 [LICENSE](LICENSE)
