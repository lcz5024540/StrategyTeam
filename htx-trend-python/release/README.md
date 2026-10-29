# G3 candidate packaging

**候选包 ≠ 可以热更 ≠ 可装现网**。仅 G3.1–G3.4b；G3.5 干净环境验收由测试另行执行，G3.6–7 热更/现网安装不在本轮范围。不包含真实 admission/approval，不执行 SQL，不连接交易账户。

从仓库根目录复制执行（现有 Python 3.11 环境，无安装步骤）：

```sh
cd /Users/jenniferni/jenifer/build/CoinStrategy/htx-trend-python
export http_proxy=http://127.0.0.1:7897 https_proxy=http://127.0.0.1:7897 HTTPS_PROXY=http://127.0.0.1:7897 ALL_PROXY=socks5h://127.0.0.1:7897
export PYTHONPATH=src:/private/tmp/htx-r1-clean-cert-11126/lib/python3.11/site-packages
PY=/private/tmp/r0-entry-venv/bin/python
VER="0.1.2-g3-candidate-$(git rev-parse --short=8 HEAD)"
OUT="/tmp/g3-codex/frozen/$VER"
TAR="$OUT/htx-trend-python-$VER.tar.gz"
"$PY" release/package.py build
"$PY" release/package.py verify "$TAR"
(cd "$OUT" && LC_ALL=C shasum -a 256 -c "$(basename "$TAR").sha256")
"$PY" release/package.py approval-template "$TAR" --output "$OUT/approval.template.json"
"$PY" -m pytest tests/ -q --tb=short
"$PY" -m htx_trend.cli self-test
```

冻结目录已存在时 build 拒绝覆盖；已有包直接执行 verify。需要新构建时使用新的 `--out-root /tmp/g3-codex/<review-build>`，或提交后按新 HEAD 构建。默认版本固定为 `0.1.2-g3-candidate-<8位HEAD>`，不接受任意版本输入。

## 文件与绑定

- 固定制品：`release/artifacts/{strategy_card,feature_dict,experiment_ledger}.{schema,example}.json`。验证器 `src/htx_trend/release_artifacts.py` 读取 schema、严格验证必填字段/类型/枚举/引用；仅实现这些 schema 使用的 JSON Schema 子集，未知关键字失败。示例均 SYNTHETIC/candidate-only，无研究结论或 LIVE 权限。
- tar 包含完整 `src/htx_trend/**/*.py`、`pyproject.toml`、README、打包入口和制品，使用 src 布局；依赖在 pyproject 声明，未捆绑环境或执行安装。排除 SQL、测试、环境文件、密钥及授权文件。包内 `release/config.example.json` 为生成的非运行配置示例。
- `MANIFEST.json.files` 与 tar 普通文件成员严格 1:1，无目录/软链接/重复成员。普通文件 SHA256 为原始字节；**清单自身**为 canonical JSON（G2 canonical 规则），仅将 `files["MANIFEST.json"].sha256` 清空后求 SHA256。这是明确的自引用例外，不是自身原始字节 SHA。
- 最终 tar 原始字节 SHA256 只能在打包完成后计算；包内 `bindings.package_sha256` 指向包外 `BUILD.json#/package_sha256`，不得伪称包内包含最终 SHA。BUILD 同时记录清单原始字节 SHA、所有绑定字段；`.tar.gz.sha256` 支持独立重算。verify 检查 tar、逐成员哈希、成员集合、外置清单、BUILD、config/artifact 引用一致性。不进行解压或运行。
- `source_commit` 是完整 HEAD；未提交实现会标记 `working_tree_dirty=true`，实际字节由清单锁定，不能声称全部源码已在 HEAD 中。提交后应重新构建以绑定新 HEAD。
- `config_fingerprint=config_hash=SHA256(config.example.json 原始字节)`，只绑定候选示例，不代表账户运行配置。策略版本与 profile/spec hash 取现有 G2 运行合同；制品引用暴露 id/version/hash/feature keys/experiment ids。
- Approval 模板为 G2 `htx-live-approval-g2` 外层和 `LiveApproval` 内层字段。package/source/config/spec/version/profile 从验证后的 BUILD 取值，`synthetic:true` 固定，无更改选项；账户为 0、admission hash/seal、有效期及 approval seal 留空，因此不是可用授权。模板在 tar 外产生，避免循环绑定。
- 后续真实准入必须另获授权、核对研究/PAPER 证据，并独立采集 `context.json` 中 `admission.BINDINGS` 所列的运行绑定；**不得从模板复制 context 当作独立证据**。G2 admission 内层 `source_commit/config_hash/strategy_version` 与 approval 对应字段交叉绑定，`admission_hash/admission_seal_hash` 必须来自另行核验的 admission。哈希校验只证明完整性，不证明签发者或部署权限。

交给测试时传递 tar、sha256、MANIFEST、BUILD 和可选模板。G3.5 尚未执行，不据工程测试结果宣称现网可安装。

## PAPER evidence compact-v2

[运行、盘点、部署与回滚说明](EVIDENCE-RETENTION.md)。默认 legacy-v1；新格式显式配置，只影响 PAPER。历史数据无自动删除。
