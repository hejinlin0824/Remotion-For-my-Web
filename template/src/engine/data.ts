import contentJson from "../data/content.json";
import audioMetaJson from "../data/audio_meta.json";
import { buildTimeline } from "./timeline";
import { validateContract } from "./contract";
import type { AudioMeta, ContentJson } from "./types";

export const content = contentJson as unknown as ContentJson;
export const audioMeta = audioMetaJson as unknown as AudioMeta;

const errs = validateContract(content, audioMeta);
if (errs.length > 0) {
  throw new Error("content.json 契约校验失败:\n" + errs.join("\n"));
}
export const timeline = buildTimeline(content, audioMeta);
