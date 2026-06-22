# Iris 构建说明

## GitHub Actions 自动构建

本项目已配置 GitHub Actions 自动构建工作流。

### 触发条件

- **推送**：当代码推送到 `main` 或 `master` 分支时
- **Pull Request**：当 PR 目标是 `main` 或 `master` 分支时
- **发布**：当推送 `v*` 标签时（如 `v3.9.3`）

### 构建产物

每次构建成功后，会自动上传以下文件作为构建产物：

- `build/libs/Iris-*.jar` - 主插件 JAR 文件
- `core/build/libs/*.jar` - Core 模块 JAR 文件

你可以在仓库的 **Actions** 页面下载这些产物。

### 如何触发自动发布

1. 更新 `build.gradle.kts` 中的版本号
2. 提交并推送更改
3. 创建并推送版本标签：

```bash
git tag v3.9.3
git push origin v3.9.3
```

4. GitHub Actions 会自动：
   - 构建项目
   - 创建 GitHub Release
   - 上传 JAR 文件到 Release

### 本地构建

如果你想在本地构建：

```bash
# 克隆仓库
git clone https://github.com/windy664/Iris.git
cd Iris

# 构建
./gradlew build

# 构建产物位置
# build/libs/Iris-*.jar
```

**注意**：需要 JDK 21 或更高版本。

### 构建状态

在仓库首页 README 中添加构建状态徽章：

```markdown
![Build Iris](https://github.com/windy664/Iris/actions/workflows/build.yml/badge.svg)
```

## 工作流详情

查看完整的工作流配置：[`.github/workflows/build.yml`](.github/workflows/build.yml)
