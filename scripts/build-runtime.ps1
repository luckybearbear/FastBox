<#
.SYNOPSIS
  FastBox 运行时构建脚本（Windows）
.DESCRIPTION
  生成打包所需的两个外部组件：
    1. runtime/jre17/       — jlink 精简 JRE（40-60MB），替代完整 JDK
    2. python-runtime/.venv/ — portable Python（venv --copies），跨机可执行

  用法（在 FastBox 根目录）：
    pwsh scripts/build-runtime.ps1           # 完整：jlink + portable venv
    pwsh scripts/build-runtime.ps1 -JreOnly  # 仅生成 jlink JRE
    pwsh scripts/build-runtime.ps1 -PyOnly   # 仅重建便携 venv

  前置：
    - JDK 17+ 已安装且 jlink.exe 在 PATH
    - python 3.11+ 在 PATH（推荐 3.13，与开发环境一致）

  完成后 electron-builder 即可把 runtime/jre17 和 python-runtime/.venv
  打包进 extraResources。
#>

[CmdletBinding()]
param(
    [switch]$JreOnly,
    [switch]$PyOnly,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$RuntimeDir  = Join-Path $ProjectRoot "runtime"
$PythonDir   = Join-Path $ProjectRoot "python-runtime"

# Java 网关编译，确保 fastbox-gateway.jar 存在
function Build-GatewayJar {
    Write-Host "==> mvn package (fastbox-gateway.jar)" -ForegroundColor Cyan
    Push-Location (Join-Path $ProjectRoot "java-gateway")
    try { mvn -q clean package -DskipTests }
    finally { Pop-Location }
}

# 1) jlink 精简 JRE：仅含 Java 网关 + ClassLoader 反射所需的模块
function Build-JlinkJre {
    param([string]$JdkHome)
    if (-not $JdkHome) { throw "未指定 JDK 路径（通过 -JavaHome 或设置 JAVA_HOME 环境变量）" }
    $jlink = Join-Path $JdkHome "bin\jlink.exe"
    if (-not (Test-Path $jlink)) { throw "找不到 jlink.exe：$jlink" }

    $jdeps = Join-Path $JdkHome "bin\jdeps.exe"
    $gatewayJar = Join-Path $ProjectRoot "java-gateway\target\fastbox-gateway.jar"
    if (-not (Test-Path $gatewayJar)) { Build-GatewayJar }

    Write-Host "==> jdeps 分析依赖模块" -ForegroundColor Cyan
    # 输出到 stderr，捕获时排除 verbose
    $modules = & $jdeps --module-path "$JdkHome\jmods" --list-deps $gatewayJar 2>$null |
        Where-Object { $_ -match '^[a-z]+(\.[a-z]+)+$' } |
        Sort-Object -Unique
    # 兜底模块清单（即使 jdeps 报错也能用）
    $fallback = @("java.base","java.sql","java.logging","java.naming","java.management","jdk.unsupported","jdk.crypto.ec")
    $modList = ($modules + $fallback) | Sort-Object -Unique
    Write-Host "    模块清单：$($modList -join ',')"

    $out = Join-Path $RuntimeDir "jre17"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    Write-Host "==> jlink 生成精简 JRE → $out" -ForegroundColor Cyan
    & $jlink @(
        "--module-path", "$JdkHome\jmods",
        "--add-modules", ($modList -join ','),
        "--strip-debug",
        "--compress", "2",
        "--no-header-files",
        "--no-man-pages",
        "--output", $out
    ) | Write-Host

    if (-not (Test-Path (Join-Path $out "bin\java.exe"))) {
        throw "jlink 失败，未生成 java.exe"
    }
    $size = (Get-ChildItem $out -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host "    精简 JRE 大小：$([math]::Round($size,1)) MB" -ForegroundColor Green
}

# 2) 便携 Python：用 --copies 重建 venv，拷贝解释器而非符号链接，跨机可用
function Build-PortablePython {
    $python = (Get-Command python -ErrorAction Stop).Source
    Write-Host "==> portable venv 重建（python=$python）" -ForegroundColor Cyan

    $venv = Join-Path $PythonDir ".venv"
    if (Test-Path $venv) { Remove-Item -Recurse -Force $venv }

    Push-Location $PythonDir
    try {
        & $python -m venv --copies .venv
        if ($LASTEXITCODE -ne 0) { throw "venv 创建失败" }
        Write-Host "==> pip install -r requirements.txt" -ForegroundColor Cyan
        & .\.venv\Scripts\python.exe -m pip install --upgrade pip | Out-Null
        & .\.venv\Scripts\python.exe -m pip install -r requirements.txt
        if ($LASTEXITCODE -ne 0) { throw "依赖安装失败" }
    }
    finally { Pop-Location }

    # 校验跨机可移植性：pyvenv.cfg 不应含本机绝对路径到 venv 内部（home / executable）
    $pyvenvCfg = Join-Path $venv "pyvenv.cfg"
    $cfg = Get-Content $pyvenvCfg -Raw
    if ($cfg -match 'home\s*=\s*[A-Z]:\\') {
        Write-Warning "pyvenv.cfg 含本机绝对路径 home，跨机分发时 Python 可能无法启动"
        Write-Warning "考虑用 embeddable Python (https://www.python.org/downloads/windows/) 替换 .venv"
    }
    $size = (Get-ChildItem $venv -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host "    portable venv 大小：$([math]::Round($size,1)) MB" -ForegroundColor Green
}

Write-Host "===== FastBox 运行时构建 =====" -ForegroundColor Cyan
Write-Host "ProjectRoot = $ProjectRoot"

if (-not $PyOnly) {
    if (-not $JavaHome) { $JavaHome = (Get-Command java -ErrorAction SilentlyContinue).Source }
    if ($JavaHome -and $JavaHome -like "*\java.exe") { $JavaHome = Split-Path $JavaHome -Parent }
    Build-JlinkJre -JdkHome $JavaHome
}

if (-not $JreOnly) {
    Build-PortablePython
}

Write-Host "`n===== 完成 =====" -ForegroundColor Green
Write-Host "下一步：在 FastBox\electron 目录下执行"
Write-Host "  npm install"
Write-Host "  npm run dist          # 生成 Windows 安装包"