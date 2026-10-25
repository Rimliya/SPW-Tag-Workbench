# 编译 libebur128 为 Windows x64 DLL。
#
# 产物固定输出到 src/main/resources/native/win-x64/ebur128.dll，
# 这样 Gradle 的 processResources 会自动把它收进 .spmod 的 classes/。
#
# 用法：
#   .\native\build-native.ps1              # -O2（默认）
#   .\native\build-native.ps1 -SizeOptimized   # -Os，体积更小

param(
    [switch]$SizeOptimized
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'src\main\resources\native\win-x64'
$out = Join-Path $outDir 'ebur128.dll'

# 找编译器：优先 MinGW 的 gcc，其次 LLVM 的 clang
$compiler = $null
foreach ($name in @('gcc', 'clang')) {
    $found = Get-Command $name -ErrorAction SilentlyContinue
    if ($found) { $compiler = $found.Source; break }
}
if (-not $compiler) {
    throw '找不到 C 编译器。请安装 MinGW-w64（gcc）或 LLVM（clang），并确保在 PATH 中。'
}

$strip = Get-Command 'strip' -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$optFlag = if ($SizeOptimized) { '-Os' } else { '-O2' }

$args = @(
    $optFlag,
    '-shared',
    '-msse2', '-mfpmath=sse', # libebur128 依赖 SSE2 的 FTZ 消除 denormal 惩罚
    '-I', (Join-Path $root 'native'),
    '-I', (Join-Path $root 'native\queue'),
    '-o', $out,
    (Join-Path $root 'native\ebur128.c')
)

Write-Host "编译器: $compiler"
Write-Host "选项  : $($args -join ' ')"
Write-Host ''

& $compiler @args
if ($LASTEXITCODE -ne 0) { throw "编译失败（exit $LASTEXITCODE）" }

if ($strip) {
    & $strip.Source $out
    if ($LASTEXITCODE -ne 0) { Write-Warning "strip 失败，保留未 strip 的产物" }
}

$size = [math]::Round((Get-Item $out).Length / 1KB, 1)
Write-Host ''
Write-Host "✅ 已生成: $out  ($size KB)"
