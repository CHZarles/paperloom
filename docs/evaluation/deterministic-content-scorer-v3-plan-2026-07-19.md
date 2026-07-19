# 确定性 Content Scorer v3 改造计划与验收记录

日期：2026-07-19

状态：实现与验收完成。

## 1. 要解决的问题

Golden Case 可以声明 `facts`：

```yaml
facts:
  beta2: "0.98"
```

旧 `BehaviorScorer._score_content()` 不读取用户最终看到的 `markdown`，而是把可选、无类型的
`research_answer.fields` 当作完整 Fact Contract：

```text
fields 为空       -> content=not_run，不阻止 Hard Pass
fields 有任意字段 -> 要求全部 Golden Fact Key，否则 FACT_MISSING
```

因此，同一份正确答案会因为附带一个无关 `evidence_id` 而失败；错误答案只要不输出 `fields`，Content
又可能完全不执行。这不是单个 Case 的匹配问题，而是评分输入与产品答案合同不一致。

## 2. 改造目标

1. Content 评分检查用户可见 Markdown，而不是猜测任意 `fields` 的含义。
2. 数值、百分比、科学计数法、数量和短语使用确定性、可测试的 Typed Assertion。
3. Fact Label 和 Value 必须出现在同一个句子、列表项、表格行或对比子句中。
4. 不支持的 Fact 类型进入 `review_required`，不能 Hard Pass，也不能静默跳过。
5. 结构化 Fact 只有声明 `fields_schema=golden-facts/v1` 时才参与评分。
6. Score Report 必须记录 Scorer Contract 和 SHA-256，禁止混合不同评分合同做模型比较。
7. 已保存 Run 可以离线复评分，不重新调用 MiniMax。

## 3. 非目标

- 不修改 Research Prompt、模型可见 Tool Schema、Golden 问题、Anchor、Reading Model 或语料路径；
- 不按 Case ID、Anchor ID 或某个预期值写例外；
- 不删除 Content 评分，不把缺失 Fact 改回 `not_run`；
- 不引入在线 Judge 或第二个模型；
- 不把人工语义质量与确定性合同分数合成一个数字。

## 4. Target Contract

### 4.1 User-visible Markdown

普通 MiniMax 输出没有声明 Fact Schema，评分器从 `markdown` 读取 Fact Assertion：

```text
β₂ = 0.98
-> NFKC / Greek / Subscript Normalize
-> beta2 = 0.98
-> labeled_decimal(beta2, 0.98)
-> pass
```

科学计数法使用通用指数归一化，例如 `10⁻⁹`、`10⁻¹²` 和 `1 × 10^{-12}`，实现中不包含针对
当前 Golden Expected Value 的 `1e-9` 特例。

当前支持的 Golden Fact Key：

| Fact | Matcher |
| --- | --- |
| `optimizer` | Labeled Scalar |
| `beta1` / `beta2` | Labeled Decimal |
| `epsilon` | Labeled Scientific Number |
| `transformer_role` | Normalized Phrase |
| `environment_count` | Labeled Count |
| `question_count` | Labeled Count |
| `human_success_rate` | Subject-bound Percentage |
| `gpt4_plugins_success_rate` | Subject-bound Percentage |
| `application_count` | Labeled Count，接受 application、app、domain |

### 4.2 Declared Structured Facts

确定性 Fixture 可以声明：

```json
{
  "fields_schema": "golden-facts/v1",
  "fields": {
    "beta2": "0.98"
  }
}
```

只有这个显式合同允许评分器逐 Key 比较 `fields`。普通模型输出中的任意 `fields` 不作为评分输入。

### 4.3 Content Status

```text
pass             所有支持的 Fact Assertion 均匹配
fail             支持的 Assertion 缺失或值不匹配
review_required  Golden Fact 没有确定性 Matcher
not_applicable   Case 没有 facts
```

Hard Pass 要求所有适用维度为 `pass`；`fail` 与 `review_required` 都会阻止 Hard Pass。

## 5. 实现步骤

1. 在真实 `β₂ = 0.98` 失败样本上先写 Regression Test，确认旧实现得到 `FACT_MISSING:beta2`。
2. 增加 `evaluation/fact_assertions.py`，集中维护 Matcher、Normalization 和 Claim Unit。
3. 让 `BehaviorScorer` 委托 Typed Fact Assertion，并删除“任意非空 fields 激活评分”的逻辑。
4. 给 Golden Fixture 增加显式 `golden-facts/v1` Schema。
5. 把 Score Report 升级为 `harness-score-report/v3`，写入 `behavior-scorer/v3` Contract 与 Hash。
6. 让人工盲审工具继续读取冻结 v2 报告，但拒绝在同一次比较中混用 v2 与 v3。
7. 增加 `rescore` CLI，从持久目录读取 `harness_run.json` 并离线生成 v3 Report。

## 6. 验收 Gate

- 正确 Markdown 加无关 `fields`：Content Pass；
- 错误 Markdown 加空 `fields`：Content Fail；
- Expected Value 只出现在无关句子：Content Fail；
- `β₂`、任意整数指数科学计数法、英文数字词、百分比和 Markdown 表格均正确归一化；
- Human 与 GPT-4 百分比互换时同时失败；
- 不支持的 Fact 为 `review_required`，Hard Pass 为 False；
- Fixture Stable `10/10`、Expanded `24/24`；
- 冻结 v2 人工盲审报告仍可重建；
- 完整 Harness 测试通过；
- 旧、新 MiniMax 保存产物用同一 v3 Contract 复评分并保存到非临时目录。

## 7. 验收结果

### 自动验证

```text
Harness unittest: 163 passed, 1 skipped
Stable Fixture:    10/10
Expanded Fixture:  24/24
```

冻结 v2 人工盲审报告仍能从原始 30 Case 产物重建。v3 报告包含：

```text
score report:    harness-score-report/v3
scorer contract: behavior-scorer/v3
fact contract:   typed-markdown-facts/v1
contract hash:   9828d261617a63e8748e9d9c7e128e7145516fb778ff85fd588d48ee12519ea0
```

### 已保存 MiniMax 产物复评分

没有重新调用模型：

| 保存产物 | v2 参考 | v3 | 变化 |
| --- | ---: | ---: | --- |
| 旧 MiniMax，匹配当前 24 Case | `12/24` | `11/24` | 旧答案没有明确写出 Optimizer 为 Adam，v3 不再因空 fields 跳过 Content |
| 当前 Stable | `6/10` | `7/10` | 修复 `bert_choice_followup_001` 的假阴性 |
| 当前 Expanded | `9/24` | `10/24` | 修复 `adam_constraint_refinement_followup_001` 的假阴性 |

完整报告保存在：

```text
research/golden-data/local-runs/scorer-v3-rescore-20260719/
```

历史 v2 Report、原始 Run、Prompt、Golden Case、Anchor、Reading Model 和语料路径均未覆盖或修改。
