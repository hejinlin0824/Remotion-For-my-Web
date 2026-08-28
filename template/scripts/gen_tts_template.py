# -*- coding: utf-8 -*-
"""
gen_tts_template.py — 模板音频一次性生成（fixed 2 句 + content.json 全部场次）
产出: public/audio/fixed/act1.wav act5.wav, public/audio/lines/line_NN.wav, src/data/audio_meta.json
重跑: 删除 public/audio/lines 与 fixed 后整批重跑（禁止混批，防跨批次音色漂移）
Key:  环境变量 DASHSCOPE_API_KEY 优先，否则解析旧项目 TTS 配置文件的 ApiKey 行
"""
import json, math, os, pathlib, struct, sys, time
import requests
from dashscope.audio.qwen_tts import SpeechSynthesizer

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONTENT = ROOT / "src" / "data" / "content.json"
META_OUT = ROOT / "src" / "data" / "audio_meta.json"
AUDIO_DIR = ROOT / "public" / "audio"
FALLBACK_KEY_FILE = pathlib.Path(r"C:\Users\jinlin.he\Desktop\remotion参考(1)\remotion参考\TTS就选它.txt")

VOICE, MODEL, THROTTLE, MAX_TAKES, DUR_RATIO = "Cherry", "qwen-tts", 3.0, 3, 0.92
FIXED_LINES = {
    "act1": "你好啊同学，我来帮你解决这道题，坐好发车！",
    "act5": "这就是本道题的解法，希望能够帮到你，祝你考研一战上岸！",
}

def load_key() -> str:
    if os.environ.get("DASHSCOPE_API_KEY"):
        return os.environ["DASHSCOPE_API_KEY"].strip()
    for line in FALLBACK_KEY_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        if line.strip().lower().startswith("apikey:"):
            return line.split(":", 1)[1].strip()
    sys.exit("[fatal] 未找到 DashScope Key（设 DASHSCOPE_API_KEY）")

def wav_duration(path: pathlib.Path) -> float:
    raw = path.read_bytes()
    if len(raw) < 44 or raw[0:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError(f"{path.name} 不是合法 WAV")
    ch, sr = struct.unpack("<H", raw[22:24])[0], struct.unpack("<I", raw[24:28])[0]
    bits = struct.unpack("<H", raw[34:36])[0]
    byte_rate = ch * sr * bits // 8
    if byte_rate <= 0:
        raise ValueError(f"{path.name} byte_rate 异常")
    return len(raw) / byte_rate

def parse_pcm(path: pathlib.Path):
    raw = path.read_bytes()
    pos = 12
    while pos + 8 <= len(raw):
        cid, size = raw[pos:pos+4], struct.unpack("<I", raw[pos+4:pos+8])[0]
        if cid == b"data":
            pcm = raw[pos+8:]
            n = len(pcm) // 2
            return struct.unpack(f"<{n}h", pcm[:n*2]), struct.unpack("<I", raw[24:28])[0]
        pos += 8 + size + (size & 1)
    return None, None

def rms(seg) -> float:
    return math.sqrt(sum(x*x for x in seg) / max(1, len(seg)))

def tail_profile(path: pathlib.Path):
    samples, sr = parse_pcm(path)
    if not samples:
        return 1e9, 1e9, 1e9
    w, w3 = int(sr*0.08), int(sr*0.24)
    return (rms(samples[-w:]), rms(samples[-w-w3:-w]), rms(samples[-w-2*w3:-w-w3]))

def is_complete(t: dict) -> bool:
    if t["last80"] < 100.0:
        return True
    return t["last80"] < 0.35*t["prev240"] and t["last80"] < 0.35*t["prev480"]

def find_audio_url(obj):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k.lower() in ("url", "audio_url") and isinstance(v, str) and v.startswith("http"):
                return v
            found = find_audio_url(v)
            if found:
                return found
    elif isinstance(obj, list):
        for item in obj:
            found = find_audio_url(item)
            if found:
                return found
    return None

def synth(key: str, text: str, dest: pathlib.Path) -> bool:
    resp = SpeechSynthesizer.call(model=MODEL, api_key=key, text=text, voice=VOICE)
    if resp.status_code != 200:
        print(f"    [tts] status={resp.status_code} {getattr(resp, 'message', '')}")
        return False
    url = find_audio_url(resp.output)
    if not url:
        return False
    r = requests.get(url, timeout=180)
    r.raise_for_status()
    dest.write_bytes(r.content)
    return True

def synthesize_line(key: str, text: str, dest: pathlib.Path) -> dict:
    """≤3 take，完整性+时长过滤后取尾部最静。全败 sys.exit(1)。"""
    takes = []
    for take in range(MAX_TAKES):
        tmp = dest.with_suffix(f".take{take+1}.wav")
        try:
            ok = synth(key, text, tmp)
        except Exception as e:
            print(f"    [net] {e}")
            ok = False
        if ok:
            l80, p240, p480 = tail_profile(tmp)
            dur = wav_duration(tmp)
            takes.append({"dur": dur, "last80": l80, "prev240": p240, "prev480": p480, "path": tmp})
            print(f"    take{take+1}: {dur:.2f}s last80={l80:.0f} {'完整' if is_complete(takes[-1]) else '截断'}")
        time.sleep(THROTTLE)  # 3 take 烧满再择优（先时长过滤再取尾部最静），与 spec §12 一致
    maxd = max((t["dur"] for t in takes), default=0)
    cands = [t for t in takes if t["dur"] >= DUR_RATIO*maxd and is_complete(t)]
    if not cands:
        sys.exit(f"[fatal] 「{text[:18]}…」{MAX_TAKES} take 全败。删除 public/audio 整批重跑（禁单句补录）。")
    best = min(cands, key=lambda t: t["last80"])
    dest.write_bytes(best["path"].read_bytes())
    for t in takes:
        t["path"].unlink()
    return {"durationSec": round(best["dur"], 3)}

def main() -> None:
    key = load_key()
    content = json.loads(CONTENT.read_text(encoding="utf-8"))
    fixed_dir, lines_dir = AUDIO_DIR/"fixed", AUDIO_DIR/"lines"
    fixed_dir.mkdir(parents=True, exist_ok=True)
    lines_dir.mkdir(parents=True, exist_ok=True)
    meta = {
        "voice": VOICE, "model": MODEL, "rate": 1.0, "fps": 30,
        "breathSec": 0.18, "act5TailSec": 2.0, "fixed": {}, "lines": [],
    }
    for name, text in FIXED_LINES.items():
        dest = fixed_dir/f"{name}.wav"
        print(f"[fixed:{name}] {text}")
        r = synthesize_line(key, text, dest)
        meta["fixed"][name] = {"file": f"audio/fixed/{name}.wav", **r}
        time.sleep(THROTTLE)
    for i, sc in enumerate(content["scenes"], start=1):
        dest = lines_dir/f"line_{i:02d}.wav"
        print(f"[line_{i:02d}] ({sc['id']}) {sc['ttsText'][:24]}…")
        r = synthesize_line(key, sc["ttsText"], dest)
        meta["lines"].append({"index": i, "sceneId": sc["id"], "file": f"audio/lines/line_{i:02d}.wav",
                              "durationSec": r["durationSec"], "text": sc["ttsText"]})
        time.sleep(THROTTLE)
    META_OUT.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(m["durationSec"] for m in meta["lines"])
    print(f"[done] 正文 {len(meta['lines'])} 句净长 {total:.1f}s → {META_OUT}")

if __name__ == "__main__":
    main()
