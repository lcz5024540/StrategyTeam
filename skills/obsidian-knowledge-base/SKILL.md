---
name: obsidian-knowledge-base
description: "把项目里散落的 Markdown 文档整理成 Obsidian 风格的知识库：补 frontmatter 元数据、加 tags、用 wikilink 双链互联、建 MOC 索引地图、规整目录结构。当用户说「整理文档」「文档太乱」「建知识库」「做文档索引」「Obsidian」，或需要让大量 md 文件可检索、可导航时使用。"
---

# Obsidian 知识库整理 (Obsidian Knowledge Base)

CoinStrategy 项目里有大量 Markdown 文档散落在根目录、`docs/`、`funding-rate/`、`polymarket/` 等处。本技能把它们整理成结构清晰、可导航、可检索的 **Obsidian 风格知识库**。

## 何时使用

- 文档太多、太散，找不到、看不出关系。
- 想给一批文档建立统一索引/导航。
- 用户提到「整理文档」「知识库」「Obsidian」「文档索引」。

## Obsidian 风格的四个要素

### 1. Frontmatter 元数据
每个文档头部加 YAML 元数据，便于检索和分类：

```markdown
---
title: 资金费率套利策略
type: strategy        # strategy / design / report / runbook / research
status: active        # draft / active / archived
tags: [funding-rate, arbitrage, cex]
created: 2026-03-29
updated: 2026-05-23
related: ["[[funding-rate-arbitrage-design]]"]
---
```

### 2. Tags 标签体系
建立一套**受控标签**（不要随意发明），按维度划分：

- 按策略：`#funding-rate` `#polymarket` `#top50` `#technical-analysis`
- 按文档类型：`#design` `#report` `#runbook` `#research` `#troubleshooting`
- 按模块：`#core` `#job` `#web` `#common-client` `#frontend`
- 按状态：`#active` `#archived` `#wip`

把标签表维护在一个 `tags.md` 里，新增标签前先查表。

### 3. Wikilink 双向链接
用 `[[文档名]]` 把相关文档连起来，形成知识网络：

- 设计文档链接到它的排查报告、复盘、运行手册。
- 报告链接回它分析的策略/设计。
- 用 `[[文档名|显示文字]]` 自定义链接文字。
- 在文档底部加「## 相关文档」小节集中放链接。

> 注意：标准 Markdown 用相对路径链接 `[文字](path.md)`；Obsidian 用 `[[wikilink]]`。
> 若文档既要在 GitHub 看又要在 Obsidian 用，**优先用相对路径链接**，frontmatter 的 `related` 字段可保留 wikilink。整理前先和用户确认主要在哪看。

### 4. MOC —— 内容地图（索引）
为每个主题建一个 **MOC（Map of Content）** 文档作为入口，例如 `polymarket-MOC.md`：

```markdown
# Polymarket 知识地图

## 架构与设计
- [[polymarket_arbitrage_system_design]]
- [[multi-strategy-architecture-design]]

## 策略
- [[follow-trade-entry-exit-strategy]]

## 运行与排查
- [[deployment-runbook-2026-05-17]]
- [[排查报告-2026-05-17]]
```

顶层再建一个总 MOC（`Home.md` 或 `知识库首页.md`）链接到各主题 MOC。

## 推荐目录结构

整理时不强制大搬家，**先建索引、补元数据，再按需归位**。理想结构：

```
docs/
  Home.md              # 总入口 MOC
  tags.md              # 受控标签表
  polymarket/          # 按主题分目录
    polymarket-MOC.md
  funding-rate/
    funding-rate-MOC.md
  strategies/
  reports/             # 排查/复盘报告
  runbooks/            # 运行手册
```

## 整理工作流

1. **盘点**：扫描全项目 `*.md`，列清单（路径、标题、大致主题、新旧）。
2. **分类**：按主题/类型给每个文档打标，识别重复、过时文档。
3. **补元数据**：给文档加 frontmatter（title/type/tags/dates）。
4. **建链接**：补充文档间的相互引用。
5. **建 MOC**：每个主题一个索引，再建总入口。
6. **处理过时文档**：和用户确认后归档（移到 `archived/` 或标 `status: archived`），不直接删除。
7. **输出报告**：说明整理了哪些、改了什么、建议后续怎么维护。

## 重要约束

- **不删除、不大规模移动文件前必须经用户确认** —— 项目文档可能被代码或其他文档引用。
- 移动文件后检查并修复指向它的链接。
- 保留文档原有内容，本技能只做**组织和元数据**，不改写正文（除非用户要求）。
- 整理过程用 `context-tracking` 技能记录改动。

## 完成前自检清单

- [ ] 是否盘点了全部 md 文档？
- [ ] 标签是否取自受控标签表，没有随意发明？
- [ ] 每个主题是否都有 MOC，是否有总入口？
- [ ] 移动文件后链接是否都修好了？
- [ ] 过时文档是否经确认后才归档？
- [ ] 是否产出了整理报告？
