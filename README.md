# M12P Watchface

面向华强北 M12P（368×448、Android 8.1、无 Wear OS/GMS）等小屏安卓手表的**血糖表盘桌面**。以 HOME 桌面身份运行，通过广播从 AndroidAPS 或欧态（OTTAI）app 实时获取血糖数据，直接显示在表盘上。

English users: see [License](#许可证license) — noncommercial use is free; commercial use requires the author's written permission (contact: 462531904@qq.com).

**详细使用说明（安装/数据源配置/常见问题）：[docs/USAGE.md](docs/USAGE.md)**

> **开发预览版**：项目仍在快速迭代，界面与交互可能随时变动；所有 Release 均标记为 Pre-release，暂不建议在生产环境长期依赖。

## 功能

- **表盘**：大号时间/日期、血糖值 + 趋势箭头 + 时效 + delta、IOB/COB、手表电量、数据源角标；血糖高于/低于阈值自动变红
- **双屏横向翻页**：表盘 ↔ 应用列表（九宫格），跟手拖动、松手吸附，替代传统抽屉
- **数据源**：
  - `AAPS`：手表版 AndroidAPS 启用 Tizen 同步插件后发出的状态广播
  - `欧态`：OTTAI app 的血糖广播（原始 mmol 值，支持偏移修正）
- **单位**：mmol/L 与 mg/dL 一键切换
- **背景图**：自定义图片，铺满裁剪 / 完整显示两种模式 + 四向位置微调，自动应用 EXIF 方向（竖拍照片不再错乱）
- **隐私**：血糖块旁眼睛图标一键隐藏/显示血糖
- **桌面**：底部快捷入口可配置（最多 4 个）、应用白名单、界面字号三档
- **长按应用**：固定到快捷入口 / 应用信息 / 卸载
- **血糖通知**：关 / 静默（不亮屏）/ 提醒三态；锁屏可见内容取决于系统通知设置
- **省电**：默认不持有屏幕常亮（跟随系统熄屏）；每分钟对齐整分一帧重绘 + 广播到达即时刷新，灭屏不重绘

## 安装

1. 从 [Releases](https://github.com/zhantaopin/m12p-watchface/releases) 下载最新 APK
2. `adb install -r watchface-x.x-debug.apk`，或将 APK 放入手表可访问的下载地址，手表浏览器下载安装
3. 打开「表盘」app → 预览页点「设为主屏幕（桌面模式）」，或按 Home 键在弹出的选择框中选择「表盘」并勾选始终

## 数据源配置

- **AAPS**：手表上的 AndroidAPS → 配置 → 同步类插件 → 启用 **Tizen** 插件
- **欧态**：装好 OTTAI app 即自动广播；如数值偏差，在设置页调整偏移量

## 构建

```bash
./gradlew :app:assembleDebug
```

纯框架 API 实现（无 AndroidX/GMS 依赖），minSdk 27 / targetSdk 27，适配任意分辨率（按画布宽缩放）。

## 许可证（License）

本项目采用 **[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0)**：

- 个人使用、学习、研究等**非商业用途**：可自由使用、修改、分发
- **商业用途需事先获得作者书面授权**（可付费）

联系作者：zhangtao <462531904@qq.com>

## 免责声明

本项目为社区第三方工具，与 AndroidAPS、OTTAI 官方无任何隶属关系。血糖数据仅供信息展示，不构成任何医疗建议；糖尿病管理方案请务必遵循医嘱。使用本项目产生的任何后果由使用者自行承担。
