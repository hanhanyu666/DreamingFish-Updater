# 官网同步补丁（本地，未部署）

源仓库：[DreamingFish-Updater-Website](https://github.com/DreamingFish-Studio/DreamingFish-Updater-Website)，固定基线 `69d3822aa166390139c6d0433ccbb2708a9d55f4`。源文件通过只读接口取得；本轮没有推送、提交、Pages 部署或修改线上页面。

[2026-10-18.patch](2026-10-18.patch) 更新 admin、create-project、maintenance、quickstart、faq、changelog 六页，涵盖迁移 / 便携路径、导入重试与跳过、恢复与配额、备份边界、完整校验 / 游戏锁、版本关系和未发布状态。历史版本记录保留。网站没有独立构建工具链；补丁不改 CSS / JS / 媒体资源。

在该仓库的固定提交上先检查，再应用：

```powershell
git rev-parse HEAD
git apply --check <本文件夹绝对路径>\2026-10-18.patch
git apply <本文件夹绝对路径>\2026-10-18.patch
```

若官网已前进，先比较差异并解决上下文冲突。发布前应重新确认下载文件名、正式版本号和修复实际进入的 Release；页面显式标为本地修复候选，不能暗示现有下载已修复。补丁应用与 HTML 检查证据记录在本地修复结果中。
