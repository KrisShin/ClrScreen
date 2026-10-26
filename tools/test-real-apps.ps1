# 真实 App 开屏广告测试（v4）
#
# 相比前几版的关键修正：
#  1. 隐私协议已由用户手动点掉，不再重复处理
#  2. 用 ClrScreen 的诊断日志判断"服务是否扫描了该 App"，区分 NO_AD 与 NO_EVENT
#  3. 每轮冷启动后等待 12s，覆盖慢启动
#  4. 单独记录"最高分节点"，失败时可直接看到服务看到了什么
param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [int]$Rounds = 3,
    [string]$OutFile = "tools/real-app-results.csv"
)

$ErrorActionPreference = "Continue"

$apps = [ordered]@{
    "com.qiyi.video"              = @("爱奇艺",        "com.qiyi.video/.WelcomeActivity")
    "com.wuba"                    = @("58同城",        "com.wuba/.home.activity.HomeActivity")
    "com.baidu.appsearch"         = @("百度手机助手",   "com.baidu.appsearch/.SplashActivity")
    "com.shoujiduoduo.wallpaper"  = @("壁纸多多",      "com.shoujiduoduo.wallpaper/.ui.SplashActivity")
    "com.ss.android.ugc.live"     = @("抖音火山版",    "com.ss.android.ugc.live/com.ss.android.ugc.aweme.splash.SplashActivity")
    "com.duoduo.child.story"      = @("儿歌多多",      "com.duoduo.child.story/.ui.activity.SplashActivity")
    "com.ss.android.article.lite" = @("今日头条极速版", "com.ss.android.article.lite/.activity.SplashActivity")
    "com.tencent.weishi"          = @("腾讯微视",      "com.tencent.weishi/com.tencent.oscar.module.splash.SplashActivity")
    "ctrip.android.view"          = @("携程旅行",      "ctrip.android.view/ctrip.business.splash.CtripSplashActivity")
    "com.snda.wifilocating"       = @("WiFi万能钥匙",   "com.snda.wifilocating/com.wifitutu.ui.launcher.LauncherActivity")
}

$results = @()

foreach ($pkg in $apps.Keys) {
    $name = $apps[$pkg][0]
    $act = $apps[$pkg][1]
    Write-Host "`n===== $name =====" -ForegroundColor Cyan

    for ($i = 1; $i -le $Rounds; $i++) {
        & $Adb logcat -c 2>&1 | Out-Null
        & $Adb shell am force-stop $pkg 2>&1 | Out-Null
        Start-Sleep -Milliseconds 800
        & $Adb shell am start -n $act 2>&1 | Out-Null
        Start-Sleep -Seconds 12

        $log = (& $Adb logcat -d -s ClrScreen:D 2>&1) -join "`n"

        $clicked = [regex]::Match($log, "已点击 pkg=$([regex]::Escape($pkg)) score=(\d+)")
        $missed = [regex]::Match($log, "未命中 pkg=$([regex]::Escape($pkg)) 最高分节点: (.+)")
        $window = $log -match "窗口切换 pkg=$([regex]::Escape($pkg))"

        $result = if ($clicked.Success) { "AD_CLICKED" }
                  elseif ($missed.Success -or $window) { "NO_AD" }
                  else { "NO_EVENT" }

        $top = if ($missed.Success) { $missed.Groups[1].Value } else { "" }

        $results += [pscustomobject]@{
            App     = $name
            Package = $pkg
            Round   = $i
            Result  = $result
            Score   = if ($clicked.Success) { $clicked.Groups[1].Value } else { "" }
            TopNode = $top
        }

        $color = switch ($result) {
            "AD_CLICKED" { "Green" }
            "NO_AD"      { "DarkGray" }
            default      { "Red" }
        }
        $detail = if ($clicked.Success) { "score=$($clicked.Groups[1].Value)" }
                  elseif ($top) { "最高分: $top" }
                  else { "" }
        Write-Host ("  轮次{0}: {1,-11} {2}" -f $i, $result, $detail) -ForegroundColor $color

        Start-Sleep -Milliseconds 500
    }
    & $Adb shell am force-stop $pkg 2>&1 | Out-Null
}

Write-Host "`n`n========== 汇总 ==========" -ForegroundColor Cyan
$results | Group-Object App | ForEach-Object {
    $c = ($_.Group | Where-Object { $_.Result -eq "AD_CLICKED" }).Count
    $na = ($_.Group | Where-Object { $_.Result -eq "NO_AD" }).Count
    $ne = ($_.Group | Where-Object { $_.Result -eq "NO_EVENT" }).Count
    $mark = if ($c -gt 0) { "OK " } elseif ($ne -eq $_.Count) { "?? " } else { "-- " }
    Write-Host ("{0}{1,-14} 跳过{2}  无广告{3}  无事件{4}" -f $mark, $_.Name, $c, $na, $ne)
}
$results | Export-Csv -NoTypeInformation -Encoding UTF8 $OutFile
Write-Host "`n已保存: $OutFile"
