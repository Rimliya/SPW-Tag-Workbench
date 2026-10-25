# native/ — libebur128（EBU R128 响度测量）

这个目录放的是**第三方 C 源码**，用于编译出 `ebur128.dll`，由插件通过 JNA 调用。

## 来源

| 项 | 值 |
| --- | --- |
| 上游项目 | [libebur128](https://github.com/jiixyj/libebur128) |
| 作者 | Jan Kokemüller（GitHub: `jiixyj`） |
| 许可证 | **MIT**（见同目录 `COPYING`） |
| 版本 | **1.2.6**（`ebur128.h` 里的 `EBUR128_VERSION_{MAJOR,MINOR,PATCH}`） |
| 拉取方式 | `git clone --depth 1 https://github.com/jiixyj/libebur128.git` |

## 文件说明

| 文件 | 来源 | 说明 |
| --- | --- | --- |
| `ebur128.c` | 上游原样 | 核心实现，1310 行 |
| `ebur128.h` | 上游原样 | 公开 API，23 个函数 |
| `queue/sys/queue.h` | 上游 `ebur128/queue/sys/queue.h` | **Windows 兼容头**：`ebur128.c` 会 `#include <sys/queue.h>`，Windows 没有这个 BSD 头，上游自带了一份 |
| `COPYING` | 上游原样 | MIT 许可全文 |

**未对上游代码做任何修改。**

## 编译

产物路径是固定的：`src/main/resources/native/win-x64/ebur128.dll`。
放在这个位置是为了让 Gradle 的 `processResources` 自动把它收进 `.spmod` 的 `classes/` 里
（`.spmod` 的 `lib/` 只接受 `.jar`，裸 DLL 会被打包插件过滤掉）。

```powershell
.\native\build-native.ps1
```

脚本做的事等价于：

```powershell
gcc -O2 -shared -msse2 -mfpmath=sse `
    -I native -I native/queue `
    -o src/main/resources/native/win-x64/ebur128.dll `
    native/ebur128.c
strip src/main/resources/native/win-x64/ebur128.dll
```

### 为什么带 `-msse2 -mfpmath=sse`

`ebur128.c` 依赖 **SSE2 的 FTZ（Flush-To-Zero）**：

```c
#define TURN_ON_FTZ  unsigned int mxcsr = _mm_getcsr(); \
                     _mm_setcsr(mxcsr | _MM_FLUSH_ZERO_ON);
```

IIR 滤波器在信号衰减时会产出 denormal（次正规数），x86 处理 denormal 会慢几十到上百倍。
上游靠写 MXCSR 寄存器开 FTZ 硬件消除这个惩罚；不开的话源码里有 `#warning` 提示。

## 预期产物

| 配置 | 大小 |
| --- | --- |
| `gcc -O2` + `strip` | **约 33.5 KB** |
| `gcc -Os` + `strip` | 约 26.5 KB |

## 导出符号

23 个 `ebur128_*` 函数。插件实际只用到 6 个：

```
ebur128_init              ebur128_add_frames_float
ebur128_loudness_global   ebur128_sample_peak
ebur128_true_peak         ebur128_destroy
```

## 许可

MIT。插件整体按 GPL-3.0-only 发布，MIT 与 GPL-3.0 兼容，因此可以随插件分发，
但**必须保留版权与许可声明** —— 本目录的 `COPYING` 即为该声明。
