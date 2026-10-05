#!/usr/bin/env python3
import argparse
import inspect
import json
import os
import re
import shutil
import sys
from pathlib import Path


def configure_windows_stdio():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


configure_windows_stdio()


def add_dll_directory(path):
    try:
        os.add_dll_directory(str(path))
    except Exception:
        pass


def cuda_dll_search_dirs():
    dirs = []
    extra_dirs = os.environ.get("MEDIA_SPLITTER_CUDA_DLL_DIR", "")
    for entry in extra_dirs.split(os.pathsep):
        if entry:
            dirs.append(Path(entry))
    for env_name in ("CUDA_PATH", "CUDA_HOME"):
        value = os.environ.get(env_name)
        if value:
            dirs.append(Path(value) / "bin")
    cuda_root = Path("C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA")
    if cuda_root.exists():
        dirs.extend(sorted(cuda_root.glob("v*/bin"), reverse=True))
    site_packages = Path(sys.prefix) / "Lib" / "site-packages"
    dirs.extend(
        [
            site_packages / "torch" / "lib",
            site_packages / "nvidia" / "cublas" / "bin",
            site_packages / "nvidia" / "cudnn" / "bin",
            site_packages / "nvidia" / "cuda_runtime" / "bin",
        ]
    )
    for base in [Path("C:/index-tts-windows/.venv/Lib/site-packages/torch/lib")]:
        dirs.append(base)
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        if entry:
            dirs.append(Path(entry))
    seen = set()
    unique_dirs = []
    for path in dirs:
        try:
            resolved = path.resolve()
        except Exception:
            resolved = path
        key = str(resolved).lower()
        try:
            is_dir = path.is_dir()
        except Exception:
            is_dir = False
        if key in seen or not is_dir:
            continue
        seen.add(key)
        unique_dirs.append(path)
    return unique_dirs


def configure_cuda_dll_paths():
    dirs = cuda_dll_search_dirs()
    for path in dirs:
        add_dll_directory(path)
    if dirs:
        current_path = os.environ.get("PATH", "")
        os.environ["PATH"] = os.pathsep.join(str(path) for path in dirs) + os.pathsep + current_path
    return dirs


CUDA_DLL_DIRS = configure_cuda_dll_paths()


def find_cuda_dll(name):
    for path in CUDA_DLL_DIRS:
        candidate = path / name
        if candidate.exists():
            return candidate
    return None


def cuda_runtime_ready():
    return find_cuda_dll("cublas64_12.dll") is not None


def require_faster_whisper():
    try:
        from faster_whisper import WhisperModel
    except Exception as exc:
        raise RuntimeError(
            "faster-whisper is not installed. Install it with: "
            "install-faster-whisper.cmd"
        ) from exc
    return WhisperModel


def language_arg(value):
    return None if value in (None, "", "auto") else value


def repetition_key(text):
    return re.sub(r"[\s\W_]+", "", text, flags=re.UNICODE).lower()


def bigrams(value):
    if len(value) < 2:
        return {value} if value else set()
    return {value[index : index + 2] for index in range(len(value) - 1)}


def similar_repetition(current, previous):
    if not current or not previous:
        return False
    if current == previous:
        return True
    shortest = min(len(current), len(previous))
    if shortest >= 10 and (current in previous or previous in current):
        return True
    current_bigrams = bigrams(current)
    previous_bigrams = bigrams(previous)
    if not current_bigrams or not previous_bigrams:
        return False
    overlap = len(current_bigrams & previous_bigrams)
    return overlap / min(len(current_bigrams), len(previous_bigrams)) >= 0.92


