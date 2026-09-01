# Repository Guidelines

## 项目结构与模块组织

这是一个使用 Kotlin 和 Jetpack Compose 的 Android 项目，包含两个 Gradle 模块：

- `lib/`：可发布的 `compose-face-preview` 库，源码位于 `lib/src/main/java/com/sd/lib/compose/facepreview/`。
- `app/`：示例应用，用于演示相机权限、镜头切换、人脸检测及覆盖层绘制。
- JVM 本地测试位于各模块的 `src/test/`，设备测试位于各模块的 `src/androidTest/`。
- 依赖版本统一声明在 `gradle/libs.versions.toml`，发布信息位于 `lib/gradle.properties`。

可复用 API 应放在 `lib`；演示代码和手动验证流程应放在 `app`。

## 构建、测试与开发命令

从仓库根目录执行以下命令：

- `./gradlew :lib:assembleRelease`：构建 release AAR。
- `./gradlew :app:assembleDebug`：构建示例应用的 debug APK。
- `./gradlew testDebugUnitTest`：运行本地 JUnit 4 测试。
- `./gradlew connectedDebugAndroidTest`：在已连接的设备或模拟器上运行仪器化测试。
- `./gradlew lint`：对两个模块执行 Android Lint 检查。

`compose-camera` 和 `face-detector` 的版本由 `gradle/libs.versions.toml` 统一管理。

## 依赖审查约定

使用本库的项目必然已经配置 Compose 依赖，因此本库的 Compose 依赖保持使用 `implementation`，不要求改为 `api`。后续代码审查不得将 Compose 依赖未使用 `api` 列为问题。

## 编码风格与命名约定

遵循 `gradle.properties` 配置的 Kotlin 官方风格，使用两个空格缩进、多行声明保留尾随逗号，并使用显式导入。Composable 和类使用 `PascalCase`，函数和属性使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。类体中声明的私有属性使用下划线前缀，例如 `_tracker`；构造方法声明的私有属性不加下划线。公开 Composable 应将 `Modifier` 放在参数列表前部，并优先通过不可变状态或回调暴露行为。

## 测试规范

本地测试使用 JUnit 4，设备测试使用 AndroidX Test/JUnit4。测试类按被测对象命名，例如 `FacePreviewViewTest`；测试方法应描述可观察行为。相机、生命周期、权限和坐标变换相关逻辑应添加设备测试，纯计算逻辑放在本地测试中。有合适硬件时，需验证前后镜头的行为。

## 提交与 Pull Request 规范

当前工作副本不包含 Git 历史，因此无法推断仓库已有的提交规范。提交标题应简短并使用祈使语气，可添加作用域，例如 `fix(lib): discard stale frame results`。每次提交只聚焦一个改动。Pull Request 应说明行为变化、列出验证命令并关联相关 Issue；涉及覆盖层视觉变化时，应附截图或录屏。API 或发布元数据的变更必须明确标注。

## 安全与配置提示

不要提交本机 SDK 路径、真实签名密钥或凭据。仓库中的示例 keystore 仅用于开发；生产签名信息和 Maven Central 密钥必须保存在仓库之外。
