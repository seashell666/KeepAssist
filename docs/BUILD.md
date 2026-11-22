# 构建与发布

## 仓库里为什么有 `.b64` 文件

本仓库通过 GitHub 的内容接口提交，该接口只能承载**文本**。所以图标 PNG、
APK、Magisk zip 这些二进制文件以 base64 文本（`.b64`）入库，由
[`.github/workflows/release.yml`](../.github/workflows/release.yml) 在 CI 里解码后
作为 release 附件发布。

本地还原所有二进制：

```sh
sh tools/restore-assets.sh
```

会生成：

- `res/mipmap-*/ic_launcher_ka.png`（五档图标）
- `release-assets/KeepAssist-v2.2.apk`
- `release-assets/KeepAssist-Magisk-v2.2.zip`

## 工具链

`build.sh` 不依赖 Gradle，直接用 Android build-tools 手工编译。需要自备以下目录
（不随仓库分发），通过 `TOOLCHAIN_ROOT` 指向其根目录，默认是 `./toolchain`：

```
toolchain/
├── android-14/            # build-tools 34：aapt2, lib/d8.jar, lib/apksigner.jar, zipalign
├── android-13/            # platform 33：android.jar
├── jdk-17.0.21+9/         # JDK 17
└── api-82.jar             # Xposed API 82
```

Windows（Git Bash / MSYS）下脚本自动给可执行文件加 `.exe`、classpath 用 `;`；
Linux / macOS 下不加后缀、用 `:`。

## 构建

```sh
sh tools/restore-assets.sh        # 首次需要，还原图标
TOOLCHAIN_ROOT=/path/to/toolchain ./build.sh
```

产物在 `out/KeepAssist.apk`。脚本会自建一个 `ka.jks` 调试签名（口令 `keepassist`），
仅用于本地测试；发布产物请用正式签名。

## 打包 Magisk 模块

`module/` 是 Magisk 模块的文本部分：

- `module.prop`、`customize.sh`、`service.sh`
- 还需要一个 `KeepAssist.apk`（即上面 `out/KeepAssist.apk` 复制过去）

把它们与 `META-INF/` 刷机脚本一起按 Magisk 模块格式打成 zip，即为发布包。
`customize.sh` 会自动安装 APK 并给 Keep 配置 root 权限。

## 发布流程

1. 更新 `CHANGELOG.md`、`RELEASE_NOTES.md`，必要时更新 `docs/README.md`
2. 更新 `module/module.prop` 与 `AndroidManifest.xml` 的版本号
3. 把新的 APK / zip 编码为 `release-assets/*.b64`，同步更新 `release.yml` 里的 SHA-256
4. 提交并推送到 `main`

`.github/workflows/release.yml` 会自动：

- 解码 `release-assets/*.b64`
- 用 SHA-256 校验产物完整性（防传输/编码损坏）
- 创建 `v2.2` tag 与 release，正文取 `RELEASE_NOTES.md`，附上两个安装包

工作流是幂等的：release 已存在时只更新说明与附件，不会重复建 tag。
也可以在 Actions 页手动 `Run workflow` 重跑。

## 版本对应关系

| versionName | versionCode | 章节 |
|---|---|---|
| 2.2 | 13 | 当前版本 |
| 1.8 | 9 | 起有明确的 versionCode 记录 |

详见 [CHANGELOG.md](../CHANGELOG.md)。
