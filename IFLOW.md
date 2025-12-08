# Virtual Hosts 项目上下文

## 项目概述

Virtual Hosts 是一个 Android 应用，允许开发者在无 root 权限的 Android 设备上自定义 hosts 文件。该应用通过 VPN 模式实现 DNS 解析控制，支持通配符 DNS 记录。

### 主要特性
- 无需 root 权限即可修改 hosts 文件
- 支持通配符 DNS 记录（例如：`.a.com` 可以匹配 `a.com`、`m.a.com`、`w.m.a.com` 等）
- 通过 VPN 服务实现网络流量拦截和 DNS 解析
- 支持开机自启动
- 提供快速启动小部件和磁贴服务

### 技术栈
- **平台**: Android (API 19-33)
- **语言**: Java
- **构建工具**: Gradle 7.4.2
- **主要依赖**:
  - Android Support Library v7
  - Firebase Analytics
  - Floating Action Button 库
  - Switch Button 库
  - Google Play Billing
  - 自定义 DNS Java 库 (dnsjava)

## 项目结构

```
Virtual-Hosts/
├── app/                          # 主应用模块
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   ├── com/github/xfalcon/vhosts/  # 主要应用代码
│   │   │   │   │   ├── VhostsActivity.java      # 主界面活动
│   │   │   │   │   ├── SettingsActivity.java   # 设置界面
│   │   │   │   │   ├── vservice/               # VPN 服务相关
│   │   │   │   │   │   └── VhostsService.java   # 核心 VPN 服务
│   │   │   │   │   └── util/                   # 工具类
│   │   │   │   └── org/xbill/DNS/              # DNS 处理库
│   │   │   ├── res/                            # 资源文件
│   │   │   └── AndroidManifest.xml             # 应用清单
│   │   ├── test/                               # 单元测试
│   │   └── androidTest/                        # 集成测试
│   └── build.gradle                            # 应用级构建配置
├── gradle/                                      # Gradle 包装器
├── build.gradle                                # 项目级构建配置
├── settings.gradle                              # 项目设置
└── hosts_to_wildcard.py                        # 通配符转换工具
```

## 构建和运行

### 环境要求
- Android Studio
- JDK 8 或更高版本
- Android SDK (API 19-33)
- Gradle 7.x

### 构建命令

```bash
# 清理项目
./gradlew clean

# 构建调试版本
./gradlew assembleDebug

# 构建发布版本
./gradlew assembleRelease

# 运行测试
./gradlew test

# 安装到设备
./gradlew installDebug
```

### 构建变体
项目支持两种构建变体：
- **googleplay**: 用于 Google Play 商店，启用 Google Play 服务
- **github**: 用于 GitHub 发布，不包含 Google Play 服务

### 构建类型
- **debug**: 调试版本，包含调试信息
- **release**: 发布版本，启用代码混淆和资源压缩

## 开发约定

### 代码风格
- 遵循 Java 标准命名约定
- 使用 Android Support Library 而非 AndroidX (项目已设置 `android.useAndroidX=true` 但代码仍在使用 Support Library)
- 使用 LogUtils 进行日志记录

### 权限管理
应用需要以下关键权限：
- `BIND_VPN_SERVICE`: 创建 VPN 服务
- `INTERNET`: 网络访问
- `ACCESS_NETWORK_STATE`: 网络状态访问
- `RECEIVE_BOOT_COMPLETED`: 开机自启动
- `FOREGROUND_SERVICE`: 前台服务

### 核心组件
- **VhostsActivity**: 主界面，控制 VPN 服务的启动和停止
- **VhostsService**: 核心 VPN 服务，处理网络流量和 DNS 解析
- **BootReceiver**: 开机启动接收器
- **NetworkReceiver**: 网络状态变化接收器
- **QuickStartWidget**: 快速启动小部件
- **QuickStartTileService**: 快速启动磁贴服务 (Android 7.0+)

### DNS 处理
项目使用自定义的 DNS Java 库 (dnsjava) 处理 DNS 解析，支持通配符记录匹配。

## 测试

### 单元测试
```bash
./gradlew test
```

### 集成测试
```bash
./gradlew connectedAndroidTest
```

### 压力测试
项目包含压力测试类 `PressureTest.java`，用于测试 VPN 服务在高负载下的表现。

## 发布

### Google Play 发布
1. 使用 `googleplay` 构建变体
2. 签名 APK
3. 上传到 Google Play Console

### GitHub 发布
1. 使用 `github` 构建变体
2. 签名 APK
3. 创建 GitHub Release 并上传 APK

## 许可证

项目采用 GNU General Public License v3.0 许可证。

## 依赖项

主要第三方依赖：
- LocalVPN (APL 2.0)
- dnsjava (BSD)
- Firebase Analytics
- Google Play Billing

## 注意事项

- 应用需要用户授权 VPN 权限才能正常工作
- 通配符 DNS 记录功能是项目的核心特性
- 应用会在通知栏显示持续的 VPN 服务通知
- 项目包含 Firebase Analytics 用于收集使用数据