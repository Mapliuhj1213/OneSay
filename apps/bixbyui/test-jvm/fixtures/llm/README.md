# 大模型服务商回复样本（JVM 单测用，不联网）

按各家官方 API 文档的回复格式整理的样本（字段名、事件顺序与官方示例一致；文字、id、用量是假值，不是真实账号录制，不含任何 Key）。
- `anthropic_messages.json`：Messages API 非流式回复（text + tool_use，stop_reason=tool_use）。
- `anthropic_stream.sse`：Messages API 流式（message_start → content_block_* → message_delta → message_stop，含 ping、input_json_delta）。
- `anthropic_stream_cut.sse`：同上但中途断开（没有 message_delta / message_stop）。
- `anthropic_models.json`：GET /v1/models。
- `gemini_generate.json`：generateContent 回复（text + functionCall，无 id）。
- `gemini_stream.sse`：streamGenerateContent?alt=sse（最后一帧只带用量）。
- `gemini_blocked.json`：promptFeedback.blockReason。
- `gemini_model.json`：GET /v1beta/models/{model}。
- `openai_models.json`：OpenAI 兼容 GET /models。
- `openai_error_401.json`、`anthropic_error_529.json`、`gemini_error_400_key.json`：报错正文。
