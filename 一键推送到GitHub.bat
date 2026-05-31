@echo off
chcp 65001 >nul
echo ============================================================
echo   PowerDrainTest 一键推送到 GitHub（之后云端自动生成 APK）
echo ============================================================
echo.
echo 操作前请先在 GitHub 网页创建一个【空仓库】（不要勾选任何初始化文件）：
echo   https://github.com/new
echo.
echo 创建后复制仓库地址，例如：
echo   https://github.com/你的用户名/PowerDrainTest.git
echo.
set /p REPO=请粘贴你的仓库地址后回车：

if "%REPO%"=="" (
  echo 未输入地址，已取消。
  pause
  exit /b 1
)

cd /d "%~dp0"
git remote remove origin >nul 2>&1
git remote add origin %REPO%
git branch -M main
echo.
echo 正在推送...（首次会弹出浏览器让你登录 GitHub 授权）
git push -u origin main

echo.
if %ERRORLEVEL%==0 (
  echo ============================================================
  echo   推送成功！
  echo   现在打开仓库页面的 Actions 标签，等 Build APK 变绿，
  echo   约 3-5 分钟后在该次运行底部 Artifacts 下载 APK。
  echo ============================================================
) else (
  echo 推送失败，请检查仓库地址是否正确、是否已登录 GitHub。
)
echo.
pause
