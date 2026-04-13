# skills - find-skill
### 一、简介
find-skill 是Claude Code的一个扩展技能，它充当了Claude Code的"应用商店"或"搜索引擎"，帮助用户发现、搜索并安装社区开发的扩展技能，从而增强 Claude Code 的原生能力。

### 二、主要功能
* **技能搜索**：通过关键词搜索全球开发者发布的技能
* **技能安装**：直接调用安装命令，集成技能到 Claude Code环境
* **技能管理**：列出当前已安装的技能，方便管理

## 三、安装 find-skill
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


