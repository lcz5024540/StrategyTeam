# skills - find-skill
### 一、简介
find-skills 是一个跨平台、开源的技能发现与管理工具。它的核心使命是打破不同 AI 编程助手（Agent）之间的壁垒，为开发者提供一个统一的入口，用于搜索、安装和管理社区构建的扩展技能。
简单来说，它就像是 AI 编程领域的 npm 或 App Store，让 Claude Code、Codex、Cursor 等不同工具都能共享同一个繁荣的技能生态。
find-skills 本质上是一个包管理器（类似于 npm 或 pip）。当你运行安装命令时，它会检测你电脑上安装了哪些 AI 工具，并自动将技能文件部署到对应的配置目录中。
例如：
* **对于 Claude Code，它会将技能安装到 ~/.claude/skills/ 或项目下的 .claude/skills/。**
* **对于 Codex，它会安装到 ~/.codex/skills/**

### 二、主要功能
* **技能搜索**：通过关键词搜索全球开发者发布的技能
* **技能安装**：直接调用安装命令，集成技能到 Claude Code环境
* **技能管理**：列出当前已安装的技能，方便管理

## 三、安装 find-skill（以 Claude Code为例）
#### **方法一：标准安装（推荐）**
```bash
npx skills add https://github.com/vercel-labs/skills --skill find-skills
```
执行后会出现交互式界面，建议选择：

* 安装位置：选择`Claude Code (.claude/skills)`并确认
* 安装范围：根据需求选择全局或项目范围
* 安装方式：推荐选择`SymLink`（符号链接）

#### 方法二：**快速安装（跳过交互）**
```bash
npx skills add https://github.com/vercel-labs/skills --skill find-skills -g -y
# 或者 
npx skills add vercel-labs/skills@find-skills -g -y
```
### 四、验证安装
#### 1、列出npx 安装的 skill
```bash
npx skills list -g
```
![image](images/1FAnzLVIkWq0UjzZN3CHwZrx8pZdS4hRF8KU8tlccD4.png)

#### 2、检查安装位置：
```bash
ls ~/.claude/skills/
```
正常find-skill 都会安装在 claude 的安装路径中

![image](images/q8wYyNRcxHU31pyPWg7kQC1RkcClwN9MGxEZW-f2drc.png)

#### 3、检查技能文件：
```bash
cat ~/.claude/skills/find-skills/SKILL.md | head -20
```
![image](images/RkLDzJcsEFHKkkXS9lQtf1Mdtc_O-n3K3laf491Ti90.png)

#### 4、claude 环境中验证
```Plain Text
#进入 claude 环境
claude
# 查看已安装的技能
claude --list-skills
```
![image](images/kgQsdF6vDMclPUuoMZzUJ83mpSgLh6SB0GFZYHoCYUE.png)

#### 五、使用
#### 5.1、交互式搜索
可通过对话形式搜索全球发布的 skill

前提：进入到 claude 环境

```Plain Text
/find-skills
```
系统将提示您输入搜索关键词，如下：

![image](images/hUVtUaMPiAaaTAuK4PnNKZ2I5CZU-JiTHLGPHaL9k44.png)

#### 5.2、直接对话使用
```Plain Text
帮我找一个能画流程图的 skill
有没有处理PDF文件的技能？
```
## 六、实用 skill 推荐
#### 6.1、绘图与可视化
**Mermaid技能** - 用于绘制流程图、序列图等

```bash
/find-skills mermaid
```
**PlantUML技能** - UML图绘制工具

```bash
/find-skills plantuml
```
### 6.2 、开发工具
**Git技能** - 增强的Git操作支持

```bash
/find-skills git
```
**Docker技能** - Docker容器管理

```bash
/find-skills docker
```
### 6.3 办公效率
**Notion技能** - 与 Notion 笔记应用集成

```bash
/find-skills notion
```
**Slack技能** - Slack消息发送和管理

```bash
/find-skills slack
```
## 七、高级用法
#### 7.1、技能管理
```bash
# 查看已安装技能
/find-skills list

# 卸载技能
/find-skills uninstall <skill-name>
```
#### 7.2、自定义搜索
```bash
# 搜索特定语言的技能
/find-skills python
/find-skills javascript

# 搜索特定功能的技能
/find-skills database
/find-skills testing
```


