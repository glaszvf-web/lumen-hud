# 上传指引（给执行上传的 AI）

## 1. 仓库信息（建议值）

| 项 | 值 |
|---|---|
| 仓库名 | `lumen-hud` |
| 描述（英文） | Sci-fi holographic HUD dashboard for Android — single-file Kotlin + Jetpack Compose (compass radar, TTS, terminal console, particle layer). ~1000 lines. |
| 描述（中文） | 科幻风安卓全息终端仪表：Kotlin+Compose 单文件实现，罗盘雷达/TTS/终端命令行/粒子背景，约 1000 行。 |
| 可见性 | public |
| 标签 | kotlin, android, jetpack-compose, hud, compose, canvas, sensors |
| 默认分支 | main |

## 2. 脱敏复核（打包方已完成，上传前复核）

- [x] 包名中性化：`com.lumen.hud`
- [x] 终端彩蛋字符串已中性化（OPERATOR / LUMEN-01 / STANDBY）
- [x] TTS 问候语已去个人称谓
- [x] 全库身份字符串 grep 应为 0 命中 应为 0 命中
- [x] 无 APK / keystore / local.properties 入包

## 3. 上传步骤

```bash
cd <本目录>
git init -b main
git add -A
git commit -m "LUMEN HUD v3.0 — single-file Compose holographic dashboard"
gh repo create lumen-hud --public --source=. --push \
  --description "Single-file Compose holographic HUD for Android"
```

无 gh 时用方式 B（先在平台建空仓）：
```bash
git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/lumen-hud.git
git push -u origin main
```

## 4. 上传后动作

- Releases → tag `v3.0`：可附 `gradle assembleDebug` 产出的 APK
- 仓库描述与标签按上表填写

## 5. 禁止事项

- 禁止提交任何 APK 到主分支（放 Releases）
- 禁止恢复任何个人称谓字符串
