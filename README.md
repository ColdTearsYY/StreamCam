# StreamCam

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-green.svg)](https://www.minecraft.net/)
[![Paper](https://img.shields.io/badge/Paper-26.2-blue.svg)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://www.oracle.com/java/)

一个受 LiveRecorder 启发的 Minecraft 服务器插件，专为直播、录制和服务器监控设计。实现了智能的自动化导播功能，可以自动在服务器玩家之间轮换观察视角。

> A Minecraft plugin inspired by LiveRecorder. Provides intelligent automatic camera rotation to observe all players on the server, perfect for streaming and recording.

## ✨ 核心特性

### 🎥 自动导播模式
- **智能轮播**: 自动在服务器玩家间循环切换视角
- **聚焦模式**: 优先观察活跃玩家，自动跳过挂机玩家
- **自定义间隔**: 可自由设定切换时间（秒）
- **无缝切换**: 使用观察者模式实现流畅的视角转换

### 🎮 灵活控制
- **手动切换**: 快速跳转到下一个目标玩家
- **实时标题提示**: 显示当前观察目标的名称
- **一键停止**: 随时退出导播模式

### 🛡️ 智能过滤
- **AFK 检测**: 自动识别并过滤挂机玩家
  - 轮播模式：30秒无活动视为挂机
  - 聚焦模式：15秒无活动视为挂机
- **黑名单管理**: 排除不想观察的玩家（如管理员、工作人员）
- **持久化存储**: 黑名单配置保存至 `config.yml`

### 🔧 技术优势
- **Folia 兼容**: 使用现代的 `ScheduledTask` API
- **线程安全**: 采用 `ConcurrentHashMap` 确保多线程安全
- **Paper 优化**: 利用 Paper API 的最新特性
- **高性能**: 异步任务处理，不影响服务器性能

## 📦 安装

### 要求
- Minecraft 服务器：26.2
- 服务端核心：Paper / Folia / Purpur 26.2
- Java 版本：25

### 步骤
1. 下载 `StreamCam-1.2.1-ourtravel.jar`
2. 将 JAR 文件放入服务器的 `plugins` 目录
3. 重启服务器使插件加载
4. 编辑 `plugins/StreamCam/config.yml` 进行配置（可选）

## 🎮 使用指南

### 指令列表

| 指令 | 别名 | 描述 | 用法 | 权限 |
|------|------|------|------|------|
| `/streamcycle` | `/scycle` | 开始自动轮播观察模式 | `/streamcycle [秒数]` | `streamcam.use` |
| `/streamfocus` | `/sfocus` | 开始聚焦模式（只观察活跃玩家） | `/streamfocus` | `streamcam.use` |
| `/streamnext` | `/snext` | 立即切换到下一个目标玩家 | `/streamnext` | `streamcam.use` |
| `/streambl` | `/sbl` | 管理导播黑名单 | `/streambl <add\|remove\|list> [玩家名]` | `streamcam.admin` |

### 使用示例

#### 基础轮播
```
/streamcycle 10
```
每 10 秒自动切换到下一个玩家（默认过滤 30 秒内无活动的玩家）

#### 聚焦活跃玩家
```
/streamfocus
```
智能寻找正在活动的玩家，自动跳过挂机者（15 秒检测阈值）

#### 手动控制
```
/streamnext
```
如果当前观察对象不够有趣，立即切换到下一个

#### 黑名单管理
```
/streambl add Steve          # 将 Steve 加入黑名单
/streambl remove Steve       # 将 Steve 移出黑名单
/streambl list               # 查看黑名单列表
```

### 权限节点

| 权限 | 默认 | 描述 |
|------|------|------|
| `streamcam.use` | OP | 允许使用轮播、聚焦和切换指令 |
| `streamcam.admin` | OP | 允许管理黑名单 |

## ⚙️ 配置文件

插件首次运行时会生成 `plugins/StreamCam/config.yml`：

```yaml
# 黑名单玩家 UUID 列表（自动管理，通常无需手动编辑）
blacklist-players:
  - "550e8400-e29b-41d4-a716-446655440000"
  - "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
```

> **提示**: 建议通过游戏内指令 `/streambl` 管理黑名单，而非直接编辑配置文件。

## 🔨 构建

### 从源码构建

```bash
# 克隆仓库
git clone https://github.com/ColdTearsYY/StreamCam.git
cd StreamCam

# 使用 Maven 构建
mvn clean package

# 生成的 JAR 文件位于 target/StreamCam-1.2.1-ourtravel.jar
```

### 开发环境
- IDE: IntelliJ IDEA / Eclipse
- 构建工具: Maven 3.6+
- JDK: 25

## 📝 工作原理

1. **活动追踪**: 插件监听玩家的移动、交互、聊天等事件，实时更新活跃状态
2. **智能筛选**: 根据最后活动时间和黑名单，动态生成可观察玩家列表
3. **视角切换**: 将指令执行者切换到观察者模式（`GameMode.SPECTATOR`）
4. **定时任务**: 使用 Paper 的 `ScheduledTask` API 定期执行切换逻辑
5. **标题通知**: 通过 Adventure API 显示美观的标题提示

## 🎯 使用场景

- 📺 **服务器直播**: 自动展示服务器内的精彩瞬间
- 🎬 **视频录制**: 无需手动操作，轻松录制多人视角
- 👀 **服务器监控**: 快速巡查玩家行为，维护服务器秩序
- 🏆 **赛事导播**: 在 PVP 竞赛中自动切换到战斗激烈的选手

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

如果你有新的功能建议或发现了 Bug，请：
1. Fork 本项目
2. 创建你的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交你的修改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启一个 Pull Request

## 📄 许可证

[![License: CC BY-NC-ND 4.0](https://img.shields.io/badge/License-CC%20BY--NC--ND%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-nd/4.0/)

本项目采用 **Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International License** (CC BY-NC-ND 4.0)

✅ **允许**：
- 下载和分享本作品（需注明原作者）
- 在私人 Minecraft 服务器上使用

❌ **禁止**：
- 修改、转换或基于本作品创作衍生作品
- 用于商业目的
- 发布修改版本或二次分发

📝 **署名要求**：使用时必须注明原作者 (ColdTearsYY) 并提供许可证链接

详细条款请参阅: [CC BY-NC-ND 4.0 完整文本](https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode)

## 🙏 致谢

- 灵感来源: [LiveRecorder](https://github.com/WeddedMercury15/LiveRecorder)
- 基于 [Paper API](https://papermc.io/)
- 使用 [Adventure API](https://docs.advntr.dev/) 提供现代化的文本组件

## 📮 联系方式

- 作者: ColdTearsYY
- 项目地址: [https://github.com/ColdTearsYY/StreamCam](https://github.com/ColdTearsYY/StreamCam)

---

⭐ 如果这个插件对你有帮助，请给个 Star 支持一下！
