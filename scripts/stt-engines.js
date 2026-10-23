/**
 * STT 引擎选择（纯函数，不依赖 Firebase / Python，方便单测）。
 *
 * 两个后端：
 *   - sensevoice     SenseVoice-Small（sherpa-onnx / ONNX Runtime，CPU int8，默认）
 *   - faster-whisper CTranslate2 faster-whisper（原后端，保留作回退）
 *
 * SenseVoice-Small 只支持 zh / yue / en / ja / ko 五种语言：
 * 其它语种（例如法语、德语）必须回退 faster-whisper，否则会输出乱码。
 */

/** 可选值：sensevoice / faster-whisper。默认 sensevoice。 */
export const STT_ENGINES = ["sensevoice", "faster-whisper"];

/** SenseVoice-Small 支持的语种（monitor.js 归一化后的写法）。 */
export const SENSEVOICE_LANGUAGES = ["zh", "yue", "en", "ja", "ko"];

const DEFAULT_ENGINE = "sensevoice";

/** 把环境变量 / 配置里的写法归一成引擎名；无法识别时回退默认引擎。 */
export function normalizeSttEngine(value) {
  const key = String(value ?? "").trim().toLowerCase();
  if (!key) return DEFAULT_ENGINE;
  if (["sensevoice", "sensevoice-small", "sense-voice", "sense_voice", "sv"].includes(key)) {
    return "sensevoice";
  }
  if (["faster-whisper", "fasterwhisper", "faster_whisper", "whisper", "faster"].includes(key)) {
    return "faster-whisper";
  }
  return DEFAULT_ENGINE;
}

/** language 是 normalizeLanguage() 的结果；"auto" 表示交给模型自己判断。 */
export function senseVoiceSupportsLanguage(language) {
  const code = String(language || "").trim().toLowerCase();
  if (!code || code === "auto") return true;
  return SENSEVOICE_LANGUAGES.includes(code);
}

/**
 * 决定这次转录实际用哪个引擎。
 *
 * @param {object} options
 * @param {string} options.engine       用户配置（原始值）
 * @param {string} options.language     归一化语种（zh/ja/en/ko/yue/auto）
 * @param {boolean} options.senseVoiceReady SenseVoice 后端是否可用（python 包 + 模型）
 * @returns {{engine: "sensevoice"|"faster-whisper", fallbackReason: string|null}}
 */
export function resolveSttEngine({ engine, language, senseVoiceReady = true } = {}) {
  const preferred = normalizeSttEngine(engine);
  if (preferred === "faster-whisper") {
    return { engine: "faster-whisper", fallbackReason: null };
  }
  if (!senseVoiceReady) {
    return { engine: "faster-whisper", fallbackReason: "sensevoice-not-ready" };
  }
  if (!senseVoiceSupportsLanguage(language)) {
    return { engine: "faster-whisper", fallbackReason: "language-unsupported" };
  }
  return { engine: "sensevoice", fallbackReason: null };
}

/** 回退原因 → 给人看的一句话（写进日志）。 */
export function describeFallback(reason, language) {
  switch (reason) {
    case "sensevoice-not-ready":
      return "SenseVoice 后端不可用（未安装 sherpa-onnx 或模型缺失），本次改用 faster-whisper";
    case "language-unsupported":
      return `SenseVoice 不支持语种「${language || "auto"}」，本次改用 faster-whisper`;
    default:
      return null;
  }
}
