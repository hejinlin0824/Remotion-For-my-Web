import { ACT5_TAIL_SEC, BREATH_SEC, CHAPTER_F, CHAPTER_TITLES, FPS } from "./constants";
import type { ActKey, AudioMeta, ComponentKey, ContentJson, SceneProps } from "./types";

export interface ActWindow { act: 1 | 2 | 3 | 4 | 5; startFrame: number; durFrames: number }
export interface SceneWindow {
  index: number; sceneId: string; act: ActKey; component: ComponentKey; props: SceneProps;
  startFrame: number; durFrames: number; lineFile: string; stepAnchor?: string;
}
export interface ChapterSlot { act: ActKey; fromFrame: number; title: string }
export interface Timeline {
  acts: ActWindow[]; scenes: SceneWindow[]; chapterSlots: ChapterSlot[];
  problemScene: SceneWindow; act4StartFrame: number; totalFrames: number;
}
const frame = (sec: number) => Math.round(sec * FPS);
const BREATH_F = frame(BREATH_SEC);

export function buildTimeline(content: ContentJson, audio: AudioMeta): Timeline {
  const scenes = content.scenes;
  const actOrder = scenes.map((s) => s.act);
  const sorted = [...actOrder].sort((a, b) => a - b);
  if (JSON.stringify(actOrder) !== JSON.stringify(sorted)) {
    throw new Error("scenes 必须按 act 顺序排列（act2 → act3 → act4）");
  }
  const firstAct2 = scenes.find((s) => s.act === 2);
  if (!firstAct2 || firstAct2.component !== "problem-card") {
    throw new Error("act2 第一场必须是 problem-card");
  }
  const lineBy = new Map(audio.lines.map((l) => [l.sceneId, l]));

  const acts: ActWindow[] = [];
  const windows: SceneWindow[] = [];
  let t = 0;
  acts.push({ act: 1, startFrame: 0, durFrames: frame(audio.fixed.act1.durationSec) });
  t = acts[0].durFrames;

  scenes.forEach((s, i) => {
    const line = lineBy.get(s.id);
    if (!line) throw new Error(`场景 ${s.id} 没有对应音频行（audio_meta.lines.sceneId）`);
    const start = t;
    const dur = frame(line.durationSec);
    windows.push({
      index: i, sceneId: s.id, act: s.act, component: s.component,
      props: s.props ?? {}, startFrame: start, durFrames: dur, lineFile: line.file,
      stepAnchor: s.props?.stepRef != null ? content.steps[s.props.stepRef - 1]?.usesAnchor : undefined,
    });
    t = start + dur + BREATH_F;
  });

  const act5Start = t - BREATH_F;
  acts.push({ act: 5, startFrame: act5Start, durFrames: frame(audio.fixed.act5.durationSec + ACT5_TAIL_SEC) });

  const acts234: ActWindow[] = [];
  for (const act of [2, 3, 4] as ActKey[]) {
    const group = windows.filter((w) => w.act === act);
    acts234.push({ act, startFrame: group[0].startFrame, durFrames: group[group.length - 1].startFrame + group[group.length - 1].durFrames - group[0].startFrame });
  }
  const act2FirstKnowledge = windows.find((w) => w.act === 2 && w.component === "knowledge-card");
  if (!act2FirstKnowledge) throw new Error("act2 至少需要一场 knowledge-card（章节字挂靠点）");
  const chapterSlots: ChapterSlot[] = [
    { act: 2, fromFrame: act2FirstKnowledge.startFrame, title: CHAPTER_TITLES[2] },
    { act: 3, fromFrame: acts234[1].startFrame, title: CHAPTER_TITLES[3] },
    { act: 4, fromFrame: acts234[2].startFrame, title: CHAPTER_TITLES[4] },
  ];

  return {
    acts: [acts[0], ...acts234, acts[1]],
    scenes: windows,
    chapterSlots,
    problemScene: windows[0],
    act4StartFrame: acts234[2].startFrame,
    totalFrames: act5Start + acts[1].durFrames,
  };
}
