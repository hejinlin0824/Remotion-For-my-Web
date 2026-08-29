export type AspectKey = "16:9" | "9:16";
export type ComponentKey =
  | "problem-card" | "knowledge-card" | "step-card" | "derivation-popup"
  | "pitfall-card" | "checklist-card" | "general-list";
export type ActKey = 2 | 3 | 4;

export interface Segment { type: "text" | "math"; value: string }
export interface ProblemLine { id: string; segments: Segment[] }
export interface Knowledge { claim: string; formula: string; premise: string; trap: string }
export interface Step { usesAnchor: string; statement: string; derivation: string; note: string }
export interface Pitfall { claim: string; why: string }
export interface MethodItem { step: string; trick: string }

export interface SceneProps {
  stepRef?: number; formula?: string; knowledgeRef?: number;
  pitfallRef?: number; pitfallRefs?: number[]; itemRef?: number;
}
export interface Scene { id: string; act: ActKey; component: ComponentKey; ttsText: string; props?: SceneProps }
export interface ContentJson {
  meta: { aspect: AspectKey; problemType: string };
  problem: { lines: ProblemLine[] };
  knowledge: Knowledge[]; steps: Step[]; pitfalls: Pitfall[]; generalMethod: MethodItem[];
  scenes: Scene[];
}
export interface AudioLine { index: number; sceneId: string; file: string; durationSec: number; text: string }
export interface AudioMeta {
  voice: string; model: string; rate: number; fps: number;
  breathSec: number; act5TailSec: number;
  fixed: { act1: { file: string; durationSec: number }; act5: { file: string; durationSec: number } };
  lines: AudioLine[];
}
