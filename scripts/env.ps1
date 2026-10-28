# 本机开发环境（PowerShell，只影响当前会话）
# 用法： . .\scripts\env.ps1
if ($env:JAVA_HOME_OVERRIDE) {
    $env:JAVA_HOME = $env:JAVA_HOME_OVERRIDE
} elseif (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    # 系统级 JAVA_HOME 已指向 Temurin 17；这里只兜底"继承了失效旧值"的会话
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

if (-not $env:MVN_HOME) {
    $env:MVN_HOME = "E:\02_software\05_programming\04_IntelliJ_IDEA\plugins\maven\lib\maven3"
}
function global:mvn { & "$env:MVN_HOME\bin\mvn.cmd" @args }

Write-Host "JAVA_HOME=$env:JAVA_HOME"
& java -version 2>&1 | Select-Object -First 1
Write-Host "用法： mvn -B test  /  mvn -B spring-boot:run"
