# D2-C2C5 交审

源基线：`4b2356917009520e1f2803787070a502127cd722`（含 PR #47 ORDERPX-PERSIST）。
分支：`feature/d2-c2c5-harden-20261022`。证据中的 tip_sha 标记此源基线；交付提交 SHA 由 git 日志给出，避免自引用。

文件清单（全部在 research/）：

- `coinresearch/calibration.py`：复用 G0/G2/guard 的 C2–C5 合成密封入口。
- `coinresearch/cli.py`：`calibrate-c2c5` 参数、最小次数与退出码。
- `tests/test_calibration.py`：C1/C2/C3 共享原模拟；保留原断言，C2 明确断言 R≥5000。
- `tests/test_c2c5_calibration.py`：12 项快速测试，完整 C3/C4/C5、拒跑、SHA、逐字节复现、CLI 默认及失败退出码。
- `outputs/d2_c2c5_calibration/{c2c5_calibration,prereg,run_record}.json`：证据、预注册内容和运行记录。
- `README.md`：命令、口径、边界；保留 D1/D2/D3/D4。
- `D2_C2C5_NOTES.md`：本交审记录。

命令（research/ 内，PY=/Users/jenniferni/jenifer/venv-research/bin/python）：

```sh
$PY -m pytest -q
$PY -m pytest -q -s tests/test_calibration.py tests/test_acceptance.py tests/test_gates.py
$PY -m pytest -q -s tests/test_c2c5_calibration.py
$PY -m coinresearch.cli calibrate-c2c5 --out-dir outputs/d2_c2c5_calibration --tip-sha 4b2356917009520e1f2803787070a502127cd722
# htx-trend-python/ 内，只跑原基线测试
PYTHONPATH=src $PY -m pytest -q
```

验证结果：

- research 全量：161 passed, 1 skipped，28.76s。skip 为外部真实数据回归依赖目录未配置/不可用，不能视为真实数据验收通过。
- 原校准/验收/门禁聚焦：29 passed，21.55s。
- 新快速测试：12 passed，0.76s。两个独立输出目录全部文件 SHA 相同。
- HTX 未修改基线：499 passed，9.60s。
- CLI 完整 R=5000 实跑退出码 0，all_pass=true；master_seed=20261019。
- C2：power=0.809，位于 [0.75,0.85]；真实效应=MDE=0.05673492824824346。C1 同设定假阳性 0.0626。
- C3：B=2000，30/60 两档通过；joint_var 分别 0.00024365446674613566 / 0.00024378587810093178；per_coin_var 分别 0.00005215625523145032 / 0.000051982815240406864；truth_var=0.000234375。
- C4：estimated_neff=8642.22695476034，true_neff=8421.052631578947，相对偏差=2.626445087779028%，通过；M2 DEFF=8.45998、N_eff=711.4674029962248、MDE=49.39687675614539，全部误差≤1%。
- C5：B=5000、diagnostic_only=true，单侧分层结构保留；B<5000、未封存、缺 SHA、错 SHA 拒绝检查通过，无解锁字段。

硬禁核对：仅 research/ 改动；Config/g0/g2/guard 及原 test_acceptance/test_gates 无改动；未改 G2 门槛、联合日历块默认或仅 PASS 解锁语义；未碰 research/lob_prob；未改开仓/止损/风控或主线 A/B；未改 htx-trend-python；未执行 ALTER/SQL 或回填；未 SSH、未出运行时包、未装机、未热更、未接 PAPER/LIVE；未开 PR、未 merge。提交仅普通 push，无 force。
