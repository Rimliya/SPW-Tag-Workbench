# 音乐标签工作台

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white) ![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.1-4285F4?logo=jetpackcompose&logoColor=white) ![JDK](https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white) ![Windows](https://img.shields.io/badge/Windows-10%20%7C%2011-0078D6?logo=windows11&logoColor=white) ![SPW](https://img.shields.io/badge/SPW-Plugin-E4574C) [![Steam 创意工坊](https://img.shields.io/badge/Steam%20创意工坊-订阅-1B2838?logo=steam&logoColor=white)](https://steamcommunity.com/sharedfiles/filedetails/?id=3825830018)

适用于 Salt Player for Windows（SPW）的音乐标签插件。可以批量匹配封面与逐字歌词、为歌词逐字打轴、查看歌曲频谱图，并在写入文件前集中预览和编辑标签信息。

> 非官方社区插件。在线数据来自相应音乐服务，接口可用性可能随服务调整而变化。

## Steam 创意工坊

<a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3825830018">
  <img src="https://img.shields.io/badge/Steam%20创意工坊-音乐标签工作台-1B2838?logo=steam&logoColor=white" alt="Steam 创意工坊：音乐标签工作台">
</a>

在 [Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3825830018) 订阅安装，后续更新随创意工坊自动下发；也可以从发布页下载 `.spmod` 手动导入。

## 主要功能

- **侧栏**：窗口右侧的悬浮按钮召唤侧栏，跟随当前播放显示封面与曲目信息，标题、艺术家、专辑、流派四项可直接修改。
- **工作台**：由侧栏展开的宽视图，包含工作台、歌词、封面、艺术家、频谱、打轴六个页面；左侧曲库栏可搜索，并可按专辑、艺术家、流派筛选和勾选曲目。
- **标签编辑**：标题、艺术家、专辑、专辑艺术家、流派、年份六项元数据以及歌词都可编辑。改动先存为草稿，点“保存”才写入文件，保存结束显示成功与失败数量。
- **封面与艺术家图**：左侧是预览，右侧列出各来源的候选图。勾选候选后点“写入草稿”才替换左侧预览，保存时才写入文件；也可从本地导入图片，或把当前图片导出为文件。
- **频谱**：解码音频后绘制左右声道频谱图，先出部分结果再出成品。
- **批量匹配**：“匹配选中”处理曲库中勾选的曲目，“匹配当前”只处理当前曲目，结果直接写入文件。
- **同名专辑批量写入**：把专辑、专辑艺术家、流派、年份一次写入同一张专辑的全部曲目，执行前有确认提示。

## 界面预览

<img width="980" alt="工作台主视图：曲库列表、主封面与歌词编辑" src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/spwplugin/tagworkbench/1.png">

<img width="980" alt="封面匹配：左侧预览与右侧候选封面" src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/spwplugin/tagworkbench/3.png">

<details>
<summary>更多界面截图（歌词匹配、歌曲频谱、艺术家图、逐字打轴）</summary>

<img width="980" alt="歌词匹配：候选列表与逐字歌词" src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/spwplugin/tagworkbench/2.png">

<img width="980" alt="歌曲频谱图：左右声道与频率刻度" src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/spwplugin/tagworkbench/4.png">

<img width="980" alt="艺术家图匹配：左侧预览与右侧候选" src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/spwplugin/tagworkbench/5.png">

<img width="980" alt="逐字打轴：波形条、逐行列表与字单元" src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/spwplugin/tagworkbench/6.png">

</details>

## 在线来源

| 来源 | 元数据 | 专辑封面 | 艺术家图 | 歌词 |
| --- | --- | --- | --- | --- |
| QQ 音乐 | ✓ | ✓ | ✓ | QRC 逐字 |
| 酷狗音乐 | ✓ | — | ✓ | KRC 逐字 |
| 网易云音乐 | ✓ | ✓ | ✓ | YRC 逐字 |
| 酷我音乐 | ✓ | ✓ | — | 逐行 |
| iTunes | ✓ | ✓ | — | — |
| LRCLIB | ✓ | — | — | 逐行 |

工具栏的“标签源”菜单可分别开关 iTunes、LRCLIB、网易云音乐、QQ 音乐、酷狗音乐五个来源。匹配时会综合比较标题与艺术家，不会直接采用搜索到的第一条结果；伴奏、纯音乐一类曲目不参与匹配。

## 歌词与打轴

可读取并转换 LRC、LRC（ESLyric）、SPL、YRC、KRC、QRC、SRT、ASS、TTML 与纯文本，其中 ESLyric、SPL、YRC、KRC、QRC、TTML 带逐字时间。译文与罗马音可单独开关显示，时间轴可整体平移，也可做简体与繁体互转。

打轴页提供波形条、逐行列表与字单元区，常用按键如下：

| 操作 | 按键 |
| --- | --- |
| 播放 / 暂停 | 空格 |
| 为当前行打起点并前移 | 回车 |
| 为当前字单元打时间，并跳到下一个未打轴单元 | Tab |
| 切换到上一行 / 下一行 | ↑ / ↓ |
| 清除当前行时间 | Delete / Backspace |

字单元时间缺失时可自动补全，行与字的时间可按 10 毫秒步进整体平移，播放速度可选 0.5、0.75、1.0、1.25、1.5 倍。

## 设置项

设置页（宿主模组管理）提供以下项目：

| 设置项 | 作用 |
| --- | --- |
| 打开音乐标签工作台 | 进入工作台主视图 |
| 批量匹配时的已有标签策略 | “全部重新匹配”用在线数据覆盖完整标签；“仅补缺失”整首跳过标签已完整的曲目。默认“仅补缺失” |
| 显示侧栏悬浮按钮 | 控制窗口右侧的召唤按钮 |
| 工作台主视图显示艺术家封面 | 关闭后主封面下方不再显示艺术家封面槽位 |
| 开源项目 | 打开项目仓库页面 |

## 已知限制

- 批量匹配直接修改文件且不进入预览，操作不可撤销，建议先用少量曲目验证。
- 封面与艺术家图候选只来自在线来源与本地导入的图片，不扫描同目录的旁挂图片。
- 酷我音乐不在标签源菜单中，无法单独关闭。
- 酷狗音乐的封面接口未声明为可用能力，其封面不参与候选。
- TTML 歌词仅支持读取，不支持写出。
- 侧栏与工作台的改动在点“保存”前不会写入文件。

## 使用提醒

- 在线来源可能因地区、网络或服务接口调整暂时不可用。
- 仅整理有权修改的本地音乐文件，并遵守当地法律及各服务条款。

## 安装

1. 取得插件包 `plugin-io.github.univers629.spwtagworkbench-<version>.spmod`。
2. 在 SPW 创意工坊的本地插件管理里导入并启用。
3. 在设置页授予 `class-transform`、`library-read`、`library-write` 三项权限。
4. 在设置页点击“打开音乐标签工作台”。

## 编译方法

要求 JDK 25（Gradle 按 toolchain 校验）。

```powershell
.\gradlew.bat plugin
```

产物为 `build/libs/plugin-io.github.univers629.spwtagworkbench-<version>.spmod`。

## 致谢

以下开源项目的作者与贡献者为本项目提供了参考：

- [LDDC](https://github.com/chenmozhijin/LDDC)（GPL-3.0-only）：酷狗歌词接口签名方案与 KRC 处理思路。
- [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)（Apache-2.0）：插件接口与开发示例。
- [Lyricify-Lyrics-Helper](https://github.com/WXRIW/Lyricify-Lyrics-Helper)（Apache-2.0）：TTML / QRC / YRC 格式规格参考。
- [Lyrico-Plugins](https://github.com/Replica0110/Lyrico-Plugins)：各音乐源端点与封面尺寸的对照参考。

详细声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

许可证参见 [LICENSE](LICENSE)（GNU General Public License v3.0 only）。
