# 第三方软件与许可证声明

本项目的整体源代码依据 **GNU General Public License v3.0 only（GPL-3.0-only）** 发布，
完整许可文本见仓库根目录的 [LICENSE](LICENSE)。

本项目的部分实现派生自以下开源项目，相关权利归原作者所有。

## 派生来源

| 项目 | 许可证 | 作者 | 用于本项目的部分 |
| --- | --- | --- | --- |
| [LDDC](https://github.com/chenmozhijin/LDDC) | **GPL-3.0-only** | **沉默の金（chenmozhijin）** | 酷狗歌词接口的签名方案（`signature = md5(SALT + 排序参数串 + SALT)`）与 KRC 歌词处理思路 |

### 具体派生位置

| 本文件 | 派生内容 |
| --- | --- |
| `src/main/kotlin/io/github/univers629/spwtagworkbench/backend/source/KugouSource.kt` | `SALT` 常量、`signedUrl()` 签名构造、`md5()` 摘要 |

该文件头部保留了原始作者的 SPDX 版权行：

```
SPDX-FileCopyrightText: 2024-2025 沉默の金 (LDDC KuGou signature scheme)
SPDX-License-Identifier: GPL-3.0-only
```

### 派生链说明

```
LDDC（Python，chenmozhijin，GPL-3.0-only）
    ↓ 签名方案移植为 Kotlin
本项目（GPL-3.0-only）
```

本项目同时声明并遵守 GPL-3.0-only，因此与上游许可证兼容。

## 参考但不构成派生的项目

以下项目仅用于理解接口行为、格式规格与设计取舍，**未复制其代码**：

| 项目 | 许可证 | 用途 |
| --- | --- | --- |
| [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) | Apache-2.0 | 插件接口定义与开发示例 |
| [Salt Player for Windows](https://github.com/Moriafly/SaltPlayerSource) | — | 宿主；接口行为与材质机制的观察对象 |
| [Lyrico-Plugins](https://github.com/Replica0110/Lyrico-Plugins) | 仓库根目录未提供许可证 | 各音乐源端点与封面尺寸的对照参考；**不据此移植代码** |
| [Lyricify-Lyrics-Helper](https://github.com/WXRIW/Lyricify-Lyrics-Helper) | Apache-2.0 | TTML / QRC / YRC 歌词格式的规格参考 |

## 宿主依赖

插件运行时依赖由 Salt Player for Windows 宿主提供，均以 `compileOnly` 参与编译，
**不随本插件分发**：

| 依赖 | 提供方 |
| --- | --- |
| `spw-workshop-api` | 宿主 |
| Compose Multiplatform（ui / foundation / animation） | 宿主 |
| kotlinx-coroutines-core | 宿主 |
| skiko-awt | 宿主 |
| JNA | 宿主 |
| jaudiotagger | 宿主（LGPL-2.1-or-later） |

> jaudiotagger 采用 GNU Lesser General Public License 2.1 或更高版本，与 GPL-3.0 兼容。本插件在编译期引用它，运行期由宿主提供，不随插件分发。