def repeated_unit_prefix(value):
    chars = list(value)
    max_unit = min(80, len(chars) // 3)
    for size in range(2, max_unit + 1):
        unit = chars[:size]
        repeats = 1
        cursor = size
        while cursor + size <= len(chars) and chars[cursor : cursor + size] == unit:
            repeats += 1
            cursor += size
        if repeats >= 3 and cursor >= len(chars) * 0.8:
            return "".join(unit * 2)
    return None


def collapse_repeated_text(text):
    lines = []
    previous_key = ""
    repeated = 0
    for line in (part.strip() for part in text.splitlines()):
        if not line:
            continue
        key = repetition_key(line)
        compact = repeated_unit_prefix(key)
        if compact and len(compact) < len(key):
            line = compact
            key = compact
        if similar_repetition(key, previous_key):
            repeated += 1
        else:
            repeated = 1
            previous_key = key
        if repeated <= 2:
            lines.append(line)
    return "\n".join(lines).strip()


def model_repo_id(model_name):
    if "/" in model_name or Path(model_name).exists():
        return None
    if model_name.startswith("faster-whisper-"):
        return f"Systran/{model_name}"
    return f"Systran/faster-whisper-{model_name}"


def local_model_dir(download_root, repo_id):
    return Path(download_root) / "local" / repo_id.replace("/", "--")


def resolve_model_name(model_name, download_root):
    if Path(model_name).exists():
        return model_name
    repo_id = model_repo_id(model_name)
    if repo_id is None:
        return model_name

    try:
        from huggingface_hub import snapshot_download
    except Exception:
        return model_name

    target = local_model_dir(download_root, repo_id)
    snapshot_download(repo_id=repo_id, local_dir=target)
    return str(target)


def load_model(whisper_model, model_name, download_root, requested_device):
    attempts = []
    if requested_device in ("auto", "cuda"):
        if cuda_runtime_ready():
            attempts.append(("cuda", "float16"))
            attempts.append(("cuda", "int8_float16"))
        elif requested_device == "cuda":
            raise RuntimeError(
                "CUDA faster-whisper was requested, but cublas64_12.dll was not found. "
                "Install the CUDA 12 runtime/cuBLAS or run with device=auto for CPU fallback."
            )
    if requested_device in ("auto", "cpu"):
        attempts.append(("cpu", "int8"))

    first_error = None
    cuda_skip_reason = None
    if requested_device == "auto" and not cuda_runtime_ready():
        cuda_skip_reason = (
            "CUDA faster-whisper was skipped (no CUDA runtime found); CPU fallback used."
        )
    for device, compute_type in attempts:
        try:
            model = whisper_model(
                model_name,
                device=device,
                compute_type=compute_type,
                download_root=download_root,
            )
            fallback = None
            if first_error and device == "cpu":
                fallback = f"CUDA faster-whisper failed, CPU fallback used: {first_error}"
            elif cuda_skip_reason and device == "cpu":
                fallback = cuda_skip_reason
            return model, {
                "backend": f"faster-whisper:{device}",
                "gpuEnabled": device == "cuda",
                "fallbackReason": fallback,
            }
        except Exception as exc:
            if first_error is None:
                first_error = str(exc)

    raise RuntimeError(first_error or "failed to load faster-whisper model")


def is_corrupt_model_cache_error(error):
    return "model.bin" in error and ("Unable to open file" in error or "No such file" in error)


def model_cache_patterns(model_name):
    names = [model_name]
    if not model_name.startswith("faster-whisper-"):
        names.append(f"faster-whisper-{model_name}")
    return [f"models--Systran--{name}" for name in names]


def remove_model_cache(download_root, model_name):
    root = Path(download_root)
    removed = []
    for pattern in model_cache_patterns(model_name):
        path = root / pattern
        if path.exists():
            shutil.rmtree(path)
            removed.append(str(path))
    repo_id = model_repo_id(model_name)
    if repo_id is not None:
        path = local_model_dir(download_root, repo_id)
        if path.exists():
            shutil.rmtree(path)
            removed.append(str(path))
    return removed


def load_model_with_cache_repair(whisper_model, model_name, download_root, requested_device):
    resolved_model_name = resolve_model_name(model_name, download_root)
    try:
        return load_model(whisper_model, resolved_model_name, download_root, requested_device)
    except Exception as exc:
        message = str(exc)
        if not is_corrupt_model_cache_error(message):
            raise
        removed = remove_model_cache(download_root, model_name)
        if not removed:
            raise RuntimeError(
                f"{message}. The model cache looked corrupt, but no matching cache directory was found under {download_root}."
            ) from exc
        try:
            resolved_model_name = resolve_model_name(model_name, download_root)
            model, runtime = load_model(
                whisper_model,
                resolved_model_name,
                download_root,
                requested_device,
            )
            runtime["fallbackReason"] = (
                "Corrupt faster-whisper model cache was deleted and downloaded again."
            )
            return model, runtime
        except Exception as retry_exc:
            raise RuntimeError(
                f"{message}. Deleted corrupt model cache ({', '.join(removed)}) and retried once, but loading still failed: {retry_exc}"
            ) from retry_exc


def transcribe(args):
    whisper_model = require_faster_whisper()
    model, runtime = load_model_with_cache_repair(
        whisper_model,
        args.model,
        args.download_root,
        args.device,
    )
    transcribe_options = {
        "language": language_arg(args.language),
        "beam_size": 5,
        "vad_filter": True,
        "vad_parameters": {"min_silence_duration_ms": 500},
        "condition_on_previous_text": False,
        "compression_ratio_threshold": 2.4,
        "log_prob_threshold": -1.0,
        "no_speech_threshold": 0.6,
        "repetition_penalty": 1.1,
        "no_repeat_ngram_size": 3,
        "word_timestamps": False,
    }
    accepted_options = inspect.signature(model.transcribe).parameters
    compatible_options = {
        key: value
        for key, value in transcribe_options.items()
        if key in accepted_options
    }
    segments, _info = model.transcribe(args.audio, **compatible_options)

    cleaned_segments = []
    previous_key = ""
    repeated = 0
    suppressed_for_repetition = 0
    for segment in segments:
        text = collapse_repeated_text(segment.text or "")
        key = repetition_key(text)
        if not text or not key:
            continue
        if similar_repetition(key, previous_key):
            repeated += 1
        else:
            repeated = 1
            previous_key = key
        if repeated <= 2:
            cleaned_segments.append(
                {
                    "startCs": round(segment.start * 100),
                    "endCs": round(segment.end * 100),
                    "text": text,
                }
            )
        else:
            suppressed_for_repetition += 1

    payload = {
        "runtime": runtime,
        "segments": cleaned_segments,
        "suppressedForRepetition": suppressed_for_repetition,
    }
    print(json.dumps(payload, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe", action="store_true")
    parser.add_argument("--audio")
    parser.add_argument("--model", default="large-v3")
    parser.add_argument("--language", default="auto")
    parser.add_argument("--download-root")
    parser.add_argument("--device", choices=["auto", "cuda", "cpu"], default="auto")
    args = parser.parse_args()

    try:
        if args.probe:
            require_faster_whisper()
            print(json.dumps({"ok": True}))
            return
        if not args.audio:
            raise RuntimeError("--audio is required")
        if not args.download_root:
            raise RuntimeError("--download-root is required")
        transcribe(args)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
